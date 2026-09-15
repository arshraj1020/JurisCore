package com.juriscore.app.email;

import com.juriscore.common.email.EmailDeliveryException;
import com.juriscore.common.email.EmailMessage;
import com.juriscore.common.email.EmailSender;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stand-in for SES that keeps messages in a list and sends nothing at all.
 *
 * <p>Sibling of {@code InMemoryObjectStorageService}, active for exactly the same reason:
 * the whole suite — and a fresh clone with no AWS account — runs the real invoice-email
 * rules end to end, against a double that captures what would have gone out. Selected by
 * {@code juriscore.email.provider=recording}, which the test profile sets.
 *
 * <p>It is emphatically <strong>not</strong> a delivery mechanism, and it shouts at WARN on
 * startup, because a component that quietly swallows a firm's invoices while reporting
 * success is the worst thing to find in a deployed environment. {@code application.yml}
 * leaves the provider at {@code ses}, so this cannot load anywhere by accident.
 *
 * <h2>{@link #failNextSend} is what makes the failure path testable</h2>
 *
 * <p>Provider failures are the branch that matters most here — an invoice must never be
 * recorded as emailed when nothing arrived — and it is unreachable through a mock of the
 * port, because a mock would also replace the service logic under test. Arming a real
 * adapter to refuse the next message exercises {@code InvoiceEmailService}'s recovery for
 * real: the same catch, the same failure record, the same audit event.
 */
@Component
@ConditionalOnProperty(prefix = "juriscore.email", name = "provider", havingValue = "recording")
public class RecordingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(RecordingEmailSender.class);

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> failNextReason = new AtomicReference<>();

    @PostConstruct
    void warnLoudly() {
        log.warn("RecordingEmailSender is active: NO EMAIL WILL BE DELIVERED. "
                + "Set juriscore.email.provider=ses for a deployment that must send.");
    }

    @Override
    public SentEmail send(EmailMessage message) {
        String reason = failNextReason.getAndSet(null);
        if (reason != null) {
            throw new EmailDeliveryException(reason, "RecordingEmailSender was armed to fail");
        }
        sent.add(message);
        log.info("Recorded (not sent) an email to {} with subject '{}'",
                message.toAddress(), message.subject());
        return new SentEmail("recorded-" + UUID.randomUUID());
    }

    // ------------------------------------------------------------------ test affordances

    /** The next {@link #send} throws with this reason code, once. */
    public void failNextSend(String reasonCode) {
        failNextReason.set(reasonCode);
    }

    public List<EmailMessage> sentMessages() {
        return List.copyOf(sent);
    }

    public Optional<EmailMessage> lastMessage() {
        return sent.isEmpty() ? Optional.empty() : Optional.of(sent.get(sent.size() - 1));
    }

    /** Between tests, so one test's messages are not another's fixture. */
    public void clear() {
        sent.clear();
        failNextReason.set(null);
    }
}
