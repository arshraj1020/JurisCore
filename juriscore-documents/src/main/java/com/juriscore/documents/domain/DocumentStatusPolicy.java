package com.juriscore.documents.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The document lifecycle, as data, in the shape Phase 3's policies established.
 *
 * <p>Two edges are worth reading twice. {@code UPLOADING -> DELETED} exists so an
 * abandoned upload can be cleaned up without first pretending it succeeded. And there is
 * no edge back out of {@link DocumentStatus#DELETED}: undeleting would have to resurrect
 * an object that {@code DeletedDocumentObjectCleaner} has already removed from storage, so
 * the metadata would point at nothing.
 *
 * <p>{@code AVAILABLE -> AVAILABLE} is refused here rather than treated as a no-op. That
 * matters for retries: a client that completes twice must not re-transition or publish a
 * second event, and the service handles that by not calling this at all the second time —
 * see {@code DocumentService#completeUpload}. Making the policy permissive instead would
 * have hidden the double-completion rather than handled it.
 */
public final class DocumentStatusPolicy {

    private static final Map<DocumentStatus, Set<DocumentStatus>> ALLOWED =
            new EnumMap<>(DocumentStatus.class);

    static {
        ALLOWED.put(DocumentStatus.UPLOADING,
                Set.of(DocumentStatus.AVAILABLE, DocumentStatus.FAILED, DocumentStatus.DELETED));
        ALLOWED.put(DocumentStatus.AVAILABLE, Set.of(DocumentStatus.DELETED));
        ALLOWED.put(DocumentStatus.FAILED, Set.of(DocumentStatus.DELETED));
        ALLOWED.put(DocumentStatus.DELETED, Set.of());
    }

    private DocumentStatusPolicy() {
    }

    public static Set<DocumentStatus> allowedFrom(DocumentStatus current) {
        return ALLOWED.getOrDefault(current, Set.of());
    }

    public static boolean permits(DocumentStatus current, DocumentStatus target) {
        return current != null && target != null && allowedFrom(current).contains(target);
    }

    /**
     * @throws ApiException {@code ILLEGAL_STATE_TRANSITION} (409) for any move the
     *                      lifecycle does not allow, including a no-op.
     */
    public static void requireTransition(DocumentStatus current, DocumentStatus target) {
        if (!permits(current, target)) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A document cannot move from " + current + " to " + target);
        }
    }
}
