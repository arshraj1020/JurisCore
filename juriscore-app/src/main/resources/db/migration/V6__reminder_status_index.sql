-- =============================================================================
-- V6 — an index for the reminder list's status filter.
--
-- GET /api/v1/reminders?status=... is served by
-- ReminderRepository.findByOrganizationIdAndStatus, and the closest existing index is
-- idx_reminders_organization (organization_id, remind_at) from V3. PostgreSQL can use its
-- leading column, so the tenant's rows are found by index — and then every one of them is
-- read to test `status`, because status is not in the index at all. For a firm with a few
-- reminders that costs nothing; for one with tens of thousands it is a filter over the
-- whole tenant partition on a screen people leave open.
--
-- The ordering column is included so the sort is served by the same index rather than by a
-- sort node on top of it, which is what the paged endpoint actually asks for.
--
-- Additive and non-destructive: a new index, no data or constraint changes. Historical
-- migrations are left exactly as they are.
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_reminders_organization_status
    ON case_management.reminders (organization_id, status, remind_at);

COMMENT ON INDEX case_management.idx_reminders_organization_status IS
    'Serves ReminderRepository.findByOrganizationIdAndStatus, ordered by remind_at.';
