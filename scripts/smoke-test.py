#!/usr/bin/env python3
"""
JurisCore Phase 1 smoke test.

Exercises a *running* application over HTTP: startup, authentication, credential
rejection, tenant isolation and role enforcement. It is the outside-in counterpart to the
integration suite — those tests prove the same properties in-process against Testcontainers,
this one proves the deployed thing actually behaves that way on your machine.

Standard library only, so there is nothing to install.

    python3 scripts/smoke-test.py                       # http://localhost:8080
    python3 scripts/smoke-test.py http://localhost:9000

Exit codes: 0 all passed, 1 a check failed, 2 could not run (app down, or rate limited).

Note on scope: full role-matrix RBAC needs a non-admin account, and activating an invited
user requires the one-time token that is published on a domain event and deliberately
never returned by the API or written to a log. That is correct behaviour, so this script
does not try to work around it — SecurityGuaranteesIT covers those paths in-process, where
it can read the event bus. What is checked here is real either way: a FIRM_ADMIN is
allowed what a FIRM_ADMIN should be, and refused what it should not.
"""

import json
import sys
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
PASSWORD = "Adv0cate!Chamber"
RUN = str(int(time.time()))          # keeps each run's fixtures unique and re-runnable

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"
passed = failed = 0


def call(method, path, body=None, token=None):
    """Returns (status, parsed_json_or_raw_text)."""
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read().decode()
            status = response.status
    except urllib.error.HTTPError as e:
        raw, status = e.read().decode(), e.code
    except urllib.error.URLError as e:
        print(f"{RED}Cannot reach {BASE} — is the application running?{RESET}\n  {e.reason}")
        sys.exit(2)
    if status == 429:
        print(f"\n{YELLOW}Rate limited (429) on {method} {path}.{RESET}")
        print("  The sign-in endpoints allow 10 requests per minute per IP, and this")
        print("  script uses 7 of them. Either wait 60 seconds, or clear the counters:")
        print(f"{DIM}    docker compose exec -T redis redis-cli eval \\{RESET}")
        print(f"{DIM}      \"for _,k in ipairs(redis.call('keys','ratelimit:*')) do "
              f"redis.call('del',k) end return 1\" 0{RESET}")
        sys.exit(2)
    try:
        return status, json.loads(raw)
    except json.JSONDecodeError:
        return status, raw


