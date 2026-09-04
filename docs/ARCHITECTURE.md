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

## 5b. Phase 4: documents and object storage

A fifth module, `juriscore-documents`, owning the `documents` schema. One dependency edge,
to `juriscore-casework`, because a document is a document *on a matter*.

**No bytes in PostgreSQL, and none through the application.** A `bytea` column holding
filings would put the whole corpus into every backup, replica and dump; proxying uploads
through the JVM would put every file through the heap and make the app the bottleneck in
front of something that scales perfectly well without it. So the flow is presigned: the
platform authorizes, issues a signed URL, and the browser talks to S3 directly.

### The seam

`ObjectStorageService` is a port in `juriscore-common`, alongside `EventPublisher` and for
the same reason. It lives there rather than in the documents module because the SDK clients
and their credentials are configured in `juriscore-app`, and a module cannot depend on the
application that assembles it. Two adapters sit beside `AwsConfig`:

- `S3ObjectStorageService` — the real one, active when `juriscore.aws.enabled=true`.
- `InMemoryObjectStorageService` — active when it is false. Stores no bytes, returns inert
  URLs, reports `isDurable() == false` and warns loudly at startup. It exists so the suite
  and a fresh checkout run with no AWS credentials and no network, while the document rules
  themselves are exercised for real.

Nothing in `juriscore-documents` imports an AWS type. That is what makes the whole rule set
unit-testable without a cloud account.

### Upload, in two halves

The application never sees the file, so it learns about an upload twice:

1. **Register.** Validate the case, the filename, the content type and the size; write an
   `UPLOADING` row; derive the object key; issue a signed PUT. Nothing is downloadable yet.
2. **Complete.** Read the object back from storage. If it is not there, the document is not
   complete — the client's word is not evidence. If it is, its **actual** size replaces the
   declared one and the document becomes `AVAILABLE`.

Completing twice is a no-op: no second transition, no second event, no second timeline
entry. That matters because a retry, a double-click and a client replaying after a timeout
all look the same from here.

### What a presigned PUT actually enforces

Worth stating precisely, because the guarantee is narrower than it looks. The signature
covers the **object key** and the **HTTP method**, so a link cannot be redirected to another
key or reused to GET or DELETE — that is what keeps one firm's upload URL from ever writing
into another firm's prefix. `Content-Type` is signed too. **Size is not.** A client that
ignores `Content-Length` can push a larger object. That gap is closed at completion, where
the real size is read back and anything over the maximum moves the document to `FAILED`; in
a deployed environment a bucket policy is the belt to that braces.

### Object keys

`organizations/{organizationId}/cases/{caseId}/documents/{documentId}` — every segment a
UUID the platform generated. No filename, no description, no header value; nothing a user
typed. Traversal and cross-tenant collision are not filtered out, they are unrepresentable,
because a UUID cannot contain a slash. The tenant prefix leads so a future least-privilege
IAM split is a policy document rather than a migration. The key is never returned by the
API.

The last segment is the generated id, which is why registration writes twice inside one
transaction: the row is inserted with a unique reservation value and stamped with its real
key immediately after. Neither shortcut works. An id assigned before the insert is rejected
outright — `@GeneratedValue` treats a non-null id as a detached entity — and a key set on
the entity between `persist` and `flush` is not folded into the INSERT. The column is
therefore *not* mapped `updatable = false`: Hibernate excludes a non-updatable column from
dirty checking entirely, so that mapping silently discarded the stamping statement and
every row kept its reservation. What actually keeps a caller from influencing the key is
that `UpdateDocumentRequest` has no field for it, `update` never writes it, and
`uk_case_documents_storage_key` would reject a collision. `StorageKeys.requireCanonical`
guards the one path that matters: no link is ever signed for anything but a stamped key.

The in-memory adapter issues an **opaque handle**, resolved internally, rather than the key
spelt into a URL. A link is the one artefact a client is handed, so a stand-in that pasted
the key into it would publish through the link exactly what `DocumentResponse` withholds.

### `FAILED` has to outlive the error that reports it

Rejecting an oversized or empty object ends in an error response, and `ApiException` is a
`RuntimeException` — so the throw marks the transaction rollback-only and discards the
`FAILED` transition that was the entire point of the check. `DocumentFailureRecorder`
commits it in a `REQUIRES_NEW` transaction first, exactly as `LoginAttemptRecorder` does for
the sign-in counter it sits behind. This is the same trap twice in one codebase, and both
times it was invisible to a unit test: a mocked repository has no transaction to roll back,
so the entity in hand says `FAILED` while the row never left `UPLOADING`.

