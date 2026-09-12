package com.juriscore.legalresearch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.legalresearch.config.LegalResearchProperties;
import com.juriscore.legalresearch.domain.EmbeddingSchema;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link EmbeddingClient} backed by OpenAI's {@code /v1/embeddings} endpoint.
 *
 * <p>Uses the JDK's own {@link HttpClient} rather than an OpenAI SDK — a deliberate
 * choice to avoid a new dependency for what is one REST call with a small, stable request
 * and response shape. If a later step needs more of the API surface (chat completions for
 * {@link LlmClient}, function calling, streaming), an SDK becomes the better trade and can
 * replace this without the port ({@link EmbeddingClient}) changing.
 *
 * <p>The API key comes from {@code juriscore.legal-research.embedding.api-key}
 * ({@code JURISCORE_LEGAL_RESEARCH_EMBEDDING_API_KEY}), with no default — unlike the JWT
 * secret, an unset key does not stop the application from starting (embeddings are a
 * feature of one module, not the security perimeter every request passes through), but
 * this client refuses to call the API without one, so a missing key surfaces as a FAILED
 * judgment with a clear reason instead of a confusing 401 buried in a stack trace.
 */
@Component
@ConditionalOnProperty(prefix = "juriscore.legal-research.embedding", name = "provider",
        havingValue = "openai", matchIfMissing = true)
@RequiredArgsConstructor
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/embeddings");
    /** Conservative batch size; OpenAI's own limit is much higher. */
    private static final int MAX_BATCH_SIZE = 96;

    private final LegalResearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String modelName() {
        return properties.getEmbedding().getModel();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        String apiKey = properties.getEmbedding().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new EmbeddingException(
                    "No OpenAI API key configured (juriscore.legal-research.embedding.api-key / "
                            + "JURISCORE_LEGAL_RESEARCH_EMBEDDING_API_KEY). Cannot generate embeddings.");
        }

        List<float[]> result = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(start, Math.min(start + MAX_BATCH_SIZE, texts.size()));
            result.addAll(embedOneBatch(batch, apiKey));
        }
        return result;
    }

    private List<float[]> embedOneBatch(List<String> batch, String apiKey) {
        try {
            String requestBody = objectMapper.writeValueAsString(new EmbeddingRequest(
                    properties.getEmbedding().getModel(), batch));

            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // The body is never logged in full: it can echo back the input text, which
                // is the judgment's content. The status and a truncated prefix are enough
                // to diagnose a bad request without putting a firm's document in a log.
                throw new EmbeddingException("OpenAI embeddings request failed with HTTP "
                        + response.statusCode() + ": "
                        + truncate(response.body()));
            }

            return parse(response.body(), batch.size());
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("OpenAI embeddings request failed", e);
        }
    }

    private List<float[]> parse(String responseBody, int expectedCount) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.size() != expectedCount) {
            throw new EmbeddingException("OpenAI embeddings response had an unexpected shape "
                    + "(expected " + expectedCount + " items)");
        }

        // The API is documented to preserve input order, but `index` is returned
        // specifically so a caller can verify that rather than assume it — cheap
        // insurance against a provider change silently reordering results.
        float[][] byIndex = new float[expectedCount][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode embeddingNode = item.get("embedding");
            if (index < 0 || index >= expectedCount || embeddingNode == null || !embeddingNode.isArray()) {
                throw new EmbeddingException("OpenAI embeddings response item was malformed");
            }
            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            if (vector.length != EmbeddingSchema.VECTOR_DIMENSIONS) {
                throw new EmbeddingException("OpenAI returned a " + vector.length
                        + "-dimensional embedding but this schema is fixed at "
                        + EmbeddingSchema.VECTOR_DIMENSIONS + " dimensions ("
                        + properties.getEmbedding().getModel() + " may not be "
                        + "text-embedding-3-small — see EmbeddingSchema)");
            }
            byIndex[index] = vector;
        }
        for (float[] vector : byIndex) {
            if (vector == null) {
                throw new EmbeddingException("OpenAI embeddings response was missing an item");
            }
        }
        return List.of(byIndex);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "...";
    }

    /** Request shape per https://platform.openai.com/docs/api-reference/embeddings. */
    private record EmbeddingRequest(String model, List<String> input) {
    }
}
