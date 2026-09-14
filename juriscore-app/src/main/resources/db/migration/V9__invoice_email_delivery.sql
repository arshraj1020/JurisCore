-- =============================================================================
-- V9 — whether an invoice has been emailed to its client.
--
-- Phase 7 milestone 2. Three columns on billing.invoices, and deliberately not a
-- table: this records the CURRENT delivery state of an invoice, and the history
-- of who sent what and when is already the audit trail's job (invoice.emailed and
-- invoice.email_failed, see DomainEventAuditListener). A second history table
-- would be a second answer to a question audit.audit_events already answers.
--
-- Delivery is a separate axis from status, not more values on it: an invoice can
-- be PAID and never emailed, or ISSUED and emailed three times. Folding the two
-- together would multiply every payment transition by every delivery outcome.
--
-- No recipient column on top of casework.clients.email, and no per-invoice
-- override: a bill goes to the client it bills. email_recipient below is a record
-- of where the last attempt was ADDRESSED, not a second place to configure it —
-- which is why it is written by the attempt rather than read by it.
--
-- Additive and non-destructive. Existing invoices take the NOT_SENT default,
-- which is exactly true of every one of them.
-- =============================================================================

ALTER TABLE billing.invoices
    ADD COLUMN email_status          VARCHAR(32) NOT NULL DEFAULT 'NOT_SENT',
    ADD COLUMN email_recipient       VARCHAR(255),
    ADD COLUMN email_last_attempt_at TIMESTAMPTZ;

ALTER TABLE billing.invoices
    ADD CONSTRAINT ck_invoices_email_status CHECK (email_status IN
        ('NOT_SENT', 'SENT', 'FAILED'));

-- The three columns are one fact, so the database refuses a row where they
-- disagree — the same reasoning as ck_invoices_paid_at. An invoice nobody has
-- tried to email has no attempt time and no recipient; one that has been tried
-- has both. Invoice.recordEmailAttempt is the only code path that writes them.
ALTER TABLE billing.invoices
    ADD CONSTRAINT ck_invoices_email_attempt CHECK (
        (email_status = 'NOT_SENT'
            AND email_last_attempt_at IS NULL
            AND email_recipient IS NULL)
        OR (email_status <> 'NOT_SENT'
            AND email_last_attempt_at IS NOT NULL
            AND email_recipient IS NOT NULL));

COMMENT ON COLUMN billing.invoices.email_status IS
    'NOT_SENT | SENT | FAILED. SENT means the provider accepted the message, not that it was read.';
COMMENT ON COLUMN billing.invoices.email_recipient IS
    'Where the last attempt was addressed. A record of what happened, not configuration -- the address itself lives on casework.clients.';
