package com.juriscore.app.email;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Email wiring, bound from {@code juriscore.email.*}.
 *
 * <p>Separate from {@code AwsProperties} for the reason {@code DocumentProperties} is
 * separate from it: which mailbox a platform sends from is a fact about this feature, and
 * it survives replacing SES with anything else.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "juriscore.email")
public class EmailProperties {

    /** {@code ses} (default) or {@code recording} — see {@link RecordingEmailSender}. */
    private String provider = "ses";

    /**
     * The verified mailbox every message is sent from.
     *
     * <p>No default, and deliberately not a startup requirement — the same decision the
     * legal-research API key records. An unset address does not stop the application
     * serving matters, hearings and invoices; it refuses invoice email, loudly, until
     * somebody sets it. Failing startup instead would take a deployed platform down over
     * a feature nobody had asked it to perform yet.
     *
     * <p>It must be an identity verified with the provider. The firm's own address goes in
     * the reply-to header instead — see {@code EmailMessage}.
     */
    private String fromAddress;
}
