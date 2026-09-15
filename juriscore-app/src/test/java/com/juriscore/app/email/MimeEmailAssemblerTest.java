package com.juriscore.app.email;

import com.juriscore.common.email.EmailAttachment;
import com.juriscore.common.email.EmailMessage;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MIME document SES is handed.
 *
 * <p>Asserted by parsing the bytes back rather than by matching strings against them: the
 * failures worth catching here — a display name that corrupts a header, an attachment that
 * arrives with the wrong name, a body that loses its encoding — all look fine in a raw
 * string and only show up once something reads the message the way a mail client does.
 */
class MimeEmailAssemblerTest {

    private static final String FROM = "billing@juriscore.test";

    private static MimeMessage parse(byte[] mime) throws Exception {
        try (InputStream in = new ByteArrayInputStream(mime)) {
            return new MimeMessage(Session.getInstance(new Properties()), in);
        }
    }

    private EmailMessage message(EmailAttachment attachment) {
        return new EmailMessage("Sharma & Associates LLP", "accounts@sharma-legal.test",
                "asha@menon.test", "Asha Menon",
                "Invoice INV-2026-000001 from Sharma & Associates LLP",
                "Dear Asha Menon,\n\nPlease find attached invoice INV-2026-000001.\n",
                attachment);
    }

    @Test
    @DisplayName("the firm is the sender and the firm's mailbox takes the replies")
    void writesTheHeadersAClientWillSee() throws Exception {
        byte[] mime = MimeEmailAssembler.assemble(message(pdf()), FROM);
        MimeMessage parsed = parse(mime);

        InternetAddress from = (InternetAddress) parsed.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo(FROM);
        assertThat(from.getPersonal()).isEqualTo("Sharma & Associates LLP via juriscore.test");

        InternetAddress to = (InternetAddress) parsed.getRecipients(Message.RecipientType.TO)[0];
        assertThat(to.getAddress()).isEqualTo("asha@menon.test");
        assertThat(to.getPersonal()).isEqualTo("Asha Menon");

        assertThat(((InternetAddress) parsed.getReplyTo()[0]).getAddress())
                .isEqualTo("accounts@sharma-legal.test");
        assertThat(parsed.getSubject())
                .isEqualTo("Invoice INV-2026-000001 from Sharma & Associates LLP");
    }

    @Test
    void attachesThePdfUnderItsOwnName() throws Exception {
        byte[] content = "%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8);
        byte[] mime = MimeEmailAssembler.assemble(
                message(new EmailAttachment("invoice-INV-2026-000001.pdf", "application/pdf",
                        content)), FROM);

        MimeMultipart multipart = (MimeMultipart) parse(mime).getContent();
        assertThat(multipart.getCount()).isEqualTo(2);

        assertThat(multipart.getBodyPart(0).getContentType()).contains("text/plain");
        assertThat((String) multipart.getBodyPart(0).getContent())
                .contains("Please find attached invoice INV-2026-000001.");

        var attachment = multipart.getBodyPart(1);
        assertThat(attachment.getFileName()).isEqualTo("invoice-INV-2026-000001.pdf");
        assertThat(attachment.getContentType()).contains("application/pdf");
        assertThat(attachment.getDisposition()).isEqualToIgnoringCase("attachment");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        attachment.getDataHandler().writeTo(out);
        // Base64 in transit, the same bytes back out: the PDF a client opens is the PDF
        // the renderer produced.
        assertThat(out.toByteArray()).isEqualTo(content);
    }

    @Test
    @DisplayName("a non-ASCII firm or client name survives the header encoding")
    void encodesNonAsciiNames() throws Exception {
        EmailMessage message = new EmailMessage("Nguyễn & Associés", null,
                "khach@example.test", "Trần Minh", "Hóa đơn INV-2026-000001", "Xin chào,\n", pdf());

        MimeMessage parsed = parse(MimeEmailAssembler.assemble(message, FROM));

        assertThat(((InternetAddress) parsed.getFrom()[0]).getPersonal())
                .isEqualTo("Nguyễn & Associés via juriscore.test");
        assertThat(((InternetAddress) parsed.getRecipients(Message.RecipientType.TO)[0])
                .getPersonal()).isEqualTo("Trần Minh");
        assertThat(parsed.getSubject()).isEqualTo("Hóa đơn INV-2026-000001");
    }

