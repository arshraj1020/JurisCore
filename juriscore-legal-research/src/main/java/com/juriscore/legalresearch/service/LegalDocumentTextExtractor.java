package com.juriscore.legalresearch.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts structured text from uploaded judgment documents.
 *
 * <p>Two extraction paths:
 * <ol>
 *   <li><b>PDF</b> — handled directly via the PDFBox 2.x API ({@link PDDocument} +
 *       {@link PDFTextStripper}). Tika's own {@code tika-parser-pdf-module} is deliberately
 *       excluded from the classpath because it would pull PDFBox 3.x, which conflicts with
 *       OpenHTMLtoPDF 1.0.10 (invoice rendering) that requires PDFBox 2.x.</li>
 *   <li><b>Everything else</b> — routed through Tika's {@link AutoDetectParser} with
 *       {@link ToXMLContentHandler}, covering Office (DOC/DOCX, XLS/XLSX, PPT/PPTX, ODF),
 *       plain text, RTF, HTML, and images.</li>
 * </ol>
 *
 * <p>The PDF path produces a flat paragraph list with page numbers derived from
 * PDFTextStripper's per-page output. The Tika path produces structured XHTML that
 * {@link #fromXhtml} walks to extract the same paragraph/page structure.
 */
@Component
public class LegalDocumentTextExtractor {

    public record ExtractedParagraph(int paragraphNumber, Integer pageNumber, String text,
                                      int charStart, int charEnd) {}

    public record ExtractionResult(String fullText, List<ExtractedParagraph> paragraphs, Integer pageCount) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public ExtractionResult extract(byte[] content, String contentType) throws IOException, TikaException {
        if (isPdf(contentType, content)) {
            return extractFromPdf(content);
        }
        return extractViaTika(content, contentType);
    }

    // -------------------------------------------------------------------------
    // PDF path — PDFBox 2.x
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the content should be handled by the PDFBox path.
     *
     * <p>The MIME type is checked first because it is always cheapest. When the caller
     * supplies no type (or an unhelpful "application/octet-stream"), the first four bytes
     * of the content are inspected for the {@code %PDF} magic bytes, matching what Tika's
     * own detector does internally.
     */
    private boolean isPdf(String contentType, byte[] content) {
        if (contentType != null && !contentType.isBlank()) {
            String type = contentType.split(";")[0].trim().toLowerCase();
            if ("application/pdf".equals(type) || "application/x-pdf".equals(type)) {
                return true;
            }
            // Non-PDF MIME type explicitly provided — trust it and use the Tika path.
            if (!"application/octet-stream".equals(type)) {
                return false;
            }
        }
        // No type or generic binary: fall back to magic-byte detection.
        return content != null && content.length >= 4
                && content[0] == '%' && content[1] == 'P'
                && content[2] == 'D' && content[3] == 'F';
    }

    /**
     * Extracts text from a PDF using PDFBox 2.x, page by page.
     *
     * <p>Each non-blank page is split into paragraphs on double-newline boundaries.
     * Single newlines within a paragraph are collapsed to a space to reconstruct
     * reflowed lines, matching the behaviour of the Tika XHTML path.
     *
     * @throws IOException on I/O failure or on a password-protected PDF
     *         ({@link InvalidPasswordException} extends {@link IOException})
     */
    private ExtractionResult extractFromPdf(byte[] content) throws IOException {
        try (PDDocument doc = PDDocument.load(content)) {
            int pageCount = doc.getNumberOfPages();
            List<ExtractedParagraph> paragraphs = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();
            int paragraphNumber = 1;

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(doc);
                if (pageText == null || pageText.isBlank()) {
                    continue;
                }

                // Split on blank lines (paragraph boundary).
                String[] blocks = pageText.split("\\n{2,}");
                for (String block : blocks) {
                    // Collapse single newlines (reflowed lines) to a space.
                    String text = collapseWhitespace(block.replace('\n', ' '));
                    if (text.isEmpty()) {
                        continue;
                    }
                    int charStart = fullText.length();
                    fullText.append(text).append('\n');
                    int charEnd = fullText.length() - 1;
                    paragraphs.add(new ExtractedParagraph(paragraphNumber++, page, text, charStart, charEnd));
                }
            }

            return new ExtractionResult(fullText.toString(), paragraphs, pageCount);
        }
    }

    // -------------------------------------------------------------------------
    // Non-PDF path — Tika AutoDetectParser → XHTML → paragraph walking
    // -------------------------------------------------------------------------

    private ExtractionResult extractViaTika(byte[] content, String contentType) throws IOException, TikaException {
        AutoDetectParser parser = new AutoDetectParser();
        Metadata metadata = new Metadata();
        if (contentType != null && !contentType.isBlank()) {
            metadata.set(HttpHeaders.CONTENT_TYPE, contentType);
        }
        ToXMLContentHandler handler = new ToXMLContentHandler();
        try (InputStream stream = new ByteArrayInputStream(content)) {
            parser.parse(stream, handler, metadata, new ParseContext());
        } catch (org.xml.sax.SAXException e) {
            throw new TikaException("Tika could not serialise the extracted content", e);
        }
        return fromXhtml(handler.toString());
    }

    // -------------------------------------------------------------------------
    // XHTML → ExtractionResult (package-private for unit tests)
    // -------------------------------------------------------------------------

    ExtractionResult fromXhtml(String xhtml) {
        Document doc = parseXml(xhtml);
        if (doc == null) {
            return new ExtractionResult("", List.of(), null);
        }

        NodeList pageDivs = doc.getElementsByTagName("div");
        List<Element> pageElements = new ArrayList<>();
        for (int i = 0; i < pageDivs.getLength(); i++) {
            Node n = pageDivs.item(i);
            if (n instanceof Element e && "page".equals(e.getAttribute("class"))) {
                pageElements.add(e);
            }
        }

        List<ExtractedParagraph> paragraphs = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();

        if (!pageElements.isEmpty()) {
            int pageNumber = 1;
            for (Element page : pageElements) {
                appendParagraphs(page, pageNumber, paragraphs, fullText);
                pageNumber++;
            }
        } else {
            // No page structure: walk the body for paragraph-like elements.
            NodeList bodies = doc.getElementsByTagName("body");
            if (bodies.getLength() > 0) {
                appendParagraphs((Element) bodies.item(0), null, paragraphs, fullText);
            }
        }

        Integer pageCount = pageElements.isEmpty() ? null : pageElements.size();
        return new ExtractionResult(fullText.toString(), paragraphs, pageCount);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void appendParagraphs(Element container, Integer pageNumber,
                                   List<ExtractedParagraph> paragraphs, StringBuilder fullText) {
        // Collect direct-child <p> elements; fall back to body text split on blank lines.
        NodeList children = container.getChildNodes();
        boolean hasParagraphElements = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element e && "p".equals(e.getTagName())) {
                hasParagraphElements = true;
                appendOne(e.getTextContent(), pageNumber, paragraphs, fullText);
            }
        }
        if (!hasParagraphElements) {
            // Plain text: split on blank lines.
            String body = container.getTextContent();
            for (String block : body.split("\\n{2,}")) {
                appendOne(block, pageNumber, paragraphs, fullText);
            }
        }
    }

    private void appendOne(String raw, Integer pageNumber,
                            List<ExtractedParagraph> paragraphs, StringBuilder fullText) {
        String text = collapseWhitespace(raw);
        if (text.isEmpty()) {
            return;
        }
        int charStart = fullText.length();
        fullText.append(text).append('\n');
        int charEnd = fullText.length() - 1;
        int paragraphNumber = paragraphs.size() + 1;
        paragraphs.add(new ExtractedParagraph(paragraphNumber, pageNumber, text, charStart, charEnd));
    }

    private static String collapseWhitespace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            return null;
        }
    }
}
