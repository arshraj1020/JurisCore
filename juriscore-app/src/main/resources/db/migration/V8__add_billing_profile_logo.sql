-- =============================================================================
-- V8 — a logo for the firm's invoices.
--
-- Phase 7 (Professional Billing Delivery) renders invoices in the firm's own identity
-- rather than JurisCore's. Every other field that identity needs — legal name, tax
-- registration, billing address, contact details, standing notes for payment terms —
-- already exists on billing.billing_profiles (see V5). The one genuinely missing piece
-- is a logo.
--
-- This deliberately stores a URL rather than adding an upload pipeline: JurisCore has no
-- image-hosting concern anywhere in the product yet, and building one (presigned upload,
-- object storage key, content-type validation) is a disproportionate amount of new
-- infrastructure for "put an image on a PDF". A firm points this at an image it already
-- hosts; a full upload flow is left for a later iteration if firms ask for it.
--
-- Nullable, like the address and contact columns beside it: a firm that has not set a
-- logo prints an invoice without one rather than with a broken image.
--
-- Additive and non-destructive. Historical migrations are left exactly as they are.
-- =============================================================================

ALTER TABLE billing.billing_profiles
    ADD COLUMN logo_url VARCHAR(500);

COMMENT ON COLUMN billing.billing_profiles.logo_url IS
    'A URL to an image the firm already hosts, printed at the top of its invoice PDFs. Not an upload -- see V8 header.';
