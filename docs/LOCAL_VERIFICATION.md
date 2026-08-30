# JurisCore Phase 1 — local verification

The procedure that takes Phase 1 from "statically verified" to "proven on a real machine".
Every command is run from `~/Desktop/JurisCore` and assumes **nothing is already running**.

Roughly 10–15 minutes end to end, most of it the first Maven dependency download.

If you only want the whole thing in one paste, skip to [§10](#10-one-shot-sequence). The
sections below are the same commands with the reasoning attached, which is what you want
the first time or when something fails.

---

## 1. Prerequisites

| Tool | Required | Check | If missing |
|---|---|---|---|
| JDK | **21** (exactly — the build targets release 21) | `java -version` | `brew install --cask temurin@21` |
| Maven | **3.9+** | `mvn -version` | `brew install maven` |
| Docker Engine | **24+** (BuildKit cache mounts) | `docker --version` | Docker Desktop |
| Docker Compose | **v2** (`docker compose`, not `docker-compose`) | `docker compose version` | ships with Docker Desktop |
| Python | **3.9+** (smoke test, stdlib only) | `python3 --version` | preinstalled with Xcode CLT |

```bash
cd ~/Desktop/JurisCore

java -version           # expect 21.x
mvn -version            # expect 3.9+, and "Java version: 21"
docker --version        # expect 24+
docker compose version  # expect v2.x
python3 --version       # expect 3.9+
```

**If `mvn -version` reports a JDK other than 21**, point it at the right one — Maven uses
`JAVA_HOME`, not whatever `java` resolves to:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -version            # confirm it now says 21
```

**Docker must be running and have ~4 GB available.** The test suite starts PostgreSQL and
Redis containers; the full stack adds LocalStack.

**Ports used:** 5432, 6379, 4566, 8080. If you already run PostgreSQL or Redis locally,
stop them first — `docker compose up` will fail to bind otherwise:

```bash
lsof -nP -iTCP:5432 -iTCP:6379 -iTCP:8080 -iTCP:4566 -sTCP:LISTEN
```

---

## 2. Clean slate

`-v` is the part that matters: it deletes the `postgres-data` volume, so the next start
runs Flyway against a genuinely empty database. Without it you are testing a migration
that already ran, which proves nothing.

```bash
docker compose down -v --remove-orphans
docker volume ls | grep juriscore || echo "no juriscore volumes remain"
```

---

## 3. Start the dependencies

```bash
docker compose up -d postgres redis localstack
docker compose ps
```

Wait until PostgreSQL and Redis report `healthy` (a few seconds). LocalStack takes ~25s
because its health check waits for the S3 bucket and SQS queues to be created, not merely
for the API to answer:

```bash
for svc in juriscore-postgres juriscore-redis juriscore-localstack; do
  printf 'waiting for %s' "$svc"
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$svc" 2>/dev/null)" = healthy ]; do
    printf '.'; sleep 2
  done
  echo ' healthy'
done
```

### Confirm the infrastructure is actually what it claims

```bash
# PostgreSQL reachable, and empty (Flyway has not run yet — the app does that)
docker compose exec -T postgres psql -U juriscore -d juriscore -c '\dn'

# Redis reachable
docker compose exec -T redis redis-cli ping                       # PONG

