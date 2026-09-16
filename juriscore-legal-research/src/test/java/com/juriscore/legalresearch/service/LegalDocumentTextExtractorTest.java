package com.juriscore.legalresearch.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LegalDocumentTextExtractor}.
 *
 * <p>Tests are split across two axes:
 * <ul>
 *   <li>The XHTML-walking logic ({@link LegalDocumentTextExtractor#fromXhtml}) — tested via
 *       synthetic XHTML strings that represent what Tika would produce, without needing
 *       Tika on the test classpath for every case.</li>
 *   <li>The PDF extraction path — tested via PDFBox-generated in-memory PDFs, exercising
 *       the full {@link LegalDocumentTextExtractor#extract} dispatch.</li>
 *   <li>The non-PDF Tika end-to-end path — tested with plain-text bytes whose MIME type
 *       is known and does not require the PDF branch.</li>
 * </ul>
 */
class LegalDocumentTextExtractorTest {

    private final LegalDocumentTextExtractor extractor = new LegalDocumentTextExtractor();

    // =========================================================================
    // XHTML walking — fromXhtml()
    // =========================================================================

    @Test
    void preservesPageAndParagraphNumbersFromPageStructuredXhtml() {
        String xhtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body>
                  <div class="page"><p>First paragraph.</p><p>Second paragraph.</p></div>
                  <div class="page"><p>Third paragraph.</p></div>
                </body>
                </html>
                """;

        LegalDocumentTextExtractor.ExtractionResult result = extractor.fromXhtml(xhtml);

        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.paragraphs()).hasSize(3);

        assertThat(result.paragraphs().get(0).paragraphNumber()).isEqualTo(1);
        assertThat(result.paragraphs().get(0).pageNumber()).isEqualTo(1);
        assertThat(result.paragraphs().get(0).text()).isEqualTo("First paragraph.");

        assertThat(result.paragraphs().get(1).paragraphNumber()).isEqualTo(2);
        assertThat(result.paragraphs().get(1).pageNumber()).isEqualTo(1);
        assertThat(result.paragraphs().get(1).text()).isEqualTo("Second paragraph.");

        assertThat(result.paragraphs().get(2).paragraphNumber()).isEqualTo(3);
        assertThat(result.paragraphs().get(2).pageNumber()).isEqualTo(2);
        assertThat(result.paragraphs().get(2).text()).isEqualTo("Third paragraph.");
    }

    @Test
    void leavesPageNumberNullWhenTheFormatHasNoPageStructure() {
        String xhtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body><p>No pages here.</p><p>Just body paragraphs.</p></body>
                </html>
                """;

        LegalDocumentTextExtractor.ExtractionResult result = extractor.fromXhtml(xhtml);

        assertThat(result.pageCount()).isNull();
        assertThat(result.paragraphs()).allMatch(p -> p.pageNumber() == null);
        assertThat(result.paragraphs()).extracting(LegalDocumentTextExtractor.ExtractedParagraph::text)
                .containsExactly("No pages here.", "Just body paragraphs.");
    }

    @Test
    void fallsBackToBlankLineSplittingWhenThereIsNoParagraphMarkupAtAll() {
        String xhtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body>First block

                Second block</body>
                </html>
                """;

        LegalDocumentTextExtractor.ExtractionResult result = extractor.fromXhtml(xhtml);

        assertThat(result.paragraphs()).hasSize(2);
        assertThat(result.paragraphs().get(0).text()).isEqualTo("First block");
        assertThat(result.paragraphs().get(1).text()).isEqualTo("Second block");
    }

    @Test
    void skipsBlankParagraphsRatherThanProducingEmptyChunksLater() {
        String xhtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body>
                  <div class="page"><p>Real content.</p><p>   </p><p>More content.</p></div>
                </body>
                </html>
                """;

        LegalDocumentTextExtractor.ExtractionResult result = extractor.fromXhtml(xhtml);

        assertThat(result.paragraphs()).hasSize(2);
        assertThat(result.paragraphs()).extracting(LegalDocumentTextExtractor.ExtractedParagraph::text)
                .containsExactly("Real content.", "More content.");
    }

    @Test
    void extractionResultParagraphsAreOrdered() {
        String xhtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body>
                  <div class="page"><p>A</p><p>B</p><p>C</p></div>
                </body>
                </html>
                """;

        LegalDocumentTextExtractor.ExtractionResult result = extractor.fromXhtml(xhtml);

        List<Integer> numbers = result.paragraphs().stream()
                .map(LegalDocumentTextExtractor.ExtractedParagraph::paragraphNumber)
                .toList();
        assertThat(numbers).isSorted();
    }

    // =========================================================================
    // Non-PDF Tika end-to-end
    // =========================================================================

    @Test
    void extractsPlainTextThroughTikaEndToEnd() throws Exception {
        byte[] content = "Hello world.\n\nSecond paragraph.".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        LegalDocumentTextExtractor.ExtractionResult result = extractor.extract(content, "text/plain");

        assertThat(result.fullText()).contains("Hello world.");
        assertThat(result.paragraphs()).isNotEmpty();
    }

    // =========================================================================
    // PDF path — PDFBox
    // =========================================================================

    @Test
    void extractsPdfTextThroughPdfBoxEndToEnd() throws Exception {
        byte[] pdfBytes = buildSinglePagePdf("The quick brown fox.\n\nJumped over the lazy dog.");

        LegalDocumentTextExtractor.ExtractionResult result = extractor.extract(pdfBytes, "application/pdf");

        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.paragraphs()).isNotEmpty();
        assertThat(result.fullText()).containsIgnoringCase("quick brown fox");
        assertThat(result.paragraphs()).allMatch(p -> p.pageNumber() == 1);
    }

    @Test
    void pdfParagraphsCarryCorrectPageNumbers() throws Exception {
        byte[] pdfBytes = buildTwoPagePdf("Page one text.", "Page two text.");

        LegalDocumentTextExtractor.ExtractionResult result = extractor.extract(pdfBytes, "application/pdf");

        assertThat(result.pageCount()).isEqualTo(2);
        // Each page had text, so at least one paragraph per page.
        assertThat(result.paragraphs()).anyMatch(p -> p.pageNumber() == 1);
        assertThat(result.paragraphs()).anyMatch(p -> p.pageNumber() == 2);
        // Paragraph numbers must be monotonically increasing.
        List<Integer> numbers = result.paragraphs().stream()
                .map(LegalDocumentTextExtractor.ExtractedParagraph::paragraphNumber)
                .toList();
        assertThat(numbers).isSorted();
    }

    @Test
    void detectsPdfByMagicBytesWhenContentTypeIsAbsent() throws Exception {
        byte[] pdfBytes = buildSinglePagePdf("Magic byte detection check.");

        // No content type — should still route to the PDF path via %PDF magic bytes.
        LegalDocumentTextExtractor.ExtractionResult result = extractor.extract(pdfBytes, null);

        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.fullText()).containsIgnoringCase("magic byte detection");
    }

    @Test
    void charStartAndEndPositionsAreConsistentWithFullTextForPdf() throws Exception {
        byte[] pdfBytes = buildSinglePagePdf("Consistent offsets test.");

        LegalDocumentTextExtractor.ExtractionResult result = extractor.extract(pdfBytes, "application/pdf");

        String fullText = result.fullText();
        for (LegalDocumentTextExtractor.ExtractedParagraph p : result.paragraphs()) {
            String slice = fullText.substring(p.charStart(), p.charEnd());
            assertThat(slice).isEqualTo(p.text());
        }
    }

    // =========================================================================
    // PDF builder helpers
    // =========================================================================

    /**
     * Builds a minimal single-page in-memory PDF containing the given text.
     * Uses PDFBox 2.x (the same version on the production classpath).
     */
    private static byte[] buildSinglePagePdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            addPage(doc, text);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Builds a two-page in-memory PDF with the given per-page texts. */
    private static byte[] buildTwoPagePdf(String page1Text, String page2Text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            addPage(doc, page1Text);
            addPage(doc, page2Text);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void addPage(PDDocument doc, String text) throws Exception {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA, 12);
            cs.newLineAtOffset(50, 700);
            // PDFBox 2.x showText does not handle \n — write lines individually.
            for (String line : text.split("\n")) {
                cs.showText(line.isEmpty() ? " " : line);
                cs.newLineAtOffset(0, -15);
            }
            cs.endText();
        }
    }
}
