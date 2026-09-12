package com.juriscore.legalresearch.domain;

/**
 * The one place the pgvector column width is named in Java.
 *
 * <p>pgvector requires a fixed dimension per column — there is no variable-width vector
 * type — so {@link LegalChunk#getEmbedding()} and the {@code embedding vector(1536)}
 * column V7 creates have to agree, and cannot be changed independently. This constant is
 * that agreement, made explicit in one place instead of a number repeated across the
 * module. {@link com.juriscore.legalresearch.config.LegalResearchProperties} checks the
 * configured embedding model's dimensions against it at startup, so a mismatched
 * configuration fails fast rather than writing truncated or rejected embeddings.
 *
 * <p>Changing the embedding model to one with a different output size is a schema
 * migration (a new {@code vector(N)} column plus a backfill, or an {@code ALTER COLUMN}
 * with every row re-embedded), never a configuration change alone.
 */
public final class EmbeddingSchema {

    /** Output size of OpenAI's {@code text-embedding-3-small}, the default embedding model. */
    public static final int VECTOR_DIMENSIONS = 1536;

    private EmbeddingSchema() {
    }
}
