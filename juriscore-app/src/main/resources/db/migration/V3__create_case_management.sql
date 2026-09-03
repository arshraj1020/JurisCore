-- =============================================================================
-- JurisCore V3 — case management: courts, hearings, tasks, deadlines, reminders
--
-- Same rules as V1 and V2. One schema per module, no foreign keys leaving it.
-- case_id and court_id are plain UUID columns because cases live in `casework`
-- and this module reaches them through CaseAccess, not through a join;
-- assigned_to_user_id and actor ids point into `identity` and are validated
-- through that module's service API for the same reason.
--
-- The one exception, and it is deliberate: this migration widens the CHECK
-- constraint on casework.case_events so hearings, tasks and deadlines can write
-- to the case timeline that Phase 2 already owns. Building a second timeline
-- would have been the alternative, and a matter with two histories is worse than
-- a constraint that grows.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS case_management;

-- ---------------------------------------------------------------------- courts
-- Reference data owned by the firm: the benches it actually appears before.
CREATE TABLE case_management.courts
(
    id              UUID         PRIMARY KEY,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    organization_id UUID         NOT NULL,
    name            VARCHAR(200) NOT NULL,
    court_type      VARCHAR(32)  NOT NULL,
    address_line1   VARCHAR(255),
    address_line2   VARCHAR(255),
    city            VARCHAR(120),
    state           VARCHAR(120),
    country         VARCHAR(120),
    timezone        VARCHAR(64),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT ck_courts_type CHECK (court_type IN
        ('SUPREME', 'HIGH', 'DISTRICT', 'TRIBUNAL', 'OTHER'))
);

-- Retiring a court deactivates it rather than deleting it, exactly as retiring a
-- client does in V2: hearings held before it have to keep resolving to a name.
-- The partial unique index follows from that — a name is taken only while the
-- court is in use, so a bench that closes frees its name for a successor.
CREATE UNIQUE INDEX uk_courts_name_lower
    ON case_management.courts (organization_id, lower(name)) WHERE active;
CREATE INDEX idx_courts_organization_active
    ON case_management.courts (organization_id) WHERE active;

COMMENT ON COLUMN case_management.courts.active IS
    'False means retired: hidden from lists and unselectable for new hearings; existing hearings keep resolving.';

-- -------------------------------------------------------------------- hearings
CREATE TABLE case_management.hearings
(
    id               UUID         PRIMARY KEY,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    created_by       UUID,
    updated_by       UUID,

    organization_id  UUID         NOT NULL,
    case_id          UUID         NOT NULL,
    court_id         UUID         NOT NULL,
    hearing_type     VARCHAR(32)  NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    scheduled_at     TIMESTAMPTZ  NOT NULL,
    duration_minutes INTEGER,
    judge_name       VARCHAR(200),
    courtroom        VARCHAR(120),
    purpose          VARCHAR(1000),
    outcome          VARCHAR(4000),

    CONSTRAINT fk_hearings_court FOREIGN KEY (court_id)
        REFERENCES case_management.courts (id),
    CONSTRAINT ck_hearings_type CHECK (hearing_type IN
        ('MENTION', 'EVIDENCE', 'ARGUMENTS', 'JUDGMENT', 'OTHER')),
    CONSTRAINT ck_hearings_status CHECK (status IN
        ('SCHEDULED', 'COMPLETED', 'ADJOURNED', 'CANCELLED')),
    CONSTRAINT ck_hearings_duration CHECK (duration_minutes IS NULL
        OR (duration_minutes > 0 AND duration_minutes <= 1440))
);

CREATE INDEX idx_hearings_organization_case ON case_management.hearings (organization_id, case_id);
CREATE INDEX idx_hearings_organization_court ON case_management.hearings (organization_id, court_id);
CREATE INDEX idx_hearings_organization_status ON case_management.hearings (organization_id, status);
-- The cause list: "what is listed this week", which is the query a firm runs daily.
CREATE INDEX idx_hearings_organization_schedule
    ON case_management.hearings (organization_id, scheduled_at);