# LocalStack has the bucket, both queues and both dead-letter queues
docker compose exec -T localstack awslocal s3 ls
docker compose exec -T localstack awslocal sqs list-queues
```

Expect four queue URLs: `juriscore-notifications`, `juriscore-audit` and a `-dlq` for each.

---

## 4. Build and test

```bash
mvn -B clean verify
```

This runs, in order: compile → unit tests (Surefire) → integration tests (Failsafe) →
package. **Expect 74 test cases across 8 classes, 0 failures.**

The integration tests start **their own** PostgreSQL and Redis through Testcontainers on
random host ports. They do not use the Compose containers from §3 and cannot collide with
them — the Compose stack is for running the application, Testcontainers is for the tests.
Docker must be running for both.

What the suite holds down:

| Class | Kind | Covers |
|---|---|---|
| `StrongPasswordValidatorTest` | unit | password policy, including the blocklist |
| `JwtServiceTest` | unit | token round-trip, signature/issuer/expiry rejection, short-key refusal |
| `AuthServiceTest` | unit | lockout, account-enumeration resistance, rotation, reuse |
| `TenantGuardTest` | unit | the tenant guard's contract, ahead of Phase 2 |
| `AuthFlowIT` | integration | registration, sign-in, rotation, invitation, error envelope |
| `SecurityGuaranteesIT` | integration | token revocation, single-use reset links, RBAC, tenant isolation on writes |
| `RateLimitIT` | integration | limit, atomicity, stranded-key recovery, fail-open |
| `ActuatorExposureIT` | integration | monitoring surface leaks nothing |

Three of these are regression tests for defects found during review — a replayable
password-reset link, case-insensitive email uniqueness, and a rate-limit counter that could
strand and lock a user out permanently. If any of these fail, that is a real regression,
not a flaky test.

### Verify the migration ran against a clean database

The integration tests already did this — Testcontainers gives every run a brand-new
PostgreSQL and Flyway migrates it from empty. To see it directly:

```bash
mvn -B -pl juriscore-app -am test -Dtest=AuthFlowIT -DfailIfNoTests=false
```

---

## 5. Run the application

The `local` profile is the default; it expects the Compose dependencies from §3 on
localhost and carries a development-only JWT secret so a fresh checkout runs.

```bash
mvn -B -pl juriscore-app -am spring-boot:run
```

Leave it running and open a second terminal for §6. To run it in the background instead:

```bash
mvn -B -pl juriscore-app -am spring-boot:run > /tmp/juriscore.log 2>&1 &
echo $! > /tmp/juriscore.pid
```

Startup is complete when the log shows `Started JurisCoreApplication`. Flyway logs
`Successfully applied 1 migration` on the first run against a clean volume.

> **LocalStack is not required for the application to start.** Nothing in Phase 1 reads or
> writes S3 or SQS; the clients are configured but never called. If LocalStack is down the
> application starts and serves normally — only Phase 4 makes it a real dependency.

---

## 6. Health check

```bash
curl -s localhost:8080/actuator/health | python3 -m json.tool
```

Under the `local` profile this shows the component breakdown. Expect `"status": "UP"` at
the top and both datastores up:

```json
{
  "status": "UP",
  "components": {
    "db":    { "status": "UP", "details": { "database": "PostgreSQL" } },
    "redis": { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```

The probes are scoped differently on purpose:

```bash
curl -s localhost:8080/actuator/health/readiness   # includes the database
curl -s localhost:8080/actuator/health/liveness    # JVM only
```

Readiness gates traffic, so it fails when PostgreSQL is unreachable. Liveness gates
restarts, so a database outage must not appear there — restarting the app cannot fix a
database, and a liveness probe that fails during one turns an outage into a restart loop.
Redis is in neither probe, because the only thing using it (the rate limiter) fails open;
it is still reported on `/actuator/health`, which is what makes that a real dependency check.

**Confirm the monitoring surface gives nothing away:**

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/env          # 401
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/configprops  # 401
curl -s localhost:8080/actuator/health | python3 -c \
  "import sys; b=sys.stdin.read(); print('LEAK' if 'JURISCORE' in b or 'secret' in b.lower() else 'no secrets in health output')"
```

`env` and `configprops` are the endpoints that would publish the signing key and database
password. They are excluded from exposure *and* gated behind `SUPER_ADMIN`.

---

## 7. API smoke tests

With the application running:

```bash
python3 scripts/smoke-test.py
```

**Expect `All 38 checks passed.` and exit code 0.** It verifies, over real HTTP: the app is
up with both datastores; registration and sign-in work; a wrong password and an unknown
address fail *identically* (no account enumeration); an authenticated request succeeds and
an anonymous one is refused; one firm cannot read, list or modify another's users, and gets
404 rather than 403 because a 403 would confirm the record exists; a `FIRM_ADMIN` is refused
a `SUPER_ADMIN` endpoint but allowed its own; and a refresh token rotates once, with a
replay burning the whole chain.

Exit codes: `0` all passed · `1` a check failed · `2` could not run (app down, or rate limited).

> **If it exits 2 with a 429:** the sign-in endpoints allow 10 requests per minute per IP
> and the script uses 7, so a second run inside the same minute trips the limit. That is the
> limiter working. Either wait 60 seconds, or clear the counters:
>
> ```bash
> docker compose exec -T redis redis-cli eval \
>   "for _,k in ipairs(redis.call('keys','ratelimit:*')) do redis.call('del',k) end return 1" 0
> ```
>
> That deletes only the rate-limit counters, nothing else.

Full role-matrix RBAC (a `LAWYER` or `CLERK` being refused an admin action) is covered by
`SecurityGuaranteesIT`, not here: activating an invited user needs the one-time token, which
is published on a domain event and deliberately never returned by the API or written to a
log. The integration test can read the event bus; a shell script cannot, and working around
it would mean weakening the thing being tested.

---

## 8. Docker image build

```bash
docker compose build app
```

Or directly, which is what CI does:

```bash
docker build -t juriscore:local .
docker images juriscore:local
```

The build compiles inside a `maven:3.9-eclipse-temurin-21` stage and ships only the jar on
`eclipse-temurin:21-jre-jammy`, running as a non-root user. Dependencies are cached through
a BuildKit cache mount, so the second build is much faster than the first.

### Optional: the whole stack in Compose

```bash
docker compose up -d --build
docker compose ps
curl -s localhost:8080/actuator/health | python3 -m json.tool
python3 scripts/smoke-test.py
```

This exercises the `docker` profile and the container's own health check, which is the
configuration closest to a deployment.

---

## 9. Cleanup

```bash
# stop the app if you backgrounded it (spring-boot:run forks, so clear the port too)
kill "$(cat /tmp/juriscore.pid)" 2>/dev/null; rm -f /tmp/juriscore.pid
APP_ON_8080=$(lsof -ti tcp:8080 2>/dev/null || true)
if [ -n "$APP_ON_8080" ]; then kill $APP_ON_8080; fi

docker compose down -v --remove-orphans
docker volume ls | grep juriscore || echo "clean"
```

Testcontainers removes its own containers automatically. To check nothing was orphaned:

```bash
docker ps -a --filter label=org.testcontainers=true
```

---

## 10. One-shot sequence

Paste this whole block. It stops at the first failure and prints where.

```bash
cd ~/Desktop/JurisCore && set -e

echo "=== versions ==="
java -version; mvn -version | head -3; docker --version; docker compose version; python3 --version

echo "=== clean slate ==="
docker compose down -v --remove-orphans

echo "=== dependencies ==="
docker compose up -d postgres redis localstack
# LocalStack is included because its health check waits for the bucket and queues to be
# created, and the sqs call below would otherwise race that bootstrap (~25s).
for i in $(seq 1 90); do
  # `if` keeps `set -e` from aborting on the iterations where it is not ready yet
  if [ "$(docker inspect -f '{{.State.Health.Status}}' juriscore-postgres 2>/dev/null)" = healthy ] \
  && [ "$(docker inspect -f '{{.State.Health.Status}}' juriscore-redis 2>/dev/null)" = healthy ] \
  && [ "$(docker inspect -f '{{.State.Health.Status}}' juriscore-localstack 2>/dev/null)" = healthy ]; then
    break
  fi
  sleep 2
done
docker compose ps
docker compose exec -T redis redis-cli ping
docker compose exec -T localstack awslocal sqs list-queues

echo "=== build, unit tests, integration tests ==="
mvn -B clean verify

echo "=== start the application ==="
mvn -B -pl juriscore-app -am spring-boot:run > /tmp/juriscore.log 2>&1 &
APP_PID=$!
for i in $(seq 1 60); do
  if curl -sf localhost:8080/actuator/health >/dev/null 2>&1; then break; fi
  sleep 2
done

echo "=== health ==="
curl -s localhost:8080/actuator/health | python3 -m json.tool
echo "actuator/env must be closed:"
curl -s -o /dev/null -w '  %{http_code} (expect 401)\n' localhost:8080/actuator/env

echo "=== smoke tests ==="
python3 scripts/smoke-test.py

echo "=== docker image ==="
docker build -t juriscore:local .

echo "=== cleanup ==="
kill $APP_PID 2>/dev/null || true
# spring-boot:run forks a JVM, so killing Maven alone can orphan the application
APP_ON_8080=$(lsof -ti tcp:8080 2>/dev/null || true)
if [ -n "$APP_ON_8080" ]; then kill $APP_ON_8080 2>/dev/null || true; fi
docker compose down -v --remove-orphans

echo
echo "PHASE 1 VERIFIED"
```

If it stops early, the failing step is the last thing printed; `/tmp/juriscore.log` has the
application log.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `mvn -version` shows JDK 17 or 23 | `JAVA_HOME` points elsewhere | `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` |
| `Could not find or load main class` | stale `target/` from another JDK | `mvn clean` |
| Testcontainers: `Could not find a valid Docker environment` | Docker not running, or a non-Desktop runtime | start Docker Desktop; for Colima, `export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` |
| `Bind for 0.0.0.0:5432 failed` | a local PostgreSQL already owns the port | stop it, or change the published port in `docker-compose.yml` |
| App exits: `juriscore.security.jwt.secret must not be blank` | not running under `local`/`docker` | it is the default profile; check `SPRING_PROFILES_ACTIVE` is unset, or `export JURISCORE_JWT_SECRET=$(openssl rand -base64 48)` |
| Health is `DOWN` with `redis` down | Compose Redis not up | `docker compose up -d redis` |
| Flyway: `Validate failed` | volume kept from an older schema | `docker compose down -v` and start again |
| Smoke test exits 2 with 429 | rate limiter working as designed | wait 60s or flush `ratelimit:*` (see §7) |
| Smoke test cannot connect | app not started yet | wait for `Started JurisCoreApplication` in `/tmp/juriscore.log` |