def check(label, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print(f"  {GREEN}PASS{RESET}  {label}")
    else:
        failed += 1
        print(f"  {RED}FAIL{RESET}  {label}")
        if detail:
            print(f"        {DIM}{detail}{RESET}")


def section(title):
    print(f"\n{title}")


def register(firm, email):
    return call("POST", "/api/v1/auth/register", {
        "firmName": firm, "firstName": "Asha", "lastName": "Menon",
        "email": email, "password": PASSWORD, "timezone": "Asia/Kolkata",
    })


print(f"JurisCore smoke test  ->  {BASE}")

# ---------------------------------------------------------------- 1. it is running
section("1. Application is up")
status, health = call("GET", "/actuator/health")
check("GET /actuator/health returns 200", status == 200, f"got {status}")
check("overall status is UP", isinstance(health, dict) and health.get("status") == "UP",
      f"got {health}")
components = health.get("components", {}) if isinstance(health, dict) else {}
if components:
    check("PostgreSQL reports UP", components.get("db", {}).get("status") == "UP",
          f"db component: {components.get('db')}")
    check("Redis reports UP", components.get("redis", {}).get("status") == "UP",
          f"redis component: {components.get('redis')}")
else:
    print(f"  {DIM}note  component detail hidden (show-details is not 'always' on this "
          f"profile); run with the local or docker profile to see db and redis{RESET}")
status, _ = call("GET", "/actuator/health/readiness")
check("readiness probe returns 200", status == 200, f"got {status}")

# ------------------------------------------------------- 2. authentication responds
section("2. Authentication")
email_a = f"asha+{RUN}@sharma-legal.test"
status, body = register("Sharma & Associates " + RUN, email_a)
check("POST /auth/register creates a firm (201)", status == 201, f"got {status}: {body}")
data_a = body.get("data", {}) if isinstance(body, dict) else {}
token_a = data_a.get("accessToken", "")
refresh_a = data_a.get("refreshToken", "")
org_a = data_a.get("user", {}).get("organizationId", "")
check("registration returns an access token", bool(token_a))
check("registration returns a refresh token", bool(refresh_a))
check("first user is a FIRM_ADMIN", data_a.get("user", {}).get("role") == "FIRM_ADMIN")
check("no password material in the response", "passwordHash" not in json.dumps(body))

status, body = call("POST", "/api/v1/auth/login", {"email": email_a, "password": PASSWORD})
check("POST /auth/login succeeds (200)", status == 200, f"got {status}: {body}")

# ------------------------------------------------- 3. bad credentials are rejected
section("3. Invalid credentials are refused")
status, body = call("POST", "/api/v1/auth/login",
                    {"email": email_a, "password": "Wr0ng!PasswordHere"})
code = body.get("error", {}).get("code") if isinstance(body, dict) else None
check("wrong password returns 401", status == 401, f"got {status}")
check("error code is INVALID_CREDENTIALS", code == "INVALID_CREDENTIALS", f"got {code}")

status, body = call("POST", "/api/v1/auth/login",
                    {"email": f"nobody+{RUN}@nowhere.test", "password": PASSWORD})
unknown_code = body.get("error", {}).get("code") if isinstance(body, dict) else None
check("unknown address returns 401", status == 401, f"got {status}")
check("unknown address is indistinguishable from a wrong password",
      unknown_code == code, f"{unknown_code} vs {code}")

# ------------------------------------------------- 4. authenticated request works
section("4. Authenticated access")
status, body = call("GET", "/api/v1/users/me", token=token_a)
check("GET /users/me with a token returns 200", status == 200, f"got {status}: {body}")
check("it returns the caller's own identity",
      isinstance(body, dict) and body.get("data", {}).get("email") == email_a)

status, body = call("GET", "/api/v1/users/me")
code = body.get("error", {}).get("code") if isinstance(body, dict) else None
check("GET /users/me without a token returns 401", status == 401, f"got {status}")
check("error code is UNAUTHENTICATED", code == "UNAUTHENTICATED", f"got {code}")

status, body = call("GET", "/api/v1/does-not-exist", token=token_a)
code = body.get("error", {}).get("code") if isinstance(body, dict) else None
check("an unknown path is 404, not 500", status == 404, f"got {status}")
check("error code is RESOURCE_NOT_FOUND", code == "RESOURCE_NOT_FOUND", f"got {code}")

# ------------------------------------------------------------ 5. tenant isolation
section("5. Tenant isolation")
email_b = f"vikram+{RUN}@rao-chambers.test"
status, body = register("Rao Chambers " + RUN, email_b)
check("a second firm registers (201)", status == 201, f"got {status}: {body}")
data_b = body.get("data", {}) if isinstance(body, dict) else {}
admin_b = data_b.get("user", {}).get("id", "")
org_b = data_b.get("user", {}).get("organizationId", "")
check("the two firms are separate tenants", bool(org_a) and bool(org_b) and org_a != org_b)

status, body = call("GET", f"/api/v1/users/{admin_b}", token=token_a)
code = body.get("error", {}).get("code") if isinstance(body, dict) else None
check("firm A reading firm B's user returns 404", status == 404, f"got {status}")
check("it is reported absent, not forbidden — a 403 would confirm it exists",
      code == "USER_NOT_FOUND", f"got {code}")

status, _ = call("PATCH", f"/api/v1/users/{admin_b}/status?status=SUSPENDED", token=token_a)
check("firm A cannot suspend firm B's user (404)", status == 404, f"got {status}")

status, body = call("GET", "/api/v1/users", token=token_a)
items = body.get("data", {}).get("items", []) if isinstance(body, dict) else []
check("firm A's directory contains only firm A",
      len(items) == 1 and items[0].get("email") == email_a,
      f"got {[i.get('email') for i in items]}")

# ---------------------------------------------------------------------- 6. RBAC
section("6. Role enforcement")
status, body = call("GET", f"/api/v1/organizations/{org_b}", token=token_a)
code = body.get("error", {}).get("code") if isinstance(body, dict) else None
check("a FIRM_ADMIN is refused a SUPER_ADMIN endpoint (403)", status == 403, f"got {status}")
check("error code is ACCESS_DENIED", code == "ACCESS_DENIED", f"got {code}")

status, _ = call("GET", "/actuator/env", token=token_a)
check("a FIRM_ADMIN cannot read /actuator/env (403)", status == 403, f"got {status}")

status, body = call("POST", "/api/v1/users/invite", token=token_a, body={
    "email": f"ravi+{RUN}@sharma-legal.test", "firstName": "Ravi",
    "lastName": "Kulkarni", "role": "LAWYER"})
check("a FIRM_ADMIN may invite a lawyer (201)", status == 201, f"got {status}: {body}")
check("the invitee starts INVITED, not ACTIVE",
      isinstance(body, dict) and body.get("data", {}).get("status") == "INVITED")

status, body = call("GET", "/api/v1/organizations/current", token=token_a)
check("a FIRM_ADMIN may read its own organization (200)", status == 200, f"got {status}")

# ------------------------------------------------------- 7. refresh token rotation
section("7. Refresh token rotation and replay")
status, body = call("POST", "/api/v1/auth/refresh", {"refreshToken": refresh_a})
rotated = body.get("data", {}).get("refreshToken", "") if isinstance(body, dict) else ""
check("a refresh token can be exchanged (200)", status == 200, f"got {status}: {body}")
check("rotation issues a different token", bool(rotated) and rotated != refresh_a)

status, body = call("POST", "/api/v1/auth/refresh", {"refreshToken": refresh_a})
code = body.get("error", {}).get("code") if isinstance(body, dict) else None
check("replaying the old token is refused (401)", status == 401, f"got {status}")
check("error code is REFRESH_TOKEN_INVALID", code == "REFRESH_TOKEN_INVALID", f"got {code}")

status, _ = call("POST", "/api/v1/auth/refresh", {"refreshToken": rotated})
check("replay burns the whole chain, including the replacement", status == 401, f"got {status}")

# ------------------------------------------------------------------------ summary
total = passed + failed
print(f"\n{'-' * 62}")
if failed == 0:
    print(f"{GREEN}All {total} checks passed.{RESET}")
    sys.exit(0)
print(f"{RED}{failed} of {total} checks failed.{RESET}")
sys.exit(1)
