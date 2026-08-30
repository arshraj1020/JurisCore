-- =============================================================================
-- JurisCore V1 — tenant boundary and identity
--
-- One database, one schema per module. A module reads and writes only its own
-- schema; anything it needs from another module comes through that module's API,
-- never through a cross-schema join. Keeping that rule now is what makes pulling a
-- module out into its own service later a packaging change rather than a rewrite —
-- so there are deliberately no foreign keys between schemas.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS organization;
CREATE SCHEMA IF NOT EXISTS identity;

-- ----------------------------------------------------------------- organizations
-- The tenant. Every other table in the platform carries this row's id.
CREATE TABLE organization.organizations
(
    id                  UUID         PRIMARY KEY,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    created_by          UUID,
    updated_by          UUID,

    name                VARCHAR(200) NOT NULL,
    slug                VARCHAR(120) NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    contact_email       VARCHAR(255),
    contact_phone       VARCHAR(40),
    address_line1       VARCHAR(255),
    address_line2       VARCHAR(255),
    city                VARCHAR(120),
    state               VARCHAR(120),
    country             VARCHAR(120),
    postal_code         VARCHAR(20),
    timezone            VARCHAR(64)  NOT NULL DEFAULT 'Asia/Kolkata',
    registration_number VARCHAR(120),

    CONSTRAINT uk_organizations_slug UNIQUE (slug),
    CONSTRAINT ck_organizations_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

COMMENT ON TABLE organization.organizations IS 'Law firms. This row is the tenant boundary.';

-- ------------------------------------------------------------------------- users
-- organization_id is nullable for exactly one reason: SUPER_ADMIN operates the
-- platform and belongs to no firm. Every other role must have a tenant, which the
-- check constraint below enforces at the database rather than trusting the service.
CREATE TABLE identity.users
(
    id                    UUID         PRIMARY KEY,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    created_by            UUID,
    updated_by            UUID,

    organization_id       UUID,
    email                 VARCHAR(255) NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    first_name            VARCHAR(100) NOT NULL,
    last_name             VARCHAR(100) NOT NULL,
    phone                 VARCHAR(40),
    role                  VARCHAR(32)  NOT NULL,
    status                VARCHAR(32)  NOT NULL,
    last_login_at         TIMESTAMPTZ,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    token_generation      INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT ck_users_role CHECK (role IN ('SUPER_ADMIN', 'FIRM_ADMIN', 'LAWYER', 'CLERK', 'CLIENT')),
    CONSTRAINT ck_users_status CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    CONSTRAINT ck_users_tenant CHECK (role = 'SUPER_ADMIN' OR organization_id IS NOT NULL)
);

-- Email uniqueness is case-insensitive, and it has to be a functional index rather than
-- a plain UNIQUE(email) for two reasons that were measured, not assumed:
--   1. Correctness. The application treats addresses case-insensitively
--      (findByEmailIgnoreCase), so a plain unique constraint would happily accept both
--      'asha@firm.test' and 'ASHA@firm.test'. Two rows matching one case-insensitive
--      lookup then make Optional<User> throw, turning sign-in into a 500.
--   2. Performance. Spring Data renders that finder as `lower(email) = lower(?)`, which
--      a plain b-tree on email cannot serve. Measured on 20k users: sequential scan
--      5.3ms touching every row, versus 0.08ms with this index — on the sign-in path,
--      which is both the hottest endpoint and the one attackers hammer.
CREATE UNIQUE INDEX uk_users_email_lower ON identity.users (lower(email));
CREATE INDEX idx_users_organization ON identity.users (organization_id);
CREATE INDEX idx_users_organization_role ON identity.users (organization_id, role);

COMMENT ON COLUMN identity.users.token_generation IS
    'Bumped on password change, role change and suspension. Access tokens carry this value, so a token minted before the bump stops validating immediately.';

-- ---------------------------------------------------------------- refresh tokens
-- Only the SHA-256 hash is stored: a database dump must not yield usable sessions.
CREATE TABLE identity.refresh_tokens
(
    id          UUID        PRIMARY KEY,
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID,
    updated_by  UUID,

    user_id     UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID,
    user_agent  VARCHAR(255),
    ip_address  VARCHAR(64),

    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id)
        REFERENCES identity.users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_refresh_tokens_hash ON identity.refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_user ON identity.refresh_tokens (user_id);
-- Supports the nightly purge of expired rows.
CREATE INDEX idx_refresh_tokens_expiry ON identity.refresh_tokens (expires_at);

-- ---------------------------------------------------------- password reset tokens
CREATE TABLE identity.password_reset_tokens
(
    id         UUID        PRIMARY KEY,
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_by UUID,

    user_id    UUID        NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,

    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id)
        REFERENCES identity.users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_password_reset_hash ON identity.password_reset_tokens (token_hash);
CREATE INDEX idx_password_reset_user ON identity.password_reset_tokens (user_id);
CREATE INDEX idx_password_reset_expiry ON identity.password_reset_tokens (expires_at);
