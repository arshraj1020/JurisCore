package com.juriscore.common.email;

import java.util.Objects;

/**
 * One message, described in the domain's terms rather than the provider's.
 *
 * <h2>Why there is no from-address here</h2>
 *
 * <p>Only {@link #fromDisplayName}. The mailbox a message is actually sent from is a
 * deployment fact — a domain somebody verified with the provider — and a firm's invoice
 * has no business choosing it. A multi-tenant platform cannot send as
 * {@code accounts@each-firm-in-the-world.example} without each of those domains being
 * verified, and a provider that allowed it would be a provider nobody could trust.
 *
 * <p>So the adapter supplies the address and this record supplies the name beside it: the
 * client sees <em>Sharma &amp; Associates LLP</em> as the sender, {@link #replyTo} points
 * at the firm's own billing mailbox, and replies reach the firm rather than the platform.
 * That is the arrangement every invoicing product of this shape uses, and it is the only
 * one that both authenticates and reads correctly in a client's inbox.
 *
 * @param fromDisplayName the name shown as the sender — the firm, never this product
 * @param replyTo         where replies should go, or null to leave it to the adapter
 * @param toAddress       the recipient; validated by the caller, not by this record
 * @param toName          a display name for the recipient, or null
 * @param subject         a single line, already assembled
 * @param textBody        plain text. Deliberately not HTML: the document being sent is the
 *                        attachment, and a plain-text covering note renders identically in
 *                        every client, survives every filter, and cannot leak a tracking
 *                        pixel into a solicitor's correspondence
 * @param attachment      the file to attach, or null for a message with none
 */
public record EmailMessage(
        String fromDisplayName,
        String replyTo,
        String toAddress,
        String toName,
        String subject,
        String textBody,
        EmailAttachment attachment) {

    public EmailMessage {
        Objects.requireNonNull(toAddress, "toAddress");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(textBody, "textBody");
    }

    public boolean hasAttachment() {
        return attachment != null;
    }
}
