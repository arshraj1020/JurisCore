# JurisCore — architecture notes

Decisions, and the reasoning behind them, for the Phase 1 foundation. Where this
document departs from the PRD it says so and why.

## 1. Modular monolith before microservices

The PRD specifies eight independently deployable services behind an API gateway. That is
the right *destination*; it is a poor place to start, and the difference matters.

What microservices cost on day one:

- **Distributed transactions.** "Create case, assign lawyer, notify client" is one
  database transaction in a monolith and a saga with compensating actions across three
  services otherwise.
- **Operational surface.** Eight pipelines, eight dashboards, eight sets of alerts, and a
  local environment nobody can run in full.
- **Debugging.** A failed request becomes a correlation-id hunt across services instead
  of a stack trace.
- **Boundaries chosen too early.** Service boundaries are hard to move once they are
  network calls. Getting them wrong produces a distributed monolith — every cost, none of
  the benefit.

What the platform actually needs on day one: correct tenant isolation, a schema that will
not need rewriting, and a working case lifecycle.

So JurisCore ships as one deployable with the boundaries a split will need already in
place:

| Discipline | How it is enforced now |
|---|---|
| Module owns its data | One PostgreSQL schema per module; no cross-schema foreign keys |
| No back doors | A module never injects another module's repositories — only its service API |
| Async where it will be async | Domain events, published after commit, never inline calls to a would-be consumer |
| Stateless authorization | The JWT carries user, tenant and role, so no module has to call identity per request |

When a module earns its own service — documents will first, since upload traffic scales
differently from everything else — the work is: move the package, point it at its schema,
replace the in-process event bus with SQS for that module's events. The domain code does
not change.

**Trigger to split**, written down now so the decision is not made on vibes later:

1. A module's scaling profile diverges enough that scaling the whole app is wasteful, or
2. a module's deploy cadence is blocked by the rest of the codebase, or
3. a module's failure modes need isolating (a stuck document conversion must not consume
   the pool serving hearing reminders).

Absent one of these, splitting adds cost and removes nothing.

## 2. Tenant isolation

The one thing this platform cannot get wrong. A leak across firms is not a bug report,
it is a professional-privilege incident.

Three independent layers, on the assumption that any one of them will eventually be
missed. **Two are load-bearing today; the third is built and tested but not yet on any
request path** — worth stating plainly, because "defence in depth" is exactly the sort of
claim that quietly stops being true:

1. **Schema — active.** Every tenant-scoped table carries a non-null `organization_id`. A
   check constraint on `identity.users` enforces that only a `SUPER_ADMIN` may have none;
   an insert violating it is rejected by PostgreSQL, verified in `AuthFlowIT` and by the
   migration tests.
2. **Query — active.** Repository methods take `organizationId` as part of the predicate —
   see `UserRepository`, where the only lookup without it is the sign-in path, before a
   tenant is known. `SecurityGuaranteesIT` asserts this for reads *and writes*: a firm
   admin holding a valid token cannot suspend or re-role another firm's user.
3. **Guard — active since Phase 2.** `TenantGuard` is called on every tenant-scoped entity
   the moment it is loaded, in `CaseAccess` (casework) and in every case-management
   service. It is redundant with layer 2 today and is meant to be: it is the layer that
   still holds the day somebody adds a lookup without the predicate. No Phase 1 entity is
   a `TenantAwareEntity` — `User` deliberately is not, since `SUPER_ADMIN` has no tenant —
   which is why it sat unwired until there were tenant-scoped rows to guard.

Both active layers return the module's *not found* code for a foreign tenant rather than
*forbidden*, because a 403 confirms the record exists.

Row-level security in PostgreSQL is the natural fourth layer and is worth adding when the
first module reaches an untrusted query path.

## 3. Authentication

**Access token: 15 minutes, HS256, carries `sub`, `org`, `role`, `gen`.** Everything
needed to authorize a request travels with it, so no module has to call identity per
request — the property that makes an eventual service split cheap.

**Stateless tokens that can still be revoked.** A pure JWT cannot be withdrawn before it
expires; for a platform where a suspended lawyer must lose access *now*, a 15-minute
window is too long. The `gen` claim is checked against `users.token_generation` on each
request. Changing a password, changing a role or suspending an account increments it, and
every outstanding token for that user fails on its next use. The cost is one two-column
primary-key lookup per request. When that read volume justifies it, that query goes behind
a Redis cache evicted on the same three events.

