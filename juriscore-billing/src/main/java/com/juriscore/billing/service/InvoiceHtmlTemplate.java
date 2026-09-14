package com.juriscore.billing.service;

import com.juriscore.billing.domain.BillingProfile;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceLineItem;
import com.juriscore.casework.domain.Client;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.organization.domain.Organization;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Builds the XHTML {@link InvoicePdfService} renders to PDF.
 *
 * <p>A pure function of its arguments — no I/O, no clock read beyond what is already on
 * the invoice — kept in its own class precisely so it can be unit-tested as a string
 * builder, without touching a PDF library, a database or a byte of image data. See
 * {@code InvoiceHtmlTemplateTest}.
 *
 * <p>Output must be well-formed XML: {@link InvoicePdfService} hands this string straight
 * to OpenHTMLtoPDF's XHTML parser, which is stricter than a browser's — every attribute is
 * quoted, every element that a browser would let self-close is written that way here, and
 * every piece of firm/client/invoice text is escaped through {@link #esc(String)} before
 * it reaches the markup. An unescaped {@code &} in a client's name would otherwise be a
 * rendering failure, not a cosmetic one.
 */
final class InvoiceHtmlTemplate {

    private InvoiceHtmlTemplate() {
    }

    static String render(Invoice invoice, BillingProfile profile, Organization organization,
                         Client client, LegalCase legalCase, BigDecimal amountPaid) {
        String currency = invoice.getCurrency();
        BigDecimal amountDue = invoice.getTotalAmount().subtract(amountPaid);

        StringBuilder html = new StringBuilder(4096);
        html.append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>");
        html.append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />");
        html.append("<title>Invoice ").append(esc(invoice.getInvoiceNumber())).append("</title>");
        html.append("<style>").append(CSS).append("</style>");
        html.append("</head><body>");

        renderHeader(html, invoice, profile, organization);
        html.append("<hr class=\"rule\" />");
        renderMetaGrid(html, invoice, client, legalCase);
        renderLines(html, invoice, currency);
        renderTotals(html, invoice, amountPaid, amountDue, currency);
        renderNotes(html, invoice);

        html.append("</body></html>");
        return html.toString();
    }

    // --------------------------------------------------------------------------- sections

    private static void renderHeader(StringBuilder html, Invoice invoice, BillingProfile profile,
                                     Organization organization) {
        String firmName = firstNonBlank(profile.getLegalName(), organization.getName());
        String taxRegistration = profile.getTaxRegistration();
        String email = firstNonBlank(profile.getBillingEmail(), organization.getContactEmail());
        String phone = firstNonBlank(profile.getBillingPhone(), organization.getContactPhone());
        String address = firmAddress(profile, organization);
        String logoUrl = profile.getLogoUrl();

        html.append("<div class=\"header\"><div class=\"firm\">");
        if (isSet(logoUrl)) {
            html.append("<img class=\"logo\" src=\"").append(escAttr(logoUrl))
                    .append("\" alt=\"\" />");
        }
        html.append("<p class=\"firm-name\">").append(esc(firmName)).append("</p>");
        if (isSet(address)) {
            html.append("<p class=\"firm-line\">").append(escMultiline(address)).append("</p>");
        }
        if (isSet(taxRegistration)) {
            html.append("<p class=\"firm-line\">Tax reg. ").append(esc(taxRegistration))
                    .append("</p>");
        }
        if (isSet(email) || isSet(phone)) {
            html.append("<p class=\"firm-line\">");
            html.append(String.join(" &#183; ",
                    listOf(isSet(email) ? esc(email) : null, isSet(phone) ? esc(phone) : null)));
            html.append("</p>");
        }
        html.append("</div>");

        html.append("<div class=\"doc-title\">");
        html.append("<h1>INVOICE</h1>");
        html.append("<p class=\"invoice-number\">").append(esc(invoice.getInvoiceNumber()))
                .append("</p>");
        html.append("<span class=\"status status-").append(invoice.getStatus().name())
                .append("\">").append(esc(humanise(invoice.getStatus().name()))).append("</span>");
        html.append("</div></div>");
    }

    private static void renderMetaGrid(StringBuilder html, Invoice invoice, Client client,
                                       LegalCase legalCase) {
        html.append("<div class=\"meta-grid\">");

        html.append("<div class=\"meta-col\">");
        html.append("<p class=\"label\">Bill to</p>");
        html.append("<p class=\"value client-name\">").append(esc(client.getDisplayName()))
                .append("</p>");
        String clientAddress = address(client.getAddressLine1(), client.getAddressLine2(),
                client.getCity(), client.getState(), client.getCountry(),
                client.getPostalCode());
        if (isSet(clientAddress)) {
            html.append("<p class=\"value muted\">").append(escMultiline(clientAddress))
                    .append("</p>");
        }
        if (isSet(client.getEmail())) {
            html.append("<p class=\"value muted\">").append(esc(client.getEmail())).append("</p>");
        }
        if (isSet(client.getPhone())) {
            html.append("<p class=\"value muted\">").append(esc(client.getPhone())).append("</p>");
        }
        html.append("</div>");

        html.append("<div class=\"meta-col\">");
        html.append(metaRow("Issue date", InvoicePdfService.date(invoice.getIssueDate())));
        html.append(metaRow("Due date", InvoicePdfService.date(invoice.getDueDate())));
        html.append(metaRow("Currency", invoice.getCurrency()));
        if (legalCase != null) {
            html.append(metaRow("Matter", legalCase.getCaseNumber()));
        }
        html.append("</div>");

        html.append("</div>");
    }

    private static String metaRow(String label, String value) {
        if (!isSet(value)) {
            return "";
        }
        return "<p class=\"label\">" + esc(label) + "</p><p class=\"value\">" + esc(value)
                + "</p>";
    }

    private static void renderLines(StringBuilder html, Invoice invoice, String currency) {
        List<InvoiceLineItem> lines = invoice.getLineItems();
        html.append("<table class=\"lines\"><thead><tr>");
        html.append("<th>Description</th><th class=\"num\">Qty</th>")
                .append("<th class=\"num\">Unit price</th><th class=\"num\">Tax</th>")
                .append("<th class=\"num\">Amount</th>");
        html.append("</tr></thead><tbody>");
        for (InvoiceLineItem line : lines) {
            html.append("<tr>");
            html.append("<td>").append(esc(line.getDescription())).append("</td>");
            html.append("<td class=\"num\">").append(InvoicePdfService.quantity(line.getQuantity()))
                    .append("</td>");
            html.append("<td class=\"num\">")
                    .append(InvoicePdfService.money(line.getUnitPrice(), currency)).append("</td>");
            html.append("<td class=\"num\">")
                    .append(line.getTaxRate() != null && line.getTaxRate().signum() > 0
                            ? InvoicePdfService.percent(line.getTaxRate()) : "&#8212;")
                    .append("</td>");
            html.append("<td class=\"num\">")
                    .append(InvoicePdfService.money(line.getAmount(), currency)).append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody></table>");
    }

    private static void renderTotals(StringBuilder html, Invoice invoice, BigDecimal amountPaid,
                                     BigDecimal amountDue, String currency) {
        html.append("<div class=\"totals\"><table>");
        html.append(totalRow("Subtotal", InvoicePdfService.money(invoice.getSubtotal(), currency), false));
        html.append(totalRow("Tax", InvoicePdfService.money(invoice.getTaxAmount(), currency), false));
        if (invoice.getDiscountAmount().signum() > 0) {
            html.append(totalRow("Discount",
                    "&#8722; " + InvoicePdfService.money(invoice.getDiscountAmount(), currency), false));
        }
        html.append(totalRow("Total", InvoicePdfService.money(invoice.getTotalAmount(), currency), true));
        if (amountPaid.signum() > 0) {
            html.append(totalRow("Paid", InvoicePdfService.money(amountPaid, currency), false));
            html.append(totalRow("Balance due", InvoicePdfService.money(amountDue, currency), true));
        }
        html.append("</table></div>");
    }

    private static String totalRow(String label, String value, boolean grand) {
        String rowClass = grand ? " class=\"grand\"" : "";
        return "<tr" + rowClass + "><td>" + esc(label) + "</td><td class=\"num\">" + value
                + "</td></tr>";
    }

    /**
     * The invoice's own notes, and only those.
     *
     * <p>Deliberately <em>not</em> falling back to {@code BillingProfile.invoiceNotes} when an
     * invoice has none. The firm's standing payment instructions already reach an invoice, by
     * the mechanism the rest of the platform uses: {@code InvoiceService.create} copies them
     * onto {@code invoice.notes} at creation unless the caller supplied its own. Reading the
     * profile again here would add a second, divergent path to the same field, and it would be
     * wrong in both directions — {@code InvoiceService.update} treats {@code notes: null} as a
     * deliberate clear, which a render-time fallback would silently undo, and an invoice issued
     * last year would start printing payment terms the firm only adopted this morning. A PDF of
     * an issued invoice is a function of that invoice, not of today's settings.
     */
    private static void renderNotes(StringBuilder html, Invoice invoice) {
        String notes = invoice.getNotes();
        if (!isSet(notes)) {
            return;
        }
        html.append("<div class=\"notes\"><p class=\"label\">Notes &amp; payment instructions</p><p>")
                .append(escMultiline(notes)).append("</p></div>");
    }

    // ---------------------------------------------------------------------------- helpers

    private static String firmAddress(BillingProfile profile, Organization organization) {
        String profileAddress = address(profile.getAddressLine1(), profile.getAddressLine2(),
                profile.getCity(), profile.getState(), profile.getCountry(),
                profile.getPostalCode());
        if (isSet(profileAddress)) {
            return profileAddress;
        }
        return address(organization.getAddressLine1(), organization.getAddressLine2(),
                organization.getCity(), organization.getState(), organization.getCountry(),
                organization.getPostalCode());
    }

    private static String address(String... parts) {
        return String.join(", ", listOf(parts));
    }

    private static List<String> listOf(String... parts) {
        return java.util.Arrays.stream(parts)
                .filter(InvoiceHtmlTemplate::isSet)
                .map(String::trim)
                .toList();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (isSet(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** {@code HEARING_SCHEDULED} to {@code Hearing scheduled} — matches the frontend's
     * {@code humanise}. Only ever applied to an enum name JurisCore itself defined. */
    private static String humanise(String enumName) {
        String lower = enumName.replace('_', ' ').toLowerCase(Locale.US);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String escAttr(String value) {
        return esc(value);
    }

    /** {@link #esc(String)}, with line breaks turned into {@code <br/>} for a multi-line
     * address or note printed inside a single paragraph. */
    private static String escMultiline(String value) {
        return esc(value).replace("\n", "<br/>");
    }

    private static final String CSS = """
            @page { size: A4; margin: 2.1cm 1.8cm; }
            * { box-sizing: border-box; }
            body { font-family: sans-serif; font-size: 10pt; color: #1c2433; margin: 0; }
            p { margin: 0 0 4px 0; }
            .header { display: table; width: 100%; margin-bottom: 14px; }
            .header .firm { display: table-cell; vertical-align: top; width: 62%; }
            .header .doc-title { display: table-cell; vertical-align: top; width: 38%; text-align: right; }
            .logo { max-height: 46px; max-width: 220px; margin-bottom: 8px; }
            .firm-name { font-size: 15pt; font-weight: bold; margin-bottom: 4px; }
            .firm-line { font-size: 8.5pt; color: #4a5468; line-height: 1.4; }
            .doc-title h1 { font-size: 19pt; margin: 0; letter-spacing: 2px; color: #1c2433; }
            .invoice-number { font-family: monospace; font-size: 11pt; margin-top: 6px; }
            .status { display: inline-block; margin-top: 8px; padding: 2px 9px; font-size: 8pt;
                      text-transform: uppercase; letter-spacing: 0.04em;
                      border: 1px solid #8a6a2f; color: #5c4720; }
            hr.rule { border: none; border-top: 1.4pt solid #1c2433; margin: 8px 0 16px 0; }
            .meta-grid { display: table; width: 100%; margin-bottom: 18px; }
            .meta-col { display: table-cell; vertical-align: top; width: 50%; padding-right: 14px; }
            .label { font-size: 7.5pt; text-transform: uppercase; letter-spacing: 0.06em;
                     color: #8a6a2f; margin-bottom: 2px; }
            .value { font-size: 10pt; margin-bottom: 9px; }
            .value.muted { font-size: 9pt; color: #4a5468; }
            .client-name { font-weight: bold; }
            table.lines { width: 100%; border-collapse: collapse; margin-bottom: 4px; }
            table.lines th { text-align: left; font-size: 7.8pt; text-transform: uppercase;
                             letter-spacing: 0.04em; color: #4a5468;
                             border-bottom: 1.2pt solid #1c2433; padding: 5px 6px; }
            table.lines td { font-size: 9.3pt; padding: 6px; border-bottom: 0.75pt solid #dcdfe6; }
            .num { text-align: right; }
            .totals { width: 46%; margin-left: 54%; margin-top: 10px; }
            .totals table { width: 100%; border-collapse: collapse; }
            .totals td { padding: 3px 0; font-size: 9.5pt; }
            .totals tr.grand td { font-weight: bold; font-size: 11pt;
                                  border-top: 1.2pt solid #1c2433; padding-top: 6px; }
            .notes { margin-top: 22px; padding-top: 10px; border-top: 0.75pt solid #dcdfe6;
                     font-size: 8.8pt; color: #333333; }
            """;
}
