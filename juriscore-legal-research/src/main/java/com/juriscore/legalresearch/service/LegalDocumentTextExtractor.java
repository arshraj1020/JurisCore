package com.juriscore.legalresearch.service;

import org.apache.tika.exception.TikaException;
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

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls text out of an uploaded judgment via Apache Tika, preserving page and paragraph
 * boundaries where the source format actually has them.
 *
 * <h2>How page/paragraph structure survives extraction</h2>
 *
 * <p>Tika's parsers can emit XHTML rather than flat text ({@link ToXMLContentHandler}), and
 * for paginated formats — PDF chief among them — that XHTML wraps each page in
 * {@code <div class="page">} and each paragraph in {@code <p>}. That structure is what
 * this class walks to assign paragraph and page numbers; nothing here re-derives paragraph
 * boundaries from raw text, because Tika's parser already knows them better than a regex
 * on whitespace would.
 *
 * <p>Not every format has that structure. Plain text and most Office formats do not carry
 * an inherent notion of a "page" (that is a rendering concern, decided by page size and
 * font, not a property of the document), so {@link ExtractedParagraph#pageNumber()} is
 * {@code null} for those — left unknown rather than guessed, the same rule the rest of
 * this module follows for judgment metadata. Paragraphs are still preserved via {@code <p>}
 * where the format has them; a format with no paragraph markup at all falls back to
 * splitting on blank lines, and if even that yields nothing, the whole document becomes
 * one paragraph rather than being dropped.
 */
@Component
public class LegalDocumentTextExtractor {

    /** One preserved unit of the source document. */
    public record ExtractedParagraph(int paragraphNumber, Integer pageNumber, String text,
                                      int charStart, int charEnd) {
    }

    /** {@code pageCount} is null when the format has no page concept (see class javadoc). */
    public record ExtractionResult(String fullText, List<ExtractedParagraph> paragraphs, Integer pageCount) {
    }

    public ExtractionResult extract(byte[] content, String contentType) throws IOException, TikaException {
        AutoDetectParser parser = new AutoDetectParser();
        Metadata metadata = new Metadata();
        if (contentType != null && !contentType.isBlank()) {
            metadata.set(Metadata.CONTENT_TYPE, contentType);
        }
        ToXMLContentHandler handler = new ToXMLContentHandler();
        try (InputStream stream = new ByteArrayInputStream(content)) {
            parser.parse(stream, handler, metadata, new ParseContext());
        } catch (org.xml.sax.SAXException e) {
            throw new TikaException("Tika could not serialise the extracted content", e);
        }
        return fromXhtml(handler.toString());
    }

    /**
     * The DOM-walking half, split out from {@link #extract} so the page/paragraph
     * assignment logic can be unit tested against hand-written XHTML without needing a
     * real PDF/DOCX binary to drive Tika.
     */
    ExtractionResult fromXhtml(String xhtml) {
        Document dom = parseXml(xhtml);

        List<Element> pageDivs = new ArrayList<>();
        NodeList divs = dom.getElementsByTagName("div");
        for (int i = 0; i < divs.getLength(); i++) {
            Element div = (Element) divs.item(i);
            if ("page".equals(div.getAttribute("class"))) {
                pageDivs.add(div);
            }
        }

        List<ExtractedParagraph> paragraphs = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();

        if (!pageDivs.isEmpty()) {
            int pageNumber = 0;
            for (Element page : pageDivs) {
                pageNumber++;
                appendParagraphs(page.getElementsByTagName("p"), pageNumber, paragraphs, fullText);
            }
            return new ExtractionResult(fullText.toString(), paragraphs, pageNumber);
        }

        NodeList allParagraphs = dom.getElementsByTagName("p");
        if (allParagraphs.getLength() > 0) {
            appendParagraphs(allParagraphs, null, paragraphs, fullText);
            if (!paragraphs.isEmpty()) {
                return new ExtractionResult(fullText.toString(), paragraphs, null);
            }
        }

        // Last resort: no <p> markup at all, or every <p> was blank. Split the document's
        // raw text on blank lines; if that too yields nothing, the whole non-blank body
        // becomes a single paragraph rather than a judgment with zero preserved text.
        String bodyText = dom.getDocumentElement() == null ? "" : dom.getDocumentElement().getTextContent();
        String[] blocks = bodyText.split("\\n\\s*\\n");
        int index = 0;
        for (String block : blocks) {
            String trimmed = collapseWhitespace(block);
            if (trimmed.isEmpty()) {
                continue;
            }
            index++;
            appendOne(index, null, trimmed, paragraphs, fullText);
        }
        if (paragraphs.isEmpty()) {
            String trimmed = collapseWhitespace(bodyText);
            if (!trimmed.isEmpty()) {
                appendOne(1, null, trimmed, paragraphs, fullText);
            }
        }
        return new ExtractionResult(fullText.toString(), paragraphs, null);
    }

    private void appendParagraphs(NodeList nodes, Integer pageNumber, List<ExtractedParagraph> out,
                                  StringBuilder fullText) {
        for (int i = 0; i < nodes.getLength(); i++) {
            String trimmed = collapseWhitespace(nodes.item(i).getTextContent());
            if (trimmed.isEmpty()) {
                continue;
            }
            appendOne(out.size() + 1, pageNumber, trimmed, out, fullText);
        }
    }

    private void appendOne(int paragraphNumber, Integer pageNumber, String text,
                           List<ExtractedParagraph> out, StringBuilder fullText) {
        int start = fullText.length();
        fullText.append(text).append('\n');
        int end = fullText.length() - 1;
        out.add(new ExtractedParagraph(paragraphNumber, pageNumber, text, start, end));
    }

    private static String collapseWhitespace(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    private static Document parseXml(String xhtml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Hardened against XXE even though the input is Tika's own output, not
            // externally supplied XML — cheap insurance, never a reason to trust less.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xhtml)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse Tika's XHTML output", e);
        }
    }
}