**Refresh token: 14 days, opaque, 256 bits from a CSPRNG, stored as SHA-256.** BCrypt is
for low-entropy secrets that must resist offline brute force; there is nothing to brute
force in a random 256-bit token, and the lookup needs an indexed equality match. Passwords
use BCrypt at cost 12.

**Rotation with reuse detection.** Each refresh revokes the presented token and issues a
replacement, recording `replaced_by`. Presenting an already-rotated token means a replay
or a stolen chain in parallel use; both revoke every session for that user. Losing a
session is an annoyance, leaving a thief with a valid chain is not. That revocation runs
in `REQUIRES_NEW` because the caller's transaction is about to roll back.

**Account enumeration.** `forgot-password` responds identically for known and unknown
addresses, and sign-in against an unknown address still runs a BCrypt comparison against a
fixed dummy hash so the timing matches.

## 4. Data model

- **UUID primary keys**, generated in the application. Sequential integer ids leak volume
  ("how many cases does this firm have?") and make cross-service references brittle.
- **`@Version` on every entity.** Two lawyers editing one case get an HTTP 409 with
  `CONCURRENT_MODIFICATION` rather than a lost update (PRD §41.1).
- **Audit columns on every entity** — `created_at/by`, `updated_at/by` — populated from
  the security context. This is the substrate the Phase 5 audit service consumes; it is not
  a substitute for it.
- **`timestamptz` throughout, UTC in the JVM.** Hearing times are rendered in the firm's
  configured zone at the edge. Storing local times is how a hearing gets missed by
  five and a half hours.
- **Enums as strings with check constraints**, not ordinals. Ordinals break the moment
  someone inserts a value into the middle of the enum.
- **Email uniqueness is a functional index on `lower(email)`**, not `UNIQUE(email)`. The
  application treats addresses case-insensitively, so a plain constraint would accept both
  `asha@firm.test` and `ASHA@firm.test` — and two rows matching one case-insensitive
  lookup make `Optional<User>` throw, turning sign-in into a 500. It is also the only form
  the planner can use for the `lower(email) = lower(?)` that Spring Data generates:
  measured on 20k users, a sequential scan touching every row at 5.3 ms versus 0.08 ms
  indexed, on the hottest and most-attacked endpoint in the system.

### A trap worth knowing about

`@Modifying(clearAutomatically = true)` detaches *every* managed entity in the caller's
persistence context, not just the rows the bulk update touched. Any entity the caller
mutates afterwards is silently dropped — no exception, no log line, the write simply never
happens. This cost us a real bug: the password-reset token was marked used after such a
call and therefore never was, leaving supposedly single-use reset links replayable for
their full 30-minute lifetime. Both bulk updates now run with `flushAutomatically` only,
and `AuthService.resetPassword` burns the token before anything else touches the context.
`SecurityGuaranteesIT.resetLinkIsSingleUse` is the regression test.

## 5. Events

`EventPublisher` is a port. Phase 1 implements it with Spring's application events and
`@TransactionalEventListener(AFTER_COMMIT)`; Phase 5 swaps in SQS. Publishing code is
identical either way — that is the point of the port.

Two properties are established now because consumers will depend on them:

- **After commit.** A rolled-back registration must not send a welcome email.
- **Idempotency key.** Every event carries an `eventId`. SQS guarantees at-least-once
  delivery, so consumers must record the id before acting; without it a redelivered
  payment event bills a client twice (PRD §41.3).

The DLQ policy is already in the LocalStack bootstrap: `maxReceiveCount` 3, then park it.
A message that has failed three times is failing for a reason a fourth attempt will not
fix, and one poison message must not block the queue behind it.

## 5a. Phase 3: case management

A fourth module, `juriscore-case-management`, owning the `case_management` schema: courts,
hearings, tasks, deadlines and reminders. One dependency edge, to `juriscore-casework`,
because everything here hangs off a matter.

**One timeline, not two.** A hearing being adjourned and a task being completed are things
that happened on a *matter*, so they are written to the case timeline casework already
owns, through `CaseTimelineService`. V3 widens the `ck_case_events_type` check constraint
rather than creating a second history table. A matter with two histories is worse than an
enum that grows.

**The module boundary holds.** `CaseTimelineRecorder` is the only class that touches
casework, and it uses `CaseAccess` and `CaseTimelineService` — never a repository.
`IdentityFirmMemberDirectory` is the only class that touches identity, and it uses
`UserService`. Everything else references other schemas by plain UUID.

**Optimistic locking reaches the client.** Every Phase 3 update endpoint requires the
`version` the caller last read, and answers 409 `CONCURRENT_MODIFICATION` on a mismatch.
This is not a second locking mechanism — it is `BaseEntity.@Version`, made reachable over
HTTP. Two people editing one hearing in two browser tabs are in separate transactions, so
JPA alone never sees the conflict and the second save silently wins.

