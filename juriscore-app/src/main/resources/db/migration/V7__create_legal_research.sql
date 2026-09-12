-- =============================================================================
-- JurisCore V7 — legal research (Legal Precedent Intelligence, step 1)
--
-- Schema-per-module, no foreign keys leaving it, same rules as V1-V6.
-- source_document_id points into documents.case_documents and is therefore a
-- plain UUID, validated through the documents module's service API before
-- anything is written here — a judgment is a case document that this module
-- additionally extracts, chunks and indexes.
--
-- judgment_id -> legal_judgments DOES carry a real foreign key: both tables
-- live in this schema, the same relationship invoice_line_items has to
-- invoices in V5.
--
-- ---------------------------------------------------------------- pgvector
-- Requires the pgvector extension (https://github.com/pgvector/pgvector) to
-- be installed in the PostgreSQL image/instance this migration runs against
-- (the dev docker-compose Postgres image and the integration-test
-- Testcontainers image were both switched to `pgvector/pgvector:pg16` for
-- this reason — see docker-compose.yml and AbstractIntegrationTest). Plain
-- postgres:16 does NOT have the extension binary and CREATE EXTENSION will
-- fail against it.
--
-- pgvector requires a FIXED dimension per column — there is no variable-width
-- vector type. 1536 is the output size of OpenAI's text-embedding-3-small,
-- the default embedding model for this feature (see
-- com.juriscore.legalresearch.config.LegalResearchProperties and
-- com.juriscore.legalresearch.domain.EmbeddingSchema, which is the one place
-- in the Java code this number is named). Changing the embedding model to one
-- with a different output size requires a NEW migration (either ALTER COLUMN
-- ... TYPE vector(N) with every existing row re-embedded, or a new column and
-- a backfill) — it cannot be done by changing configuration alone. That
-- constraint is deliberately isolated to this file and to EmbeddingSchema
-- rather than spread across the module.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS legal_research;

CREATE TABLE legal_research.legal_judgments
(
    id                  UUID          PRIMARY KEY,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    created_by          UUID,
    updated_by          UUID,

    organization_id     UUID          NOT NULL,
    -- documents.case_documents.id. One judgment per uploaded document.
    source_document_id  UUID          NOT NULL,

    -- Extracted metadata. Every one of these is nullable on purpose: if extraction
    -- cannot confidently identify a field, the field stays null rather than being
    -- guessed. Nothing here is invented — see docs/ for the hallucination-prevention
    -- rule this schema exists to make possible to enforce.
    case_name           VARCHAR(500),
    court                VARCHAR(255),
    decided_date         DATE,
    judges               VARCHAR(1000),
    citation             VARCHAR(255),
    page_count           INTEGER,

    extraction_status    VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    failure_reason        VARCHAR(2000),

    CONSTRAINT uk_legal_judgments_source_document UNIQUE (source_document_id),
    CONSTRAINT ck_legal_judgments_status CHECK (extraction_status IN
        ('PENDING', 'EXTRACTING', 'CHUNKED', 'EMBEDDED', 'READY', 'FAILED')),
    CONSTRAINT ck_legal_judgments_page_count CHECK (page_count IS NULL OR page_count >= 0)
);

CREATE INDEX idx_legal_judgments_org ON legal_research.legal_judgments
    (organization_id, created_at DESC, id DESC);

CREATE INDEX idx_legal_judgments_status ON legal_research.legal_judgments
    (organization_id, extraction_status);

COMMENT ON COLUMN legal_research.legal_judgments.source_document_id IS
    'documents.case_documents.id — no FK, cross-schema, validated through DocumentService.';
COMMENT ON COLUMN legal_research.legal_judgments.extraction_status IS
    'PENDING -> EXTRACTING -> CHUNKED -> EMBEDDED -> READY, or FAILED at any step. See failure_reason when FAILED.';

CREATE TABLE legal_research.legal_chunks
(
    id                UUID          PRIMARY KEY,
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    created_by        UUID,
    updated_by        UUID,

    organization_id   UUID          NOT NULL,
    judgment_id       UUID          NOT NULL REFERENCES legal_research.legal_judgments (id),

    chunk_index       INTEGER       NOT NULL,
    chunk_text        TEXT          NOT NULL,

    -- Preserved wherever extraction could identify them, so a citation produced from
    -- this chunk later can point at an exact paragraph/page rather than "somewhere in
    -- the document". Null, not zero, when unknown.
    paragraph_number  INTEGER,
    page_number       INTEGER,
    char_start        INTEGER,
    char_end          INTEGER,

    -- Fixed at 1536 dimensions — see the pgvector note above. Null until the
    -- embedding step has run for this chunk.
    embedding         vector(1536),

    -- PostgreSQL full-text search, NOT "BM25": ts_rank is PostgreSQL's own ranking
    -- function over a tsvector, combined with pgvector cosine similarity via
    -- Reciprocal Rank Fusion at query time. This generated column is what the GIN
    -- index below is built on.
    search_vector     tsvector GENERATED ALWAYS AS (to_tsvector('english', chunk_text)) STORED,

    CONSTRAINT uk_legal_chunks_judgment_index UNIQUE (judgment_id, chunk_index),
    CONSTRAINT ck_legal_chunks_char_range CHECK (
        char_start IS NULL OR char_end IS NULL OR char_end >= char_start)
);

CREATE INDEX idx_legal_chunks_judgment ON legal_research.legal_chunks (judgment_id, chunk_index);
CREATE INDEX idx_legal_chunks_org ON legal_research.legal_chunks (organization_id);
CREATE INDEX idx_legal_chunks_fts ON legal_research.legal_chunks USING GIN (search_vector);

-- ivfflat needs `lists` chosen for the eventual row count; 100 is a reasonable
-- starting point for a single-firm judgment corpus and is cheap to REINDEX later
-- as the table grows. Built empty is fine — ivfflat degrades gracefully and this
-- keeps the migration from depending on there being data yet.
CREATE INDEX idx_legal_chunks_embedding ON legal_research.legal_chunks
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

COMMENT ON COLUMN legal_research.legal_chunks.embedding IS
    'OpenAI text-embedding-3-small, 1536 dimensions. See EmbeddingSchema.VECTOR_DIMENSIONS.';
COMMENT ON COLUMN legal_research.legal_chunks.search_vector IS
    'PostgreSQL full-text search vector (NOT BM25). Combined with pgvector similarity via Reciprocal Rank Fusion at query time.';
