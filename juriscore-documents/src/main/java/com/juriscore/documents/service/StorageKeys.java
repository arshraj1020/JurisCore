package com.juriscore.documents.service;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds the object key, from nothing a caller supplied.
 *
 * <pre>organizations/{organizationId}/cases/{caseId}/documents/{documentId}</pre>
 *
 * <p>Every segment is a UUID the platform generated. There is no filename in it, no
 * description, no header value — nothing a user typed. That is a stronger property than
 * sanitising user input would be: traversal and cross-tenant collision are not filtered
 * out, they are unrepresentable, because a {@code UUID} cannot contain a slash or a
 * {@code ..} no matter what anybody sends.
 *
 * <p>The organization prefix leads for a second reason. It is what an S3 bucket policy or
 * an IAM condition keys on, so a future least-privilege split — a role that can only touch
 * one firm's prefix — is a policy document rather than a migration.
 *
 * <p>The original filename is carried in the database and re-attached at download time
 * through {@code Content-Disposition}. Using it as the key instead would have made the
 * bucket layout depend on user input and object identity depend on a mutable field.
 */
public final class StorageKeys {

    private static final String UUID_SEGMENT =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private static final Pattern CANONICAL = Pattern.compile(
            "^organizations/" + UUID_SEGMENT
                    + "/cases/" + UUID_SEGMENT
                    + "/documents/" + UUID_SEGMENT + "$");

    /**
     * Marks the value that holds a row's slot in {@code uk_case_documents_storage_key}
     * between the INSERT and the statement that stamps the real key. It is deliberately
     * not a key shape: {@link #isCanonical} rejects it, and nothing outside
     * {@code DocumentService.register} ever sees it.
     */
    private static final String RESERVED_PREFIX = "reserved:";

    private StorageKeys() {
    }

    public static String forDocument(UUID organizationId, UUID caseId, UUID documentId) {
        if (organizationId == null || caseId == null || documentId == null) {
            throw new IllegalArgumentException(
                    "An object key needs an organization, a case and a document");
        }
        return "organizations/" + organizationId
                + "/cases/" + caseId
                + "/documents/" + documentId;
    }

    /**
     * A unique, deliberately non-key value for the row's first write.
     *
     * <p>It exists because of a hard constraint rather than a preference. The key's last
     * segment is the document id, and {@code BaseEntity} generates that id — an id
     * assigned before the insert is rejected outright ({@code PersistentObjectException:
     * detached entity passed to persist}), and a value set on the entity after
     * {@code persist} but before {@code flush} is not folded into the INSERT. So the row
     * is inserted once and stamped once, inside a single transaction, and this is what
     * occupies the NOT NULL, UNIQUE column in between. It is unique per registration
     * because the unique index applies to it too, so a constant would make two
     * registrations in one flush collide.
     *
     * <p>It never reaches object storage: {@code register} presigns only a value that
     * passes {@link #requireCanonical}.
     */
    public static String reservation() {
        return RESERVED_PREFIX + UUID.randomUUID();
    }

    /** Whether this is a real object key: three generated ids and nothing else. */
    public static boolean isCanonical(String key) {
        return key != null && CANONICAL.matcher(key).matches();
    }

    /**
     * Refuses to let anything but a real object key reach storage.
     *
     * <p>A guard against a coding mistake, not against user input — there is no user input
     * in a key. It is here because the one way this could go wrong is a key handed out
     * before it was stamped, and that failure would be silent: a presigned URL for a
     * nonsense path, an upload that lands nowhere, and a document that can never complete.
     */
    public static void requireCanonical(String key) {
        if (!isCanonical(key)) {
            throw new IllegalStateException(
                    "Refusing to use a non-canonical object key. This is a bug: a document's "
                            + "storage key must be stamped before any link is issued for it.");
        }
    }
}
