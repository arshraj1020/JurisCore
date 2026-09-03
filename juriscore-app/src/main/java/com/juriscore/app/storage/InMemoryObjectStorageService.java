package com.juriscore.app.storage;

import com.juriscore.common.storage.ObjectStorageService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for S3 that keeps object metadata in a map and stores no bytes at all.
 *
 * <p>Active only when {@code juriscore.aws.enabled=false}, which is the test profile and a
 * checkout with no LocalStack running. It exists so the whole suite — and a fresh clone —
 * runs without AWS credentials, while the document rules themselves are exercised for
 * real: the same service code, the same lifecycle, the same completion check against
 * whatever storage says is there.
 *
 * <p>It is emphatically <strong>not</strong> a storage implementation. The URLs it returns
 * point nowhere, {@link #isDurable()} says so, and it shouts at WARN on startup, because
 * a component that silently pretends to store legal documents is the worst possible thing
 * to find in a deployed environment. {@code application-prod.yml} leaves
 * {@code juriscore.aws.enabled} at its default of true, so the S3 adapter is what loads
 * there.
 *
 * <p>{@link #put} is what a test uses to say "the browser finished its upload". Without
 * it, completion fails exactly as it would against S3 when the client never uploaded —
 * which makes the fake stricter than a mock, not laxer.
 *
 * <h2>Links are opaque</h2>
 *
 * <p>A link is a random handle resolved through {@link #keyForLink}, not the object key
 * spelt into a URL. The key is internal — {@code DocumentResponse} omits it and the tests
 * assert that no response body contains it — and a link is the one thing a document
 * endpoint hands back to a client, so a stand-in that pasted the key into its URL would
 * publish through the link exactly what the rest of the design withholds. Keeping the
 * handle opaque also keeps this adapter honest about what a link is: a capability to act
 * on one object for a short while, which is what a presigned URL is, rather than a
 * readable address.
 */
@Component
@ConditionalOnProperty(prefix = "juriscore.aws", name = "enabled", havingValue = "false")
public class InMemoryObjectStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryObjectStorageService.class);
    private static final String BUCKET = "memory://juriscore-documents/";

    /** Canonical object key to what is stored under it. */
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();

    /** Issued link handle to the object key it was issued for. */
    private final Map<String, String> links = new ConcurrentHashMap<>();

    @PostConstruct
    void announce() {
        log.warn("Object storage is the IN-MEMORY stand-in: no file is stored and every "
                + "upload and download link is inert. Set juriscore.aws.enabled=true for S3.");
    }

    @Override
    public PresignedUrl presignUpload(String key, String contentType, long sizeBytes) {
        return issue("uploads", key, Duration.ofMinutes(15));
    }

    @Override
    public PresignedUrl presignDownload(String key, String downloadFilename) {
        return issue("downloads", key, Duration.ofMinutes(5));
    }

    private PresignedUrl issue(String kind, String key, Duration expiry) {
        String handle = UUID.randomUUID().toString();
        links.put(handle, key);
        return new PresignedUrl(BUCKET + kind + "/" + handle, expiry, Instant.now().plus(expiry));
    }

    @Override
    public Optional<StoredObject> head(String key) {
        return Optional.ofNullable(objects.get(key));
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    @Override
    public boolean isDurable() {
        return false;
    }

    // ------------------------------------------------------------------ test affordances

    /** Stands in for the browser having completed its PUT to the presigned URL. */
    public void put(String key, long sizeBytes, String contentType) {
        objects.put(key, new StoredObject(key, sizeBytes, contentType));
    }

    public boolean contains(String key) {
        return objects.containsKey(key);
    }

    /**
     * The object key a previously issued link points at.
     *
     * <p>The internal half of the arrangement above: a test that only has the URL from an
     * API response can still find out which object it addresses, without that key ever
     * having travelled in the response.
     */
    public Optional<String> keyForLink(String url) {
        if (url == null) {
            return Optional.empty();
        }
        int lastSlash = url.lastIndexOf('/');
        return lastSlash < 0 ? Optional.empty()
                : Optional.ofNullable(links.get(url.substring(lastSlash + 1)));
    }

    public int size() {
        return objects.size();
    }

    public void clear() {
        objects.clear();
        links.clear();
    }
}
