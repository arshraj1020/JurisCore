# JurisCore

Enterprise legal case management and court workflow platform for law firms, advocates and
their clients.

**Status: Phase 1 (Foundation) — multi-tenant identity, authentication and the platform
skeleton.** Cases, hearings, tasks, documents and billing are scheduled for later phases;
see [Roadmap](#roadmap).

---

## What is here

| Capability | State |
|---|---|
| Multi-tenant data model with a hard tenant boundary | Done |
| Firm self-registration (organization + first administrator) | Done |
| Sign-in, JWT access tokens, rotating refresh tokens | Done |
| Password reset, invitation and activation flow | Done |
| Five-role RBAC (`SUPER_ADMIN`, `FIRM_ADMIN`, `LAWYER`, `CLERK`, `CLIENT`) | Done |
| Account lockout, per-caller rate limiting, request correlation ids | Done |
| Uniform response envelope and error catalogue | Done |
| Flyway migrations, schema-per-module | Done |
| Docker Compose stack: PostgreSQL, Redis, LocalStack (S3 + SQS) | Done |
| Domain events with commit-ordered delivery | Done (in-process; SQS not implemented) |
| OpenAPI / Swagger UI | Done |
| Unit tests and Testcontainers integration tests | Done |
| GitHub Actions CI with image build and vulnerability scan | Done |
| Clients, cases, hearings, tasks, documents | Done (Phases 2–4) |
| Invoices, line items, recorded payments, overdue sweep | Done (Phase 5) |
| In-app notifications and per-user category preferences | Done (Phase 5) |
| Append-only audit trail with a firm-admin query API | Done (Phase 5) |

## Verification status

Phase 1 has been verified as far as this environment allows, and it is worth being precise
about where that line falls.

**Executed and passing:** the Flyway migration against a clean PostgreSQL 16, three times
on three fresh databases (schemas, column types, foreign keys with `ON DELETE CASCADE`,
indexes, unique and check constraints, all asserted behaviourally); every entity mapping
cross-checked against the live DDL, which is what `ddl-auto: validate` does at startup; the
case-insensitive email index, including a measured 5.3 ms sequential scan versus 0.08 ms
indexed on 20k users; the rate-limiter's Lua script extracted from the committed source and
run against a real Redis — limit, atomicity under 200 concurrent callers, and recovery of a
stranded counter; the LocalStack bootstrap against a real AWS API emulator, with DLQ redrive
proven by pushing a poison message through it; `REQUIRES_NEW` transaction semantics
demonstrated directly on PostgreSQL, with a counter-proof that a single transaction would
undo the revocation; the smoke test validated against a stub — 38/38 on correct behaviour,
and it correctly fails when a cross-tenant 403 or an account-enumeration oracle is injected;
`docker compose config`, Dockerfile instruction parsing, and the CI workflow's structure.

**Not executed here:** `mvn verify`, application startup, and the Docker image build.
Maven Central and the Docker registry are both blocked by this environment's network
allowlist, so no dependency can be resolved and no base image pulled. In place of a
compiler the tree is checked with a Java 21 parser (83/83 files parse) and a
cross-reference pass over enum constants, Lombok builder properties, constructor arity,
static-call arity, Spring Data derived-query property names and JPQL paths — zero
unresolved references, zero unused imports. That is a good deal stronger than a read-through
and still not a compiler.

**[docs/LOCAL_VERIFICATION.md](docs/LOCAL_VERIFICATION.md) is the procedure that closes the
gap** — prerequisites through teardown, ending in one paste-able block.

## Architecture in one paragraph

JurisCore is a **modular monolith**, not a set of microservices — yet. One deployable
unit contains several modules that keep the boundaries a service split would need:
separate packages, separate database schemas, no module reaching into another's
repositories, and communication through domain events rather than shared tables. That
buys the design discipline of microservices without paying, on day one, for distributed
transactions, cross-service debugging and eight deployment pipelines protecting roughly
zero users. When a module earns its own scaling profile — documents will, first — moving
it out is a packaging change rather than a rewrite. The full reasoning is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

```
juriscore/
├── juriscore-common/        Shared kernel: API envelope, error catalogue, base
│                            entities, tenant context, domain event contracts
├── juriscore-organization/  Law firms — the tenant boundary itself
├── juriscore-identity/      Users, authentication, JWT, RBAC, sessions
├── juriscore-app/           The deployable: configuration, migrations, filters,
│                            composition of every module
├── docker/                  LocalStack bootstrap (S3 bucket, SQS queues + DLQs)
└── .github/workflows/       CI
```

## Running it

### Prerequisites

- JDK 21
- Maven 3.9+
- Docker and Docker Compose

### Everything in containers

```bash
docker compose up --build
```

The API comes up on <http://localhost:8080>, Swagger UI on
<http://localhost:8080/swagger-ui.html>.

### Infrastructure in containers, app from your IDE

Faster to iterate on, and the usual way to work:

```bash
docker compose up -d postgres redis localstack
mvn -pl juriscore-app -am spring-boot:run
```

The `local` profile is active by default and carries a development JWT secret, so a
fresh checkout runs with no further setup.

### First requests

```bash
# 1. Register a firm and its first administrator
curl -sX POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
        "firmName": "Sharma & Associates",
        "firstName": "Asha",
        "lastName": "Menon",
        "email": "asha@sharma-legal.test",
        "password": "Adv0cate!Chamber",
        "timezone": "Asia/Kolkata"
      }'

# 2. Sign in (returns accessToken + refreshToken)
curl -sX POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "asha@sharma-legal.test", "password": "Adv0cate!Chamber"}'

# 3. Call something protected
curl -s http://localhost:8080/api/v1/users/me \
  -H "Authorization: Bearer <accessToken>"
```

## API conventions

Every endpoint answers in one of two shapes.

```jsonc
// success
{ "success": true, "data": { }, "message": "Firm registered successfully" }

// failure
{ "success": false, "error": { "code": "EMAIL_ALREADY_EXISTS", "message": "…" } }
```

`error.code` is a stable enum from `ErrorCode` — clients switch on it; `message` is for
humans and may change. Validation failures add `error.details[]` with per-field messages.

### Phase 1 endpoints

| Method | Path | Access |
|---|---|---|
| POST | `/api/v1/auth/register` | public |
| POST | `/api/v1/auth/login` | public |
| POST | `/api/v1/auth/refresh` | public |
| POST | `/api/v1/auth/logout` | authenticated |
| POST | `/api/v1/auth/forgot-password` | public |
| POST | `/api/v1/auth/reset-password` | public |
| GET/PUT | `/api/v1/users/me` | authenticated |
| POST | `/api/v1/users/me/change-password` | authenticated |
| GET | `/api/v1/users` | firm staff |
| GET | `/api/v1/users/{id}` | firm staff |
| POST | `/api/v1/users/invite` | `FIRM_ADMIN` |
| PATCH | `/api/v1/users/{id}/status` | `FIRM_ADMIN` |
| PATCH | `/api/v1/users/{id}/role` | `FIRM_ADMIN` |
| GET/PUT | `/api/v1/organizations/current` | authenticated / `FIRM_ADMIN` |

## Decisions worth knowing about

**Tenant isolation has three layers; two are load-bearing today.** Every tenant-scoped
table carries `organization_id` (enforced by a check constraint), and every repository
query filters on it. The third, `TenantGuard`, is built and unit-tested but not yet called
by anything — no Phase 1 entity is tenant-scoped through it, since `User` deliberately is
not (`SUPER_ADMIN` has no firm). It is the substrate Phase 2 hangs cases and documents off.
Said plainly here because "defence in depth" is the kind of claim that quietly stops being
true.

**A foreign tenant's resource returns 404, not 403.** A 403 confirms the record exists.

**Access tokens are short-lived and revocable.** Fifteen minutes, and they carry a
`token_generation` claim checked against the user row on each request. Changing a
password, changing a role or suspending an account bumps that number, so outstanding
tokens stop validating immediately instead of lingering for their remaining life. The
cost is one indexed primary-key lookup per request — the alternative is a suspended
lawyer reading case files after being locked out.

**Refresh tokens rotate and detect reuse.** Only a SHA-256 hash is stored. Each refresh
revokes the presented token and issues a new one; presenting an already-rotated token
means a replay or a stolen chain, so every session for that user is revoked. That
revocation commits in its own transaction — the failure response rolls the caller's
transaction back, which would otherwise undo it.

**Password reset never reveals whether an address is registered**, and sign-in with an
unknown address still runs a BCrypt comparison so the timing matches a wrong password.

**Optimistic locking everywhere.** Every entity carries `@Version`, so two lawyers
editing the same case get a `CONCURRENT_MODIFICATION` conflict rather than a silent
overwrite (PRD §41.1).

**Rate limiting lives in Redis, not in memory.** A per-instance counter multiplies the
real limit by the number of running tasks — exactly wrong under autoscaling. It fails
open: losing the cache should degrade protection, not take the platform down.

**Email is globally unique, not unique per firm, and uniqueness is case-insensitive.**
Sign-in presents an address and a password with no tenant hint, so it has to resolve to
exactly one account. The constraint is a functional unique index on `lower(email)` rather
than `UNIQUE(email)`: the application matches addresses case-insensitively, so a plain
constraint would admit both `asha@firm.test` and `ASHA@firm.test`, and two rows matching
one lookup turn sign-in into a 500. It is also the only form the planner can use for the
`lower(email) = lower(?)` that Spring Data generates — measured on 20k users, 5.3 ms of
sequential scan versus 0.08 ms indexed, on the busiest endpoint in the system.

**The rate limiter counts and expires in one atomic Lua script.** `INCR` followed by
`EXPIRE` is two round-trips, and a process that dies between them strands a counter with
no TTL — locking that caller out of sign-in permanently.

**`open-in-view` is off** and Hibernate validates the Flyway-built schema rather than
generating one.

## Testing

```bash
mvn test        # unit tests
mvn verify      # + integration tests (needs Docker for Testcontainers)
```

For a full clean-machine procedure — prerequisites, infrastructure, startup, health,
smoke tests, image build and teardown — see **[docs/LOCAL_VERIFICATION.md](docs/LOCAL_VERIFICATION.md)**,
which ends in a single paste-able block. Against a running application,
`python3 scripts/smoke-test.py` checks the same guarantees over real HTTP.

Integration tests run against a real PostgreSQL container rather than H2. Schemas, check
constraints, `timestamptz` semantics and functional indexes are the parts an in-memory
database gets subtly wrong, and they are the parts worth testing.

| Suite | What it holds down |
|---|---|
| `AuthFlowIT` | Registration, sign-in, rotation, reuse detection, invitation, cross-tenant reads, and the error envelope for unknown paths, missing params and bad enum values |
| `SecurityGuaranteesIT` | Suspension/role/password changes killing live access tokens; refresh rotation; reuse revocation surviving the failed request; single-use reset links; RBAC; tenant isolation on **writes** |
| `RateLimitIT` | Limit enforcement, bucket isolation, atomic expiry, recovery of a stranded counter, atomicity under 200 concurrent callers, fail-open when Redis is down |
| `TenantGuardTest` | The guard's contract, ahead of Phase 2 depending on it |
| `ActuatorExposureIT` | Health is public and UP; readiness covers the database and liveness does not; env/configprops/beans/metrics closed even to a firm admin; no response carries the signing secret |
| `AuthServiceTest`, `JwtServiceTest`, `StrongPasswordValidatorTest` | Lockout, enumeration resistance, token round-trip, signature/issuer/expiry rejection, password policy |

Two of these encode bugs that were found and fixed during verification, so they are
regression tests rather than decoration: `SecurityGuaranteesIT.resetLinkIsSingleUse` and
`RateLimitIT.repairsAnImmortalCounter`.

## Configuration

Everything environment-specific is an environment variable with a local default; see
[`.env.example`](.env.example). Profiles: `local` (default), `docker`, `test`, `prod`.

`JURISCORE_JWT_SECRET` has **no default outside local development** — the application
refuses to start without it rather than signing tokens with a value from a tutorial.
Generate one with `openssl rand -base64 48`.

## Roadmap

Phases follow the PRD.

- **Phase 1 — Foundation**: project structure, identity, authentication,
  PostgreSQL, Docker, CI.
- **Phase 2 — Core legal system**: clients, lawyers, cases, case timeline.
- **Phase 3 — Court workflow**: courts, hearings, tasks, deadlines,
  reminders. Reminders are scheduled and published as domain events when they come due;
  **nothing delivers them** — there is still no email, SMS or push anywhere in the
  platform, so a reminder's `SENT` state means "announced on the event bus", not
  "received by a person".
- **Phase 4 — Documents** *(this release)*: case documents in S3, presigned upload and
  download, metadata in PostgreSQL. Files never pass through the application: the browser
  PUTs to a short-lived signed link and the platform confirms the upload against storage
  afterwards. **Not implemented, and not claimed anywhere:** malware or content scanning,
  OCR, previews, full-text search, document version history, external sharing, and any
  client-facing access — a client of a firm still cannot reach its documents, because the
  explicit sharing mechanism that would allow it does not exist.
- **Phase 5 — Billing, notifications and audit** *(this release)*: invoices with
  server-calculated money, recorded payments, an in-app notification feed with per-user
  category switches, and an append-only audit trail. Three new modules —
  `juriscore-billing`, `juriscore-notifications`, `juriscore-audit` — on three new schemas.

  **What Phase 5 is not**, stated plainly because several of these are one word away from
  what it does:

  - **No payment gateway, and no payment processing of any kind.** JurisCore *records*
    that money arrived; it never moves any. There is no Stripe, no Razorpay, no card
    network, no UPI handle and no bank connection. `PaymentMethod.CARD` is a label a person
    picked from a list, not a charge. No card number, CVV, bank credential or gateway
    secret is stored anywhere — there is no column that could hold one.
  - **No GST engine.** Invoices carry a tax rate and a tax amount per line, and that is the
    whole of it: no CGST/SGST/IGST split, no place-of-supply derivation, no reverse charge,
    no HSN/SAC codes, no return filing. A firm records the tax it has already worked out.
    Nothing here is a claim of statutory compliance.
  - **No accounting integration.** Nothing exports to Tally, Zoho Books or anything else.
  - **No email, SMS, WhatsApp or push.** Notifications are in-app rows read through the
    API, and that is the only delivery channel that exists. There is no delivery status
    column, no provider message id and no retry count — because nothing is sent.
  - **No SQS or Kafka.** The event bus is still in-process, exactly as in Phase 1. The
    `notification-queue` and `audit-queue` settings in `application.yml` remain unused
    placeholders.
  - **No client billing portal.** A `CLIENT` user reaches no billing endpoint. An invoice
    references a client because it is a firm-side record *about* them, not a document
    shared *with* them.
  - **No credit notes and no refunds.** Correcting an issued invoice means cancelling it
    and raising another. Half a credit-note subsystem would be worse than none.
  - **No currency conversion.** Every invoice and payment stores its currency and a payment
    in a different one is refused rather than converted. There is no FX rate anywhere.
  - **No analytics and no Redis caching**, both of which earlier notes filed under
    "Phase 5". Neither is implemented.
- **Phase 6 — Production**: AWS deployment, monitoring, autoscaling, security hardening,
  load testing.

## Notes

The source PRD is titled *JurisCore* but refers to the product as *LexFlow* throughout
the body. This implementation uses **JurisCore** consistently — worth settling before the
name reaches a public API path or an S3 bucket.