**Retirement, not deletion.** Courts deactivate; tasks and deadlines soft-delete. The same
reasoning as a soft-deleted client: a hearing held in 2019 has to keep naming the bench
that heard it, and a timeline entry has to keep resolving to the task it describes. A
court with hearings still listed ahead of it refuses to retire, because those listings
would then point at something the firm has said it no longer uses.

### Reminders, and what "sent" means

`@EnableScheduling` arrives here — this is the work Phase 1 said it was waiting for.
`ReminderScheduler` contributes a clock and nothing else; `ReminderDispatchService` does
the work and is directly callable, which is how the integration tests drive it without
waiting on a timer. The sweep is off in the test profile for the same reason.

**Claiming is `FOR UPDATE SKIP LOCKED`.** Every instance behind the load balancer runs the
same sweep on the same schedule, so "select the due rows, then update them" has all of
them selecting the same rows and publishing the same reminder several times. The row lock
makes the batches disjoint by construction: each instance locks what it takes and steps
over what another has already locked. The lock is released by the same commit that writes
`SENT`, so there is no window in which a row is claimed but still looks due. The
alternative — a Redis lock, or a scheduler library with its own tables — is a dependency
to operate and no more correct than a lock PostgreSQL is already keeping.

**`SENT` means published, not delivered.** There is no email, SMS or push anywhere in this
platform. A due reminder is marked `SENT` and published as `reminder.triggered`; the
consumer that turns that into a message belongs to Phase 5. The reminder's `channel` is
recorded intent for that consumer to read. This is said on the enum, on the column, on the
response schema and in the API description, because a status called SENT is exactly the
sort of thing a later reader takes at face value.

## 6. What Phase 1 deliberately leaves out

- **API gateway.** One deployable needs no gateway. The ALB terminates TLS and routes; a
  gateway is added with the second service, when it has something to route between.
- **Redis caching of domain data.** Redis is in the stack and used for rate limiting.
  Caching case summaries before there are cases would be cache design against imaginary
  access patterns.

  The limiter counts and expires in a single Lua script rather than `INCR` then `EXPIRE`.
  Two round-trips leave a window where the process can die — a rolling deploy, a failed
  health check, an OOM kill — after the counter exists but before it has a TTL. The key is
  then immortal, and that user or office IP is locked out of sign-in permanently with no
  way to recover. The script also re-arms the expiry on any key that somehow lacks one, so
  counters stranded by an earlier crash heal on the next request instead of needing a
  manual `DEL`.
- **Elasticsearch.** PostgreSQL full-text search covers Phase 2–3 volumes comfortably. The
  PRD agrees this is a later concern.
- **Kafka.** SQS covers the delivery semantics the platform needs. Kafka earns its
  operational cost when event replay or stream processing does.

## 7. Known gaps

Honest list, for the next session. Everything here is a deliberate Phase 1 boundary, not
an oversight:

- **No email delivery.** Invitation and reset tokens are published on domain events and go
  nowhere else. Integration tests read them off the event bus. Until Phase 5 wires SES,
  the invitation flow cannot complete for a real user.
- **`TenantGuard` is not on any request path.** Built and unit-tested; nothing calls it
  until Phase 2 introduces the first `TenantAwareEntity`. See §2.
- **No scheduled cleanup** of expired refresh and reset tokens. The repository methods
  (`deleteExpiredBefore`) exist and are indexed for it; no `@Scheduled` job calls them, so
  both tables still grow without bound. `@EnableScheduling` is now on — Phase 3's reminder
  sweep needed it — so this gap is one job away from closed, and closing it is no longer
  blocked on anything.
- **No PostgreSQL row-level security.**
- **`SUPER_ADMIN` has no bootstrap path.** The role exists throughout the model and the
  first one has to be inserted by hand; `/organizations/{id}` is unreachable until then.
- **Rate limiting uses a fixed window**, which admits up to 2× the limit across a
  boundary. Acceptable for abuse protection, not for quota enforcement.
- **Search is `LIKE '%term%'`** on name and email. The leading wildcard means no index can
  serve it; the `organization_id` predicate bounds the scan to one firm, which is fine at
  Phase 2–3 volumes. The PRD's Elasticsearch is the eventual answer.
- **The image build compiles the application a second time.** CI builds and tests with
  Maven, then the Docker build compiles again from source rather than consuming the jar
  the previous job produced. Correct, just slower; worth collapsing when CI time starts to
  matter.
