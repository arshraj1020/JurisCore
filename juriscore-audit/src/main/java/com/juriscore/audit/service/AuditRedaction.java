package com.juriscore.audit.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The one place that decides what may appear in an audit summary.
 *
 * <p>An audit trail is written by listeners reacting to events from every module, and a
 * summary is assembled from whatever those events carry. That is a wide surface, and the
 * failure mode is quiet: a token or a signed URL that ends up in a summary sits in a table
 * a firm administrator can page through, is copied into every backup, and nobody notices
 * until it is read back.
 *
 * <p>So this is a belt to the braces of "do not put secrets on events in the first place".
 * The producers already omit them — {@code PaymentRecordedEvent} carries no reference,
 * {@code PasswordResetRequestedEvent} is never mapped to an audit row at all, and no
 * document event carries a presigned URL. This class assumes one day one of them will
 * change and refuses to write the result.
 *
 * <p>It fails loudly rather than silently scrubbing. A summary that tripped a rule is a bug
 * in whatever built it, and replacing the offending substring would hide that bug while
 * still recording a row nobody can trust.
 */
public final class AuditRedaction {

    /** Longest summary the column takes. Anything longer is a summary that became a dump. */
    public static final int MAX_SUMMARY = 500;

    private static final List<Pattern> FORBIDDEN = List.of(
            // Anything that looks like a URL with a signature on it: presigned S3 links,
            // and by extension any signed callback.
            Pattern.compile("(?i)\\bX-Amz-Signature\\b"),
            Pattern.compile("(?i)\\bX-Amz-Credential\\b"),
            Pattern.compile("(?i)\\bSignature=\\S"),
            // A JWT, in any of the three positions it turns up.
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            Pattern.compile("(?i)\\bBearer\\s+\\S{8,}"),
            // AWS keys.
            Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
            Pattern.compile("(?i)\\baws_secret_access_key\\b"),
            // Anything labelled as a credential.
            Pattern.compile("(?i)\\b(?:password|passwd|secret|api[_-]?key|access[_-]?token"
                    + "|refresh[_-]?token|authorization)\\s*[:=]"),
            // A bare object key from the documents module.
            Pattern.compile("\\borganizations/[0-9a-fA-F-]{36}/cases/"),
            // Card-shaped digit runs: 13–19 digits, optionally grouped.
            Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"));

    private AuditRedaction() {
    }

    public static boolean isSafe(String summary) {
        if (summary == null) {
            return false;
        }
        for (Pattern pattern : FORBIDDEN) {
            if (pattern.matcher(summary).find()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @throws IllegalArgumentException when the summary carries something that must never
     *                                  be stored. A programming error in whatever built it,
     *                                  not a user error, so it is not an {@code ApiException}.
     */
    public static String require(String summary) {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("An audit record needs a summary");
        }
        if (!isSafe(summary)) {
            throw new IllegalArgumentException(
                    "Refusing to write an audit summary that appears to contain a credential, "
                            + "a signed URL or a card-shaped number. This is a bug in the "
                            + "listener that built it.");
        }
        return summary.length() <= MAX_SUMMARY ? summary : summary.substring(0, MAX_SUMMARY);
    }
}
