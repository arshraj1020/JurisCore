package com.juriscore.app.email;

import com.juriscore.common.email.EmailAttachment;
import com.juriscore.common.email.EmailDeliveryException;
import com.juriscore.common.email.EmailMessage;
import jakarta.activation.DataHandler;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Turns an {@link EmailMessage} into the RFC 5322 bytes a provider will accept.
 *
 * <h2>Why a library and not string concatenation</h2>
 *
 * <p>MIME looks like a text format and is not one. Header folding, {@code =?UTF-8?B?}
 * encoding for a firm called <em>Iyer &amp; Co</em> or a client called
 * <em>Nguyễn</em>, base64 in 76-character lines, boundary strings that must not occur in
 * the payload, CRLF everywhere — each is a small rule and each one produces a message that
 * renders as garbage, or is silently dropped by a spam filter, when it is broken. Jakarta
 * Mail has implemented all of them for two decades. Hand-rolling this to save a dependency
 * would be trading a known-good implementation for a bug a firm discovers in front of a
 * client.
 *
 * <p>Nothing here touches the network. Jakarta Mail's transport is not used, not
 * configured and not wanted: the {@link Session} exists only because {@link MimeMessage}
 * requires one to construct, and the result is a byte array handed to
 * {@link SesEmailSender}. That is the whole reason this class is separate — assembling a
 * message is pure, deterministic and unit-testable, and sending it is neither.
 */
final class MimeEmailAssembler {

    /** Long enough for any real firm name, short enough to keep the header readable. */
    private static final int MAX_DISPLAY_NAME = 78;

    private MimeEmailAssembler() {
    }

    /**
     * @param fromAddress the verified mailbox; the display name comes from the message
     * @return the encoded message, ready to hand to a provider as raw content
     */
    static byte[] assemble(EmailMessage message, String fromAddress) {
        try {
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));

            mime.setFrom(address(fromAddress, senderName(message.fromDisplayName(), fromAddress)));
            mime.setRecipient(Message.RecipientType.TO,
                    address(message.toAddress(), safeName(message.toName())));
            if (message.replyTo() != null && !message.replyTo().isBlank()) {
                // The firm's own mailbox, so its own name stands alone here: a reply is
                // going to the firm, not through this platform.
                mime.setReplyTo(new Address[] {
                        address(message.replyTo(), safeName(message.fromDisplayName()))});
            }
            mime.setSubject(message.subject(), StandardCharsets.UTF_8.name());

            MimeBodyPart body = new MimeBodyPart();
            body.setText(message.textBody(), StandardCharsets.UTF_8.name());

            if (!message.hasAttachment()) {
                mime.setContent(body.getContent(), body.getContentType());
            } else {
                MimeMultipart multipart = new MimeMultipart("mixed");
                multipart.addBodyPart(body);
                multipart.addBodyPart(attachmentPart(message.attachment()));
                mime.setContent(multipart);
            }

            mime.saveChanges();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            mime.writeTo(out);
            return out.toByteArray();
        } catch (MessagingException | IOException e) {
            // Not a provider failure: this is our own message that could not be built, so
            // it is reported with its own reason code rather than looking like SES said no.
            throw new EmailDeliveryException("MESSAGE_ASSEMBLY_FAILED",
                    "Could not assemble the MIME message", e);
        }
    }

    private static MimeBodyPart attachmentPart(EmailAttachment attachment)
            throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(
                new ByteArrayDataSource(attachment.content(), attachment.contentType())));
        part.setFileName(attachment.fileName());
        part.setDisposition(Part.ATTACHMENT);
        return part;
    }

    /**
     * The sender name a recipient sees: the firm's name, then the domain it was sent from.
     *
     * <h2>Why the domain is appended</h2>
     *
     * <p>Every firm on this platform sends from one verified mailbox, and the display name
     * beside it is tenant-supplied. Without this, a firm could set its legal name to
     * "HDFC Bank Ltd" and the platform would send DKIM-signed mail, from a domain it owns,
     * carrying an attachment, apparently from a bank — a phishing primitive, and the
     * fastest way to get the sending domain blocked for every other firm on it.
     *
     * <p>"Name via domain" is the convention mail clients already display for exactly this
     * situation, and it keeps the firm's identity front and centre while making it plain
     * that the message came through a platform rather than from the named organisation's
     * own systems. A firm that wants unadorned sender identity needs its own verified
     * sending identity, which is a deliberate future step, not a configuration flag.
     */
    static String senderName(String firmName, String fromAddress) {
        String domain = domainOf(fromAddress);
        String name = safeName(firmName);
        if (name.isEmpty()) {
            return domain;
        }
        return domain.isEmpty() ? name : name + " via " + domain;
    }

    /**
     * A display name that cannot pretend to be something else.
     *
     * <p>Removes the characters that let a name read as an address or restructure the
     * header — {@code @}, angle brackets, quotes, backslashes — and anything that could
     * break the header apart, which is CR and LF above all: a name carrying a newline is
     * header injection, and this is the boundary where that has to stop. Commas and
     * ampersands survive, because "Sharma, Iyer &amp; Co" is a firm name and Jakarta Mail
     * quotes it correctly.
     */
    static String safeName(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            // Replaced with a space rather than deleted: dropping them silently welds two
            // words together ("support@bank.example" -> "supportbank.example"), which is
            // less legible and less obviously neutralised than leaving the gap.
            boolean unsafe = c == '@' || c == '<' || c == '>' || c == '"' || c == '\\'
                    // Control characters, including the CR and LF that would split the header.
                    || Character.isISOControl(c);
            cleaned.append(unsafe ? ' ' : c);
        }
        String collapsed = cleaned.toString().replaceAll("\\s+", " ").trim();
        return collapsed.length() <= MAX_DISPLAY_NAME
                ? collapsed
                : collapsed.substring(0, MAX_DISPLAY_NAME).trim();
    }

    private static String domainOf(String emailAddress) {
        if (emailAddress == null) {
            return "";
        }
        int at = emailAddress.lastIndexOf('@');
        return at < 0 ? "" : emailAddress.substring(at + 1).trim();
    }

    /** Encodes a non-ASCII display name rather than letting it corrupt the header. */
    private static InternetAddress address(String email, String displayName)
            throws MessagingException {
        try {
            InternetAddress address = displayName == null || displayName.isBlank()
                    ? new InternetAddress(email)
                    : new InternetAddress(email, displayName, StandardCharsets.UTF_8.name());
            address.validate();
            return address;
        } catch (java.io.UnsupportedEncodingException e) {
            throw new MessagingException("UTF-8 is unsupported, which cannot happen", e);
        }
    }
}
