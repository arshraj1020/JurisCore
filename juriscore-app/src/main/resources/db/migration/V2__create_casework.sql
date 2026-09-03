-- =============================================================================
-- JurisCore V2 — casework: clients, cases, lawyer assignments, case timeline
--
-- Same rules as V1. One schema per module, and deliberately no foreign keys that
-- leave it: client_id and case_id are real FKs because both live in `casework`,
-- while lawyer_user_id and actor_user_id reference identity.users and are plain
-- UUID columns. Casework validates those through identity's service API instead,
-- so pulling this module out into its own service stays a packaging change.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS casework;

-- --------------------------------------------------------------------- clients
-- The firm's client of record. Distinct from identity.users: a client is a party
-- to a matter, not necessarily somebody who signs in.
CREATE TABLE casework.clients
(
    id              UUID         PRIMARY KEY,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    organization_id UUID         NOT NULL,
    display_name    VARCHAR(200) NOT NULL,
    client_type     VARCHAR(32)  NOT NULL,
    email           VARCHAR(255),
    phone           VARCHAR(40),
    address_line1   VARCHAR(255),
    address_line2   VARCHAR(255),
    city            VARCHAR(120),
    state           VARCHAR(120),
    country         VARCHAR(120),
    postal_code     VARCHAR(20),
    notes           VARCHAR(2000),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT ck_clients_type CHECK (client_type IN ('INDIVIDUAL', 'CORPORATE'))
);

-- Deletion is soft, so that a case opened three years ago still resolves to the
-- client it was opened for. Every "live" query therefore carries `deleted_at IS
-- NULL`, and this partial index is what keeps that cheap.
CREATE INDEX idx_clients_organization_active ON casework.clients (organization_id)
    WHERE deleted_at IS NULL;

-- Client email is unique per firm, not globally: two firms may both act for the
-- same person, and that is not a conflict. Functional and partial for the reasons
-- V1 sets out for identity.users — the application matches addresses
-- case-insensitively, so a plain UNIQUE would accept both spellings, and a
-- soft-deleted row must not block re-adding the same client later.
CREATE UNIQUE INDEX uk_clients_email_lower ON casework.clients (organization_id, lower(email))
    WHERE email IS NOT NULL AND deleted_at IS NULL;

COMMENT ON COLUMN casework.clients.deleted_at IS
    'Soft deletion. Set means hidden from lists and unselectable for new cases; existing cases keep resolving.';

-- ------------------------------------------------------- case number sequences
-- Per-firm, per-year counter behind CASE-2026-000001.
--
-- Deliberately not a domain entity and deliberately without version/audit columns:
-- it is a counter, and an optimistic-lock column on a row every concurrent case
-- creation contends for would turn a normal race into a 409. Correctness comes
-- from SELECT ... FOR UPDATE around the increment (see CaseNumberGenerator), with
-- uk_cases_number below as the final arbiter — no application-side check-then-insert.
CREATE TABLE casework.case_number_sequences
(
    organization_id UUID    NOT NULL,
    year            INTEGER NOT NULL,
    next_value      BIGINT  NOT NULL,

    CONSTRAINT pk_case_number_sequences PRIMARY KEY (organization_id, year),
    CONSTRAINT ck_case_number_sequences_value CHECK (next_value >= 0)
);

-- ----------------------------------------------------------------------- cases
CREATE TABLE casework.cases
(
    id              UUID         PRIMARY KEY,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    organization_id UUID         NOT NULL,
    case_number     VARCHAR(32)  NOT NULL,
    title           VARCHAR(300) NOT NULL,
    description     VARCHAR(4000),
    client_id       UUID         NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    opened_at       TIMESTAMPTZ  NOT NULL,
    closed_at       TIMESTAMPTZ,

    CONSTRAINT fk_cases_client FOREIGN KEY (client_id) REFERENCES casework.clients (id),
    CONSTRAINT ck_cases_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'ON_HOLD', 'CLOSED')),
    -- closed_at and CLOSED are the same fact stated twice; the database refuses to
    -- let them disagree, so a bug in the service cannot leave a closed case with no
    -- closing date or an open one that has one.
    CONSTRAINT ck_cases_closed_at CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

CREATE UNIQUE INDEX uk_cases_number ON casework.cases (organization_id, case_number);
CREATE INDEX idx_cases_organization_status ON casework.cases (organization_id, status);
CREATE INDEX idx_cases_organization_client ON casework.cases (organization_id, client_id);

-- ------------------------------------------------------------ case assignments
CREATE TABLE casework.case_assignments
(
    id              UUID        PRIMARY KEY,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    organization_id UUID        NOT NULL,
    case_id         UUID        NOT NULL,
    lawyer_user_id  UUID        NOT NULL,
    is_lead         BOOLEAN     NOT NULL DEFAULT FALSE,
    assigned_at     TIMESTAMPTZ NOT NULL,
    assigned_by     UUID,

    CONSTRAINT fk_case_assignments_case FOREIGN KEY (case_id)
        REFERENCES casework.cases (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_case_assignments_lawyer
    ON casework.case_assignments (case_id, lawyer_user_id);

-- "At most one lead" is a database guarantee, not a service convention: a partial
-- unique index means even a concurrent double-promotion fails rather than leaving
-- a case with two leads. "At least one" is enforced in the service, which refuses
-- to unassign the lead unless another assignee is promoted in the same transaction.
CREATE UNIQUE INDEX uk_case_assignments_lead
    ON casework.case_assignments (case_id) WHERE is_lead;

CREATE INDEX idx_case_assignments_lawyer
    ON casework.case_assignments (organization_id, lawyer_user_id);

-- ---------------------------------------------------------------- case timeline
-- Append-only. Nothing in the module exposes an update or a delete for these rows;
-- an amended history is not a history.
CREATE TABLE casework.case_events
(
    id              UUID          PRIMARY KEY,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    organization_id UUID          NOT NULL,
    case_id         UUID          NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    actor_user_id   UUID,
    occurred_at     TIMESTAMPTZ   NOT NULL,
    summary         VARCHAR(1000) NOT NULL,

    CONSTRAINT fk_case_events_case FOREIGN KEY (case_id)
        REFERENCES casework.cases (id) ON DELETE CASCADE,
    CONSTRAINT ck_case_events_type CHECK (event_type IN
        ('CASE_CREATED', 'LAWYER_ASSIGNED', 'LAWYER_UNASSIGNED', 'CASE_STATUS_CHANGED', 'MANUAL_NOTE'))
);

-- The id tiebreak is not decoration: two entries written in the same transaction
-- can share occurred_at to the microsecond, and without a second sort key the page
-- boundary between them is undefined and a row can be shown twice or skipped.
CREATE INDEX idx_case_events_case_time
    ON casework.case_events (case_id, occurred_at DESC, id DESC);
