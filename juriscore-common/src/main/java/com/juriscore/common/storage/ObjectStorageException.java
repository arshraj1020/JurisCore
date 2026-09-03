package com.juriscore.common.storage;

/**
 * Storage refused, or could not be reached.
 *
 * <p>Deliberately not an {@code ApiException}: whether a storage failure is a 500, a
 * retry, or something to be ignored is a decision for the calling service, not for the
 * adapter that noticed it. A failed object delete, for instance, must not fail the
 * request that already committed the metadata.
 *
 * <p>Messages here name the operation and the key. They never carry a presigned URL, a
 * credential, or an SDK response body, because this is the exception most likely to be
 * logged at ERROR with everything it holds.
 */
public class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message) {
        super(message);
    }

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