    @Test
    void sendsAPlainMessageWhenThereIsNothingToAttach() throws Exception {
        MimeMessage parsed = parse(MimeEmailAssembler.assemble(message(null), FROM));

        assertThat(parsed.getContentType()).contains("text/plain");
        assertThat((String) parsed.getContent()).contains("Dear Asha Menon,");
    }

    @Test
    void leavesReplyToOffWhenTheFirmHasNoAddressForIt() throws Exception {
        EmailMessage message = new EmailMessage("Menon Legal LLP", null, "asha@menon.test",
                "Asha Menon", "Invoice INV-2026-000002 from Menon Legal LLP", "Dear Asha,\n", null);

        MimeMessage parsed = parse(MimeEmailAssembler.assemble(message, FROM));

        // Jakarta Mail answers the From address when no Reply-To header is present, which
        // is the RFC's rule and the behaviour a mail client implements.
        assertThat(((InternetAddress) parsed.getReplyTo()[0]).getAddress()).isEqualTo(FROM);
    }

    @Test
    @DisplayName("a firm cannot pass itself off as somebody else's mailbox")
    void neutralisesADisplayNameThatImitatesAnAddress() throws Exception {
        // legalName is tenant-supplied and every firm sends from the same verified mailbox,
        // so without this a firm could send DKIM-signed mail from this domain that reads as
        // if it came from a bank.
        EmailMessage message = new EmailMessage("HDFC Bank Ltd <support@hdfcbank.example>",
                null, "asha@menon.test", "Asha Menon", "Invoice", "Dear Asha,\n", null);

        InternetAddress from = (InternetAddress) parse(
                MimeEmailAssembler.assemble(message, FROM)).getFrom()[0];

        assertThat(from.getAddress()).isEqualTo(FROM);
        assertThat(from.getPersonal()).isEqualTo("HDFC Bank Ltd support hdfcbank.example via juriscore.test");
        assertThat(from.getPersonal()).doesNotContain("@", "<", ">");
    }

    @Test
    @DisplayName("a newline in a firm name cannot split the header")
    void refusesHeaderInjectionThroughTheDisplayName() throws Exception {
        EmailMessage message = new EmailMessage("Sharma\r\nBcc: everyone@example.test", null,
                "asha@menon.test", "Asha Menon", "Invoice", "Dear Asha,\n", null);

        MimeMessage parsed = parse(MimeEmailAssembler.assemble(message, FROM));

        assertThat(parsed.getHeader("Bcc")).isNull();
        assertThat(((InternetAddress) parsed.getFrom()[0]).getPersonal())
                .isEqualTo("Sharma Bcc: everyone example.test via juriscore.test");
    }

    @Test
    void capsARidiculouslyLongFirmName() throws Exception {
        EmailMessage message = new EmailMessage("A".repeat(200), null, "asha@menon.test",
                "Asha Menon", "Invoice", "Dear Asha,\n", null);

        String personal = ((InternetAddress) parse(MimeEmailAssembler.assemble(message, FROM))
                .getFrom()[0]).getPersonal();

        assertThat(personal).isEqualTo("A".repeat(78) + " via juriscore.test");
    }

    @Test
    @DisplayName("replies still carry the firm's own name, unqualified")
    void doesNotQualifyTheReplyToName() throws Exception {
        EmailMessage message = new EmailMessage("Sharma & Associates LLP",
                "accounts@sharma-legal.test", "asha@menon.test", "Asha Menon", "Invoice",
                "Dear Asha,\n", null);

        InternetAddress replyTo = (InternetAddress) parse(
                MimeEmailAssembler.assemble(message, FROM)).getReplyTo()[0];

        assertThat(replyTo.getAddress()).isEqualTo("accounts@sharma-legal.test");
        assertThat(replyTo.getPersonal()).isEqualTo("Sharma & Associates LLP");
    }

    private EmailAttachment pdf() {
        return new EmailAttachment("invoice.pdf", "application/pdf",
                "%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8));
    }
}