### Deletion is not atomic, and does not pretend to be

PostgreSQL and S3 share no transaction, so one order has to be chosen and its failure mode
accepted. Metadata commits first; the object is removed afterwards by
`DeletedDocumentObjectCleaner`, an `AFTER_COMMIT` listener. The failure that leaves is an
orphaned object costing storage — cleaned up by a bucket lifecycle rule. The alternative
order would leave active metadata pointing at a file that is gone, which is the failure a
user would actually experience. A storage failure during cleanup is logged and swallowed:
the user's delete already succeeded.

### File validation, and its limits

Server-side, before any link is issued: filename required, no path separator, no `..`, no
control characters, length bounded; content type on an **allowlist**; size positive and
under the maximum.

**There is no malware scanning and no content inspection.** The platform has no scanning
infrastructure and Phase 4 adds none, so a file declared `application/pdf` that is really
something else will be accepted as one. Saying so is better than a check that looks like one
and is not. What is real: the allowlist, the size ceiling, that nothing is ever served from
a public URL, and that downloads go out as `Content-Disposition: attachment` rather than
being rendered.

### Presigned URLs are credentials

Bounded expiry (15 minutes up, 5 down), issued only after authorization, for one key and
one method. They are never logged, never stored, and never carried on a domain event —
`document.download_requested` records *who* asked and for *what*, not the link they got.

## 5c. Phase 5: billing, notifications and audit

Three more modules on three more schemas, following the rule the first six set: one module,
one schema, no foreign key across a boundary, and anything pointing at another module's row
is a plain UUID validated through that module's service API.

- **`juriscore-billing`** (`billing`) — one edge, to casework, because an invoice is a bill
  to a *client* usually for work on a *matter*. It reaches both through `ClientService` and
  `CaseAccess`, never through casework's repositories.
- **`juriscore-notifications`** (`notifications`) — depends on `juriscore-common` alone. It
  knows how to deliver a message to a user id and nothing about what a user or an invoice
  is.
- **`juriscore-audit`** (`audit`) — likewise depends on common alone.

The two obvious objections to that last pair are worth answering. Something has to know
which user ids should hear about an invoice, and something has to know what every module's
events mean. Both of those live in `juriscore-app` — `FirmStaffDirectory`,
`BillingNotificationListener`, `DomainEventAuditListener` — which is the module that already
has every module in view because it is the one that assembles them. The alternative for
audit was an `Auditable` interface in `juriscore-common` that every Phase 1–4 event
implements; that would mean editing more than twenty working event classes to install a
Phase 5 concern into them, and scattering the decision about what gets audited across four
modules. Here the whole policy is one switch that reads top to bottom.

### Money

`BigDecimal` in Java, `NUMERIC` in PostgreSQL, and `double` nowhere. The rounding rule is in
one file, `Money`, and applies **twice per line and nowhere else**:

1. `amount = round(quantity × unitPrice)` — quantity carries three decimals, so the product
   genuinely needs rounding.
2. `taxAmount = round(amount × taxRate ÷ 100)` — computed from the *already rounded* line
   amount, so what a reader can verify with a calculator from the printed figures is what
   the system stored.

Scale 2, `HALF_UP`. `HALF_EVEN` is better over long statistical runs and worse here: a firm
explaining why 2.125 became 2.12 on one line and 2.14 on another is a conversation that
should not have to happen.

Everything above a line is a sum of values already at scale 2, and adding exact two-decimal
figures needs no rounding — so `total = subtotal + tax − discount` holds as an *identity*
rather than approximately. That is what lets `ck_invoices_total` assert it in the database
rather than trust the application. One consequence worth stating: tax is computed per line
and then summed, not computed once on the subtotal. On a 200-line invoice those differ by
₹0.36, and the per-line figure is the one printed beside each line.

No request DTO has a `subtotal`, `taxAmount` or `totalAmount` field. A client cannot
over-declare a total, not because the server checks their figure but because it never asks
for one.

### Invoice numbering

`INV-2026-000001`, per firm, per year — the same three statements `CaseNumberGenerator`
uses, deliberately, because the problem is identical and a second cleverer solution to it
would be a second thing to be wrong. `INSERT … ON CONFLICT DO NOTHING`, then
`SELECT … FOR UPDATE`, then `UPDATE`. `uk_invoices_number` is the final arbiter. A
rolled-back creation releases the counter with it, so a firm's numbering has no gap to
explain.

