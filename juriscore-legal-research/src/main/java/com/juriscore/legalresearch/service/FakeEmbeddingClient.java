package com.juriscore.legalresearch.service;

import com.juriscore.legalresearch.domain.EmbeddingSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/**
 * A stand-in for a real embedding provider, in the same spirit as
 * {@code InMemoryObjectStorageService}: active only when
 * {@code juriscore.legal-research.embedding.provider=fake} (the test profile's setting),
 * so the whole ingestion pipeline — chunking, persistence, status transitions — is
 * exercised for real against deterministic vectors, with no network call and no API key.
 *
 * <p>Deterministic on the input text (seeded by its hash), not random per call: the same
 * chunk text always embeds to the same vector, so a test can assert on embeddings without
 * the flakiness a true random vector would introduce.
 */
@Component
@ConditionalOnProperty(prefix = "juriscore.legal-research.embedding", name = "provider",
        havingValue = "fake")
public class FakeEmbeddingClient implements EmbeddingClient {

    @Override
    public String modelName() {
        return "fake-deterministic-embedding";
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(FakeEmbeddingClient::embed).toList();
    }

    private static float[] embed(String text) {
        long seed = text == null ? 0L : text.hashCode();
        Random random = new Random(seed);
        float[] vector = new float[EmbeddingSchema.VECTOR_DIMENSIONS];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (random.nextFloat() * 2f) - 1f;
        }
        return vector;
    }
}
