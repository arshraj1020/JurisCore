package com.juriscore.common.email;

/**
 * The provider did not accept the message.
 *
 * <h2>Two messages, and the difference matters</h2>
 *
 * <p>{@link #reasonCode()} is a short, safe token — {@code MessageRejected},
 * {@code NOT_CONFIGURED}, {@code TIMEOUT} — chosen by the adapter and fit to travel: it
 * goes on a domain event and from there into an audit summary a firm administrator can
 * read. {@link #getMessage()} and the cause are for the log, and may carry whatever the
 * provider said, including identifiers and endpoints that have no business crossing an
 * API boundary.
 *
 * <p>Callers must translate this into an {@code ApiException} with their own wording
 * rather than passing the provider's text through — {@code GlobalExceptionHandler} sends
 * an {@code ApiException}'s message straight to the client, so anything put there is
 * published.
 */
public class EmailDeliveryException extends RuntimeException {

    /** Used when email has not been configured for this deployment at all. */
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";

    private final String reasonCode;

    public EmailDeliveryException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public EmailDeliveryException(String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
    }

    /** Short and safe to record. Never the provider's full response. */
    public String reasonCode() {
        return reasonCode;
    }
}