### Invoice lifecycle

```
DRAFT          -> ISSUED, CANCELLED
ISSUED         -> PARTIALLY_PAID, PAID, OVERDUE, CANCELLED
PARTIALLY_PAID -> PAID, OVERDUE, CANCELLED
OVERDUE        -> PARTIALLY_PAID, PAID, CANCELLED
PAID, CANCELLED -> (terminal)
```

Issuing is a one-way door. A DRAFT is a working document; the moment it is issued it has
been sent to somebody, and its figures, its client and its matter are frozen. An edit
touching any of them afterwards is a **409, not a silent no-op** — a bookkeeper who thinks
they corrected an issued invoice must not be left believing it. Only the notes stay
editable.

`PAID` and `CANCELLED` have no outgoing edges. Unwinding either is a credit note, and Phase
5 does not have one; quietly allowing `PAID -> ISSUED` would let a PATCH rewrite settled
history, which is precisely what an audit trail exists to make impossible.

### Why payments take a row lock

Two bookkeepers recording payments on the same invoice at the same moment is not a rare
case, and the naive version gets it wrong in a way that costs money: both read "nothing paid
yet", both find their 6,000 acceptable against a 10,000 invoice, both insert, and the
invoice is overpaid — with the second request reporting success.

Optimistic locking does not fix it. The version column would object only because both
transactions happen to write the invoice row, and the bookkeeper whose perfectly valid
payment lost the race gets a 409 telling them to reload. So `PaymentService` takes the
invoice under `PESSIMISTIC_WRITE` *before* reading the balance. The second caller waits for
the first to commit and then reads the truth: an overpayment is refused because it is an
overpayment, and a valid concurrent payment succeeds because it is valid. Only ever one row
is locked, and always the one being paid, so there is no deadlock to arrange.

A payment row is immutable — every column `updatable = false`, and no service exposes an
edit. Correcting one means recording the correction, not rewriting the claim.

### Overdue processing

`OVERDUE` is the one invoice state nobody causes: every other transition has a request
behind it, this one is caused by time passing. Deriving it on read was rejected — a derived
status cannot be the subject of an event, so nobody could be told, and it would leave the
stored status disagreeing with the API's.

So there is a sweep, reusing Phase 3's scheduling architecture rather than adding one.
`OverdueInvoiceClaimer` uses `FOR UPDATE SKIP LOCKED`, exactly as `ReminderClaimer` does, so
several application instances take disjoint batches by construction. **Idempotence comes
from the predicate, not from a flag:** the claim matches only `ISSUED` and `PARTIALLY_PAID`,
so a rerun finds those invoices already `OVERDUE` and publishes nothing. No "last swept at"
column is needed and none exists.

An invoice due *today* is not late today — a firm that gives a client until the 30th means
the whole of the 30th.

### Notifications

In-app only. Nothing sends an email, an SMS, a WhatsApp message or a push, and the table has
no column that would carry one: no delivery status, no provider message id, no retry count.
A status called `SENT` on a row nothing has ever sent is exactly the sort of thing a later
reader believes.

Mapping is explicit and short — five events out of the twenty-odd the platform publishes.
A notification for every event is a feed nobody reads, which is worse than no feed.

Three things suppress a notification: the recipient turned the category off; the same
`dedupe_key` already exists for that recipient; or there is no recipient. The dedupe key is
derived from the *business fact* (`invoice.overdue:{id}`), not from the delivery, so a
repeated sweep, a retried publish or a second application instance all collide on
`uk_notifications_dedupe`. The application-side check catches the ordinary case cheaply and
the unique index catches the race, because two instances handling the same event will both
pass the check.

`action_path` is a relative in-app path and the database enforces that it starts with `/`.
Never an absolute URL and never a signed one: a presigned link is a bearer credential, and a
row that sits in somebody's inbox indefinitely is the last place to keep one.

Preferences belong to the **user**, not the firm. There is no endpoint taking another user's
id, so "mute a colleague" is not a request that can be expressed rather than one that is
refused. A user with no row has everything enabled.

### Audit, and what append-only actually means here

Enforced at four levels, because a convention is not an enforcement:

1. **Shape.** `AuditEvent` does not extend `BaseEntity` — no `version`, no `updated_at`, no
   `updated_by`. A table with an optimistic-lock column is a table somebody expects to
   rewrite; the absence is the statement.
