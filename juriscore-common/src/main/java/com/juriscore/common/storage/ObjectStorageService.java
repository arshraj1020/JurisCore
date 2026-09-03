package com.juriscore.common.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * Outbound port for object storage, in the same spirit as {@code EventPublisher}.
 *
 * <p>It lives in {@code common} rather than in the documents module for a practical
 * reason: the SDK clients and their credentials are configured in {@code juriscore-app},
 * and a module cannot depend on the application that assembles it. So the port is
 * declared here, the adapters live beside {@code AwsConfig}, and the documents module
 * depends on neither S3 nor the app.
 *
 * <p>Everything the domain needs is expressed without a single AWS type. Swapping S3 for
 * anything else — or for the in-memory double the tests use — is a bean definition, not a
 * change to any business rule. That is the whole point of the seam.
 *
 * <p><strong>Bytes never pass through the application.</strong> Callers get a short-lived
 * presigned URL and the browser talks to storage directly. A legal platform proxying
 * every filing through its own heap is a memory profile nobody wants and a bottleneck in
 * front of something that scales perfectly well on its own.
 */
public interface ObjectStorageService {

    /**
     * A time-limited URL, and the moment it stops working.
     *
     * <p>Never logged and never published on a domain event: a presigned URL is a bearer
     * credential for the object it points at, and it does not stop being one because it
     * appears in a log aggregator.
     */
    record PresignedUrl(String url, Duration validFor, java.time.Instant expiresAt) {
    }

    /** What storage says is actually there — the only trustworthy account of an upload. */
    record StoredObject(String key, long sizeBytes, String contentType) {
    }

    /**
     * A URL the client may PUT exactly one object to.
     *
     * @param contentType signed into the request, so the upload must declare the same
     *                    type it was authorized for
     * @param sizeBytes   the size the caller declared. See the implementation notes on
     *                    what a presigned PUT can and cannot enforce about it.
     */
    PresignedUrl presignUpload(String key, String contentType, long sizeBytes);

    /**
     * A URL the client may GET the object from.
     *
     * @param downloadFilename the name the browser should save it as, so the object key —
     *                         which is internal — never reaches the user
     */
    PresignedUrl presignDownload(String key, String downloadFilename);

    /**
     * What storage holds at {@code key}, or empty if there is nothing there.
     *
     * <p>This is how upload completion is verified. The client tells us it finished; this
     * asks storage whether that is true, and how big the thing actually is.
     */
    Optional<StoredObject> head(String key);

    /**
     * Removes the object. Idempotent: deleting something already gone is not an error.
     *
     * @throws ObjectStorageException if storage refused or could not be reached — the
     *                                caller decides what that means, because for a delete
     *                                it usually means "try again later", not "fail the
     *                                request"
     */
    void delete(String key);

    /**
     * Whether this implementation actually persists bytes.
     *
     * <p>False for the in-memory double, and it is deliberately part of the interface so
     * that the application can say so at startup rather than looking like it works.
     */
    boolean isDurable();
}
