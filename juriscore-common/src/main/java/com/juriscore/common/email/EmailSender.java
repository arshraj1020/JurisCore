package com.juriscore.common.email;

/**
 * Outbound port for transactional email, in the same spirit as {@code ObjectStorageService}
 * and {@code EventPublisher}.
 *
 * <p>It lives in {@code common} for the reason the storage port already records: the SDK
 * clients and their credentials are configured in {@code juriscore-app}, and a module
 * cannot depend on the application that assembles it. So the port is declared here, the
 * adapters live beside {@code AwsConfig}, and {@code juriscore-billing} depends on neither
 * SES nor the app.
 *
 * <p>Nothing in this interface names a provider, a protocol or a MIME type. A caller says
 * who the message is from, who it is for, what it says and what is attached;
 * <em>how</em> that becomes bytes on a wire — the MIME assembly, the verified sending
 * identity, the retry policy — belongs entirely to the adapter. That seam is what keeps a
 * firm's invoice email out of the business of knowing what SES is.
 *
 * <h2>Failure is loud, never silent</h2>
 *
 * <p>An implementation either delivers the message to its provider or throws
 * {@link EmailDeliveryException}. There is no "best effort" return value and no boolean:
 * the one thing a billing system must never do is tell a firm an invoice reached its
 * client when it did not, so the only way to report success is to return normally.
 */
public interface EmailSender {

    /**
     * Hands one message to the provider.
     *
     * <p>Synchronous on purpose. This platform has no job runner, and inventing one for a
     * single button would be a larger change than the feature it serves — so the caller
     * waits, and the person who pressed the button learns the outcome rather than being
     * told "queued" and finding out later. See {@code InvoiceEmailService} for how that
     * outcome is recorded either way.
     *
     * @return what the provider said about the message it accepted
     * @throws EmailDeliveryException when the provider refused it, was unreachable, or is
     *                                not configured at all
     */
    SentEmail send(EmailMessage message);

    /**
     * The provider's receipt.
     *
     * <p>{@code providerMessageId} is worth keeping out of the domain and in the log: it
     * identifies the message inside the provider's own systems, which is exactly what an
     * operator needs when a firm asks "did that actually go out" and exactly what no
     * client of this API needs.
     */
    record SentEmail(String providerMessageId) {
    }
}