-- ----------------------------------------------------------------------- tasks
CREATE TABLE case_management.tasks
(
    id                  UUID         PRIMARY KEY,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    created_by          UUID,
    updated_by          UUID,

    organization_id     UUID         NOT NULL,
    case_id             UUID         NOT NULL,
    title               VARCHAR(300) NOT NULL,
    description         VARCHAR(4000),
    status              VARCHAR(32)  NOT NULL,
    priority            VARCHAR(32)  NOT NULL,
    assigned_to_user_id UUID,
    due_at              TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    deleted_at          TIMESTAMPTZ,

    CONSTRAINT ck_tasks_status CHECK (status IN ('TODO', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_tasks_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    -- completed_at and COMPLETED are one fact written twice; the database refuses
    -- to let them disagree, as it does for cases in V2.
    CONSTRAINT ck_tasks_completed_at CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE INDEX idx_tasks_organization_case ON case_management.tasks (organization_id, case_id)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_organization_assignee
    ON case_management.tasks (organization_id, assigned_to_user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_organization_due ON case_management.tasks (organization_id, due_at)
    WHERE deleted_at IS NULL;

-- ------------------------------------------------------------------- deadlines
-- A first-class date the matter has to meet, not a task with a due date. The two
-- differ in what they are for: a task is work somebody does, a deadline is an
-- obligation that exists whether or not anybody is working on it.
CREATE TABLE case_management.deadlines
(
    id              UUID         PRIMARY KEY,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    organization_id UUID         NOT NULL,
    case_id         UUID         NOT NULL,
    title           VARCHAR(300) NOT NULL,
    description     VARCHAR(4000),
    deadline_type   VARCHAR(32)  NOT NULL,
    due_at          TIMESTAMPTZ  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    completed_at    TIMESTAMPTZ,
    source          VARCHAR(255),
    deleted_at      TIMESTAMPTZ,

    -- Deliberately generic. No PRD taxonomy of legal deadline types is in the
    -- repository, and inventing one would encode rules nobody has stated; these
    -- three say who imposed the date, which is the distinction that survives any
    -- jurisdiction. Widening the list later is a one-line migration.
    CONSTRAINT ck_deadlines_type CHECK (deadline_type IN ('COURT', 'INTERNAL', 'OTHER')),
    CONSTRAINT ck_deadlines_status CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_deadlines_completed_at CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE INDEX idx_deadlines_organization_case ON case_management.deadlines (organization_id, case_id)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_deadlines_organization_due ON case_management.deadlines (organization_id, due_at)
    WHERE deleted_at IS NULL;

-- ------------------------------------------------------------------- reminders
-- Phase 3 schedules reminders and hands due ones to the existing event bus. It
-- does not deliver anything: there is no email, SMS or push in this system yet.
-- SENT therefore means "published as reminder.triggered", and the column comment
-- says so, because a status called SENT that has never sent anything is exactly
-- the sort of thing a later reader believes.
CREATE TABLE case_management.reminders
(
    id              UUID         PRIMARY KEY,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    organization_id UUID         NOT NULL,
    task_id         UUID,
    deadline_id     UUID,
    remind_at       TIMESTAMPTZ  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    channel         VARCHAR(32)  NOT NULL,
    note            VARCHAR(500),
    triggered_at    TIMESTAMPTZ,

    CONSTRAINT fk_reminders_task FOREIGN KEY (task_id)
        REFERENCES case_management.tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_reminders_deadline FOREIGN KEY (deadline_id)
        REFERENCES case_management.deadlines (id) ON DELETE CASCADE,
    -- Exactly one target. A reminder attached to both, or to neither, is not a
    -- reminder anybody can act on, and this is cheaper than trusting five call
    -- sites to agree.
    CONSTRAINT ck_reminders_one_target CHECK (
        (task_id IS NOT NULL)::int + (deadline_id IS NOT NULL)::int = 1),
    CONSTRAINT ck_reminders_status CHECK (status IN ('SCHEDULED', 'SENT', 'CANCELLED')),
    CONSTRAINT ck_reminders_channel CHECK (channel IN ('IN_APP', 'EMAIL')),
    CONSTRAINT ck_reminders_triggered_at CHECK ((status = 'SENT') = (triggered_at IS NOT NULL))
);

-- The claim query orders by remind_at over exactly this predicate, so the index
-- is partial on it: the scheduler should never pay to skip reminders it has
-- already handled, and that table only grows.
CREATE INDEX idx_reminders_due ON case_management.reminders (remind_at)
    WHERE status = 'SCHEDULED';
CREATE INDEX idx_reminders_organization ON case_management.reminders (organization_id, remind_at);
CREATE INDEX idx_reminders_task ON case_management.reminders (task_id);
CREATE INDEX idx_reminders_deadline ON case_management.reminders (deadline_id);

COMMENT ON COLUMN case_management.reminders.status IS
    'SENT means the reminder was published as a reminder.triggered domain event. Nothing in Phase 3 delivers email, SMS or push.';

-- ------------------------------------------- widening the Phase 2 case timeline
-- Phase 3 writes to the timeline casework already owns rather than starting a
-- second one, so the check constraint has to admit the new entry kinds. The
-- table, its append-only guarantee and its ordering are unchanged.
ALTER TABLE casework.case_events DROP CONSTRAINT ck_case_events_type;
ALTER TABLE casework.case_events ADD CONSTRAINT ck_case_events_type CHECK (event_type IN
    ('CASE_CREATED', 'LAWYER_ASSIGNED', 'LAWYER_UNASSIGNED', 'CASE_STATUS_CHANGED', 'MANUAL_NOTE',
     'HEARING_SCHEDULED', 'HEARING_COMPLETED', 'HEARING_ADJOURNED', 'HEARING_CANCELLED',
     'TASK_CREATED', 'TASK_COMPLETED', 'TASK_CANCELLED',
     'DEADLINE_CREATED', 'DEADLINE_COMPLETED', 'DEADLINE_CANCELLED'));
