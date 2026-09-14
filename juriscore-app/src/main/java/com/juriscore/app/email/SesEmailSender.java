package com.juriscore.app.email;

import com.juriscore.common.email.EmailDeliveryException;
import com.juriscore.common.email.EmailMessage;
import com.juriscore.common.email.EmailSender;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.RawMessage;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

/**
 * The real thing: Amazon SES v2, through the same SDK and credentials {@code AwsConfig}
 * already builds for S3 and SQS.
 *
 * <p>SES rather than a new provider because the dependency, the credential chain, the
 * region and the LocalStack endpoint override all exist already — adding it is one client
 * bean and one artifact from a BOM that is imported, where anything else would be a second
 * vendor account, a second secret to rotate and a second failure mode to learn. It also
 * bills per message with no floor, which is the right shape for a platform whose firms send
 * a handful of invoices a week.
 *
 * <h2>Raw messages, because of the attachment</h2>
 *
 * <p>SES's simple content API takes a subject and a body and cannot carry a file, so an
 * invoice PDF requires {@code RawMessage} — the whole MIME document, assembled by
 * {@link MimeEmailAssembler} and handed over as bytes. Headers in that document decide the
 * envelope: no {@code Destination} is set on the request, so the {@code To} header is the
 * single source of truth for where this goes.
 *
 * <h2>Unconfigured is a refusal, not a pretence</h2>
 *
 * <p>Two things can be missing: the SES client (when {@code juriscore.aws.enabled=false})
 * and the verified from-address. Either way this bean still loads and still refuses —
 * {@link EmailDeliveryException} with {@code NOT_CONFIGURED} — rather than failing startup
 * or, far worse, reporting success. A platform that cannot send email should keep serving
 * matters and hearings; it should never tell a firm an invoice was delivered.
 *
 * <p>Nothing here logs the message body or the attachment. An invoice is a client
 * confidence, and a log aggregator is not where it belongs.
 */
@Component
@ConditionalOnProperty(prefix = "juriscore.email", name = "provider", havingValue = "ses",
        matchIfMissing = true)
public class SesEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);

    private final ObjectProvider<SesV2Client> clientProvider;
    private final EmailProperties properties;

    public SesEmailSender(ObjectProvider<SesV2Client> clientProvider, EmailProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
    }

    /**
     * Says at startup what the application will do when somebody presses the button, so the
     * answer is in the log before the first firm discovers it.
     */
    @PostConstruct
    void announce() {
        if (unavailableReason() != null) {
            log.warn("Email delivery is NOT configured ({}). Invoice email will be refused "
                    + "until juriscore.email.from-address is set and AWS is enabled.",
                    unavailableReason());
        } else {
            log.info("Email delivery via Amazon SES, sending as {}", properties.getFromAddress());
        }
    }

    @Override
    public SentEmail send(EmailMessage message) {
        String unavailable = unavailableReason();
        if (unavailable != null) {
            throw new EmailDeliveryException(EmailDeliveryException.NOT_CONFIGURED,
                    "Email delivery is not configured: " + unavailable);
        }

        byte[] mime = MimeEmailAssembler.assemble(message, properties.getFromAddress());
        SendEmailRequest request = SendEmailRequest.builder()
                .content(EmailContent.builder()
                        .raw(RawMessage.builder().data(SdkBytes.fromByteArray(mime)).build())
                        .build())
                .build();

        try {
            SendEmailResponse response = clientProvider.getObject().sendEmail(request);
            log.info("SES accepted message {} ({} bytes) for delivery",
                    response.messageId(), mime.length);
            return new SentEmail(response.messageId());
        } catch (AwsServiceException e) {
            // errorCode() is a short token from SES — MessageRejected,
            // MailFromDomainNotVerified, AccountSendingPaused. Safe to carry onto an event;
            // the full response stays in this log line.
            String code = e.awsErrorDetails() == null ? "SES_ERROR"
                    : e.awsErrorDetails().errorCode();
            log.error("SES refused the message: {}", code, e);
            throw new EmailDeliveryException(code, "SES refused the message", e);
        } catch (SdkException e) {
            log.error("SES could not be reached", e);
            throw new EmailDeliveryException("PROVIDER_UNREACHABLE", "SES could not be reached", e);
        }
    }

    /** Null when email can actually be sent; otherwise why it cannot, for logs only. */
    private String unavailableReason() {
        if (properties.getFromAddress() == null || properties.getFromAddress().isBlank()) {
            return "no juriscore.email.from-address is set";
        }
        if (clientProvider.getIfAvailable() == null) {
            return "no SES client — juriscore.aws.enabled is false";
        }
        return null;
    }
}
