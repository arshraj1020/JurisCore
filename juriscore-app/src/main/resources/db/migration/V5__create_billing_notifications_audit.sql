-- =============================================================================
-- JurisCore V5 — billing, notifications and the audit trail
--
-- Three schemas, three modules, the same rules V1–V4 follow: one schema per
-- module, no foreign key crosses a schema boundary, and anything pointing at
-- another module's row is a plain UUID validated through that module's service
-- API. So invoices.client_id and invoices.case_id carry no FK — casework owns
-- those rows and ClientService/CaseAccess are how they are checked — while
-- invoice_line_items.invoice_id and payments.invoice_id do carry one, because
-- both live in `billing` alongside the invoice they belong to.
--
-- Money is NUMERIC throughout. Never float, never double: 0.1 + 0.2 is not 0.3
-- in binary floating point, and an invoice total is not a place to discover
-- that. The scale is 2 for every currency amount, and the arithmetic that
-- produces those amounts is defined once in Money.java.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS billing;
CREATE SCHEMA IF NOT EXISTS notifications;
CREATE SCHEMA IF NOT EXISTS audit;

-- ======================================================= billing profiles
-- One per firm: the details that would otherwise be retyped onto every
-- invoice, plus the numbering prefix. Deliberately NOT a payment-credential
-- store. There is no card number, no bank password, no gateway secret and no
-- token column anywhere in this migration, because JurisCore takes no payments
-- — it records them. Adding a gateway later means adding a vault, not widening
-- this table.
CREATE TABLE billing.billing_profiles
(
    id                UUID         PRIMARY KEY,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    created_by        UUID,
    updated_by        UUID,

    organization_id   UUID         NOT NULL,
    legal_name        VARCHAR(200),
    -- Generic on purpose. India's GSTIN is 15 characters and is the case this
    -- product is being built for, but the column is a tax registration number
    -- and nothing in Phase 5 validates its checksum, derives a place of supply
    -- from it, or files anything with it. See the comment on invoices.tax_amount.
    tax_registration  VARCHAR(64),
    billing_email     VARCHAR(255),
    billing_phone     VARCHAR(40),
    address_line1     VARCHAR(255),
    address_line2     VARCHAR(255),
    city              VARCHAR(120),
    state             VARCHAR(120),
    country           VARCHAR(120),
    postal_code       VARCHAR(20),
    default_currency  VARCHAR(3)   NOT NULL DEFAULT 'INR',
    invoice_prefix    VARCHAR(12)  NOT NULL DEFAULT 'INV',
    invoice_notes     VARCHAR(2000),

    CONSTRAINT uk_billing_profiles_organization UNIQUE (organization_id),
    CONSTRAINT ck_billing_profiles_currency CHECK (default_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_billing_profiles_prefix CHECK (invoice_prefix ~ '^[A-Z][A-Z0-9-]{0,11}$')
);

COMMENT ON COLUMN billing.billing_profiles.tax_registration IS
    'Free-text tax registration number (GSTIN in India). Recorded and printed only. Phase 5 performs no statutory validation, place-of-supply derivation or filing.';

-- ================================================ invoice number sequences
-- Per-firm, per-year counter behind INV-2026-000001, and the exact shape
-- casework.case_number_sequences already uses. No version column and no audit
-- columns: it is a counter, and an optimistic-lock column on a row every
-- concurrent invoice creation contends for would turn a normal race into a 409.
-- Correctness comes from SELECT ... FOR UPDATE around the increment (see
-- InvoiceNumberGenerator); uk_invoices_number below is the final arbiter.
CREATE TABLE billing.invoice_number_sequences
(
    organization_id UUID    NOT NULL,
    year            INTEGER NOT NULL,
    next_value      BIGINT  NOT NULL,

    CONSTRAINT pk_invoice_number_sequences PRIMARY KEY (organization_id, year),
    CONSTRAINT ck_invoice_number_sequences_value CHECK (next_value >= 0)
);

-- ================================================================ invoices
CREATE TABLE billing.invoices
(
    id               UUID         PRIMARY KEY,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    created_by       UUID,
    updated_by       UUID,

    organization_id  UUID         NOT NULL,
    invoice_number   VARCHAR(32)  NOT NULL,
    client_id        UUID         NOT NULL,
    case_id          UUID,
    status           VARCHAR(32)  NOT NULL,
    issue_date       DATE,
    due_date         DATE,
    currency         VARCHAR(3)   NOT NULL,

    subtotal         NUMERIC(15, 2) NOT NULL DEFAULT 0,
    tax_amount       NUMERIC(15, 2) NOT NULL DEFAULT 0,
    discount_amount  NUMERIC(15, 2) NOT NULL DEFAULT 0,
    total_amount     NUMERIC(15, 2) NOT NULL DEFAULT 0,

    notes            VARCHAR(2000),
    paid_at          TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,

    CONSTRAINT uk_invoices_number UNIQUE (organization_id, invoice_number),
    CONSTRAINT ck_invoices_status CHECK (status IN
        ('DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED')),
    CONSTRAINT ck_invoices_currency CHECK (currency ~ '^[A-Z]{3}$'),

    -- Money invariants the database refuses to let the service get wrong. The
    -- total is not a field the application is trusted to keep consistent; it is
    -- an identity, checked on every write.
    CONSTRAINT ck_invoices_subtotal CHECK (subtotal >= 0),
    CONSTRAINT ck_invoices_tax CHECK (tax_amount >= 0),
    CONSTRAINT ck_invoices_discount CHECK (discount_amount >= 0),
    CONSTRAINT ck_invoices_total_nonneg CHECK (total_amount >= 0),
    CONSTRAINT ck_invoices_total CHECK (total_amount = subtotal + tax_amount - discount_amount),
    CONSTRAINT ck_invoices_discount_bound CHECK (discount_amount <= subtotal + tax_amount),

    -- Dates become mandatory the moment an invoice goes out, because an invoice with
    -- no due date has no moment at which it is late and the overdue sweep would never
    -- see it. CANCELLED is exempt alongside DRAFT, and that exemption is load-bearing:
    -- a draft can be withdrawn without ever having been issued, so demanding dates from
    -- it would make cancelling a draft impossible. And a due date before its issue date
    -- is not a shorter payment window, it is a mistake.
    CONSTRAINT ck_invoices_issued_dates CHECK (
        status IN ('DRAFT', 'CANCELLED') OR (issue_date IS NOT NULL AND due_date IS NOT NULL)),
    CONSTRAINT ck_invoices_due_after_issue CHECK (
        issue_date IS NULL OR due_date IS NULL OR due_date >= issue_date),

    -- Each timestamp and the status it shadows are the same fact stated twice;
    -- the database will not let them disagree, exactly as V2 does for closed
    -- cases and V4 for uploaded documents.
    CONSTRAINT ck_invoices_paid_at CHECK ((status = 'PAID') = (paid_at IS NOT NULL)),
    CONSTRAINT ck_invoices_cancelled_at CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE INDEX idx_invoices_organization_created
    ON billing.invoices (organization_id, created_at DESC, id DESC);
CREATE INDEX idx_invoices_organization_status
    ON billing.invoices (organization_id, status);
CREATE INDEX idx_invoices_organization_client
    ON billing.invoices (organization_id, client_id);
CREATE INDEX idx_invoices_organization_case
    ON billing.invoices (organization_id, case_id) WHERE case_id IS NOT NULL;
-- The overdue sweep claims over exactly this predicate and orders by due_date,
-- so the index is partial on it: a job that runs every hour forever should not
-- pay to skip the invoices it has already settled.
CREATE INDEX idx_invoices_due ON billing.invoices (due_date)
    WHERE status IN ('ISSUED', 'PARTIALLY_PAID');

COMMENT ON COLUMN billing.invoices.tax_amount IS
    'Sum of per-line tax. A single generic rate per line, not a GST engine: no CGST/SGST/IGST split, no place-of-supply logic, no HSN/SAC codes, no return filing. Phase 5 records tax; it does not compute statutory liability.';
COMMENT ON COLUMN billing.invoices.total_amount IS
    'subtotal + tax_amount - discount_amount, enforced by ck_invoices_total. Never accepted from a client.';

-- ====================================================== invoice line items
CREATE TABLE billing.invoice_line_items
(
    id           UUID           PRIMARY KEY,
    version      BIGINT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ    NOT NULL,
    updated_at   TIMESTAMPTZ    NOT NULL,
    created_by   UUID,
    updated_by   UUID,

    invoice_id   UUID           NOT NULL,
    description  VARCHAR(500)   NOT NULL,
    -- Scale 3 so half an hour and a third of an hour are both expressible.
    quantity     NUMERIC(12, 3) NOT NULL,
    unit_price   NUMERIC(15, 2) NOT NULL,
    amount       NUMERIC(15, 2) NOT NULL,
    -- A percentage: 18.000 means 18%. Not a fraction, because every invoice a
    -- human reads writes it as a percentage and a silent factor-of-100 error in
    -- a tax column is an expensive kind of ambiguity.
    tax_rate     NUMERIC(6, 3)  NOT NULL DEFAULT 0,
    tax_amount   NUMERIC(15, 2) NOT NULL DEFAULT 0,
    sort_order   INTEGER        NOT NULL,

    CONSTRAINT fk_invoice_line_items_invoice FOREIGN KEY (invoice_id)
        REFERENCES billing.invoices (id) ON DELETE CASCADE,
    -- DEFERRABLE, and this is the one constraint in the schema that needs to be.
    -- Re-pricing a draft replaces its lines wholesale, and Hibernate executes the
    -- inserts for the new set before the deletes for the old one. Every intermediate
    -- state during that flush has two rows at sort_order 0; only the state at COMMIT is
    -- meaningful. An immediate constraint would reject a perfectly valid edit for being
    -- momentarily untrue, which is exactly what deferred checking exists for.
    CONSTRAINT uk_invoice_line_items_order UNIQUE (invoice_id, sort_order)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_invoice_line_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_invoice_line_items_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_invoice_line_items_amount CHECK (amount >= 0),
    CONSTRAINT ck_invoice_line_items_tax_rate CHECK (tax_rate >= 0 AND tax_rate <= 100),
    CONSTRAINT ck_invoice_line_items_tax_amount CHECK (tax_amount >= 0),
    CONSTRAINT ck_invoice_line_items_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX idx_invoice_line_items_invoice
    ON billing.invoice_line_items (invoice_id, sort_order);

-- ================================================================ payments
-- A record that money arrived, not a mechanism for taking it. `method` is a
-- label somebody chose from a list; `reference` is a cheque number or a UPI
-- reference string. There is no gateway, no card data, no tokenised
-- instrument, and no column here that could hold one.
CREATE TABLE billing.payments
(
    id              UUID           PRIMARY KEY,
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    organization_id UUID           NOT NULL,
    invoice_id      UUID           NOT NULL,
    amount          NUMERIC(15, 2) NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    payment_date    DATE           NOT NULL,
    method          VARCHAR(32)    NOT NULL,
    reference       VARCHAR(120),
    notes           VARCHAR(1000),

    CONSTRAINT fk_payments_invoice FOREIGN KEY (invoice_id)
        REFERENCES billing.invoices (id) ON DELETE CASCADE,
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payments_method CHECK (method IN
        ('CASH', 'BANK_TRANSFER', 'CARD', 'UPI', 'CHEQUE', 'OTHER'))
);

CREATE INDEX idx_payments_invoice ON billing.payments (invoice_id, payment_date DESC, id DESC);
CREATE INDEX idx_payments_organization ON billing.payments (organization_id, payment_date DESC);

COMMENT ON COLUMN billing.payments.method IS
    'A recording label only. JurisCore integrates with no payment gateway, card network, UPI handle or bank; nothing in this table was collected by the platform.';

-- =========================================================== notifications
-- In-app only. Nothing in Phase 5 sends an email, an SMS, a WhatsApp message
-- or a push notification, and there is no column here that would carry one —
-- no delivery status, no provider message id, no retry count. A notification
-- is a row a signed-in user reads in the application, and the API is the only
-- delivery channel that exists.
CREATE TABLE notifications.notifications
(
    id                UUID         PRIMARY KEY,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    created_by        UUID,
    updated_by        UUID,

    organization_id   UUID         NOT NULL,
    recipient_user_id UUID         NOT NULL,
    notification_type VARCHAR(64)  NOT NULL,
    category          VARCHAR(32)  NOT NULL,
    severity          VARCHAR(32)  NOT NULL,
    title             VARCHAR(200) NOT NULL,
    message           VARCHAR(1000) NOT NULL,
    entity_type       VARCHAR(64),
    entity_id         UUID,
    -- A relative path the client can navigate to, e.g. /invoices/{id}. Never an
    -- absolute URL and never a signed one: a presigned link is a bearer
    -- credential, and a notification row is the last place one should be stored.
    action_path       VARCHAR(500),
    read_at           TIMESTAMPTZ,
    -- What makes delivery at-most-once for a given business fact. The listener
    -- derives it from the event (type plus the entity it concerns), so a
    -- repeated overdue sweep, a retried publish or a second listener invocation
    -- all collide here instead of filling somebody's inbox.
    dedupe_key        VARCHAR(200),

    CONSTRAINT ck_notifications_category CHECK (category IN
        ('INVOICE', 'PAYMENT', 'CASE', 'SYSTEM')),
    CONSTRAINT ck_notifications_severity CHECK (severity IN
        ('INFO', 'SUCCESS', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_notifications_type CHECK (notification_type IN
        ('INVOICE_ISSUED', 'INVOICE_PAID', 'INVOICE_OVERDUE', 'INVOICE_CANCELLED',
         'PAYMENT_RECEIVED', 'CASE_ASSIGNED', 'SYSTEM_MESSAGE')),
    CONSTRAINT ck_notifications_action_path CHECK (action_path IS NULL OR action_path LIKE '/%')
);

CREATE UNIQUE INDEX uk_notifications_dedupe
    ON notifications.notifications (recipient_user_id, dedupe_key) WHERE dedupe_key IS NOT NULL;
-- Newest first with the id as a tiebreak, matching the repository's ordering:
-- several notifications created in the same millisecond would otherwise page
-- unstably.
CREATE INDEX idx_notifications_recipient
    ON notifications.notifications (recipient_user_id, created_at DESC, id DESC);
CREATE INDEX idx_notifications_unread
    ON notifications.notifications (recipient_user_id, created_at DESC, id DESC) WHERE read_at IS NULL;
CREATE INDEX idx_notifications_organization
    ON notifications.notifications (organization_id, created_at DESC);

COMMENT ON COLUMN notifications.notifications.action_path IS
    'Relative in-app path only (must start with /). Never an absolute or presigned URL.';

-- ================================================ notification preferences
-- One row per user, a column per category. A row per (user, category) would
-- extend without a migration, but it makes "everything is on unless you said
-- otherwise" a query with a LEFT JOIN and a COALESCE per category, and makes an
-- atomic PATCH of four switches four writes. A user with no row here has every
-- category enabled, which is the default the product wants and costs nothing to
-- represent. Adding a category is one ALTER TABLE.
CREATE TABLE notifications.notification_preferences
(
    id              UUID        PRIMARY KEY,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    organization_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    invoice_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    payment_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    case_enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    system_enabled  BOOLEAN     NOT NULL DEFAULT TRUE,

    CONSTRAINT uk_notification_preferences_user UNIQUE (user_id)
);

CREATE INDEX idx_notification_preferences_organization
    ON notifications.notification_preferences (organization_id);

-- ================================================================== audit
-- Append-only, and not merely by convention.
--
-- There is no version column, no updated_at and no updated_by, because there is
-- no update: the entity maps every column updatable = false, the repository
-- exposes no save-over-an-existing-row path, and no controller maps PUT, PATCH
-- or DELETE onto these rows. The absent columns are the point — a table with an
-- optimistic-lock version is a table somebody expects to rewrite.
--
-- organization_id and actor_user_id are both nullable, for the two cases that
-- genuinely have neither: a failed sign-in against an address that matches no
-- account, and the scheduled sweeps that act with no signed-in user.
CREATE TABLE audit.audit_events
(
    id              UUID         PRIMARY KEY,
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID,

    organization_id UUID,
    actor_user_id   UUID,
    action          VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(64)  NOT NULL,
    entity_id       UUID,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    -- The MDC request id RequestIdFilter already puts on every log line, so an
    -- audit row and the logs for the same request can be lined up.
    request_id      VARCHAR(64),
    summary         VARCHAR(500) NOT NULL,
    -- The domain event this row was derived from. UNIQUE, so an at-least-once
    -- delivery cannot record the same business action twice — the idempotency
    -- key DomainEvent.eventId() exists for.
    source_event_id UUID,

    CONSTRAINT uk_audit_events_source UNIQUE (source_event_id)
);

CREATE INDEX idx_audit_events_organization_time
    ON audit.audit_events (organization_id, occurred_at DESC, id DESC);
CREATE INDEX idx_audit_events_organization_action
    ON audit.audit_events (organization_id, action, occurred_at DESC);
CREATE INDEX idx_audit_events_entity
    ON audit.audit_events (organization_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_events_actor
    ON audit.audit_events (organization_id, actor_user_id, occurred_at DESC);

COMMENT ON TABLE audit.audit_events IS
    'Append-only. No version column, no update path, no delete endpoint. Records what happened, never why a request body said it.';
COMMENT ON COLUMN audit.audit_events.summary IS
    'Human-readable, deliberately narrow. Never a request body, a token, a password, a presigned URL, a document byte or a payment credential.';