2. **Mapping.** Every column is `updatable = false`.
3. **Repository.** `AuditEventRepository` extends Spring Data's bare `Repository` marker
   plus the read-only `JpaSpecificationExecutor`, and declares exactly one write. Extending
   `JpaRepository` would have inherited six ways to mutate the trail, sitting on the
   interface for anyone reaching for autocomplete.
4. **API.** `AuditController` maps GET and nothing else. There is no PUT, PATCH or DELETE to
   authorize.

`organization_id` and `actor_user_id` are both nullable, for the two cases that genuinely
have neither: a failed sign-in against an address matching no account, and the scheduled
sweeps that act with no signed-in user. The actor is read from `CurrentUser` rather than
carried on every event — an `AFTER_COMMIT` listener runs synchronously on the request
thread, so the security context is intact. **Moving that listener to `@Async` would silently
drop the context and attribute every row to nobody**, which is a failure that looks like
working software. It replaces `EventLogListener`, the Phase 1 placeholder that logged every
event and was `@Async` precisely because it needed no context.

`AuditTrail` writes in a `REQUIRES_NEW` transaction and swallows failures at ERROR. That is
a real trade: a lost audit row is a genuine loss, and it is the right call for an in-process
trail, because an invoice that was legitimately issued must not be reported as failed
because the record of it could not be written. A system that must not lose a single audit row
writes it inside the business transaction and pays for that with an audit table that can
roll back a payment.

`AuditRedaction` refuses any summary that looks like it carries a credential, a signed URL
or a card-shaped number. It **fails loudly rather than scrubbing**: a tripped rule is a bug
in whatever built the summary, and replacing the offending substring would hide that bug
while still recording a row nobody can trust. Producers already keep secrets off their
events — `PaymentRecordedEvent` carries no reference, and the two identity events that do
carry tokens are audited without them — so this is a belt to those braces.

Reading the trail is `FIRM_ADMIN` only. It records who did what, which makes it a record
about a firm's own staff: a clerk should not be able to page through what a partner has been
doing. `SUPER_ADMIN` gets nothing either — it has no organization, so
`requireOrganizationId()` refuses it before any handler body runs. Operating the platform is
a different problem from investigating a tenant, and the second deserves a better answer
than "the support engineer had a token".

### What Phase 5 is not

No payment gateway or processing of any kind; no card, bank or gateway credential stored
anywhere. No GST engine — a rate and an amount per line, and no claim of statutory
compliance. No accounting export. No email, SMS, WhatsApp or push. No SQS or Kafka: the bus
is still in-process, and the `notification-queue`/`audit-queue` settings remain unused
placeholders. No client billing portal. No credit notes or refunds. No currency conversion.
No analytics and no Redis caching.

Two audit gaps are worth naming rather than papering over: **sign-in success and failure,
logout and session revocation are not audited**, because Phase 1 publishes no events for
them — `AuthService` records failures directly on the user row through
`LoginAttemptRecorder`. Adding events to working authentication code for a Phase 5 concern
was left for a follow-up rather than faked here.

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
  nowhere else. Integration tests read them off the event bus, and the invitation flow
  still cannot complete for a real user. Phase 5 did *not* close this: it added an in-app
  notification feed, which is a different thing, and deliberately added no SES, SMS or
  push client.
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
- **Sign-in, logout and session revocation are not audited.** Phase 5 built the trail and
  wired every module's events into it, but authentication publishes no events for these:
  `AuthService` records failures directly on the user row through `LoginAttemptRecorder`.
  Adding events to working authentication code for a Phase 5 concern was left for a
  follow-up rather than faked with a listener that had nothing to listen to. This is the
  largest remaining hole in the trail and should be the first thing Phase 6 closes.
- **The event bus is still in-process.** `SpringEventPublisher` and `AFTER_COMMIT`
  listeners, exactly as Phase 1 shipped. The `notification-queue` and `audit-queue`
  settings in `application.yml` are placeholders that nothing reads. Swapping in an SQS
  publisher remains a one-bean change, which was the point of the port — but it has not
  been made.
- **Notification delivery is in-app only.** No email, SMS, WhatsApp or push, and no
  scheduled digest. A notification exists only where somebody signs in and reads it.
- **No credit notes.** Correcting an issued invoice means cancelling it and raising
  another, which loses the link between the two. A firm reconciling its books has to make
  that connection by hand.
- **The image build compiles the application a second time.** CI builds and tests with
  Maven, then the Docker build compiles again from source rather than consuming the jar
  the previous job produced. Correct, just slower; worth collapsing when CI time starts to
  matter.
