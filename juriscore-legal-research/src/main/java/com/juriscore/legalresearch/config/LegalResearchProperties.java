package com.juriscore.legalresearch.config;

import com.juriscore.legalresearch.domain.EmbeddingSchema;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Legal research policy, bound from {@code juriscore.legal-research.*}.
 *
 * <p>{@code embedding.dimensions} is deliberately re-validated here against
 * {@link EmbeddingSchema#VECTOR_DIMENSIONS} at startup: the database column width is fixed
 * by the V7 migration and cannot follow a configuration change, so a mismatch has to stop
 * the application before it silently writes vectors of the wrong width, rather than
 * failing confusingly on the first insert. This is the isolation point the plan called
 * for — one place that knows the schema's dimension is fixed, and refuses to start rather
 * than let configuration and schema disagree.
 *
 * <p>{@code llm.model} exists as configuration ahead of the LLM client landing in a later
 * step, so the model name is never hardcoded once that client is added.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "juriscore.legal-research")
public class LegalResearchProperties {

    @NestedConfigurationProperty
    private Embedding embedding = new Embedding();

    @NestedConfigurationProperty
    private Llm llm = new Llm();

    @NestedConfigurationProperty
    private Chunking chunking = new Chunking();

    @PostConstruct
    void validate() {
        if (embedding.dimensions != EmbeddingSchema.VECTOR_DIMENSIONS) {
            throw new IllegalStateException(
                    "juriscore.legal-research.embedding.dimensions=" + embedding.dimensions
                            + " does not match the pgvector column width ("
                            + EmbeddingSchema.VECTOR_DIMENSIONS
                            + ", fixed by migration V7). Changing the embedding model's "
                            + "dimensionality requires a new migration, not a configuration change.");
        }
    }

    @Getter
    @Setter
    public static class Embedding {
        /** Passed to the embedding API as-is; carries no meaning to this module beyond that. */
        private String model = "text-embedding-3-small";

        /** Must equal {@link EmbeddingSchema#VECTOR_DIMENSIONS} — see {@link #validate()}. */
        private int dimensions = EmbeddingSchema.VECTOR_DIMENSIONS;

        /**
         * "openai" (default) selects {@link com.juriscore.legalresearch.service.OpenAiEmbeddingClient};
         * "fake" selects {@link com.juriscore.legalresearch.service.FakeEmbeddingClient}, a
         * deterministic stand-in with no network call — set by the test profile so
         * ingestion tests never depend on a live API key. The same seam
         * {@code juriscore.aws.enabled} gives {@code ObjectStorageService}.
         */
        private String provider = "openai";

        /**
         * No default, unlike the JWT secret: an unset key does not stop the application
         * starting (embeddings are one module's feature, not the security perimeter), but
         * {@link com.juriscore.legalresearch.service.OpenAiEmbeddingClient} refuses to call
         * the API without one.
         */
        private String apiKey = "";
    }

    @Getter
    @Setter
    public static class Chunking {
        /**
         * Consecutive paragraphs are accumulated into a chunk up to roughly this many
         * characters before a new chunk starts; a single paragraph longer than this is
         * split on its own. See {@code LegalChunker}.
         */
        private int maxChunkChars = 1800;
    }

    @Getter
    @Setter
    public static class Llm {
        private String model = "gpt-4o-mini";
    }
}
