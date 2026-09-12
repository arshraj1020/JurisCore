package com.juriscore.legalresearch.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegalDocumentTextExtractorTest {

    private final LegalDocumentTextExtractor extractor = new LegalDocumentTextExtractor();

    @Test
    void preservesPageAndParagraphNumbersFromPageStructuredXhtml() {
        // Shaped the way Tika's PDF parser emits XHTML: one <div class="page"> per page,
        // <p> per paragraph within it. This is what a real multi-page PDF looks like
        // after Tika parses it, tested directly so the assertion does not depend on
        // fabricating a real PDF binary.
        String xhtml = """
                <html><body>
                <div class="page"><p>First paragraph of page one.</p><p>Second paragraph of page one.</p></div>
                <div class="page"><p>First paragraph of page two.</p></div>
                </body></html>
                """;

        LegalDocumentTextExtractor.ExtractionResult result = extractor.fromXhtml(xhtml);

        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.paragraphs()).hasSize(3);

        LegalDocumentTextExtractor.ExtractedParagraph p1 = result.paragraphs().get(0);
        assertThat(p1.paragraphNumber()).isEqualTo(1);
        assertThat(p1.pageNumber()).isEqualTo(1);
        assertThat(p1.text()).isEqualTo("First paragraph of page one.");

        // Paragraph numbers run continuously across the whole judgment, not reset per
        // page — the same convention a judgment's own printed paragraph numbers follow,
        // and the one a citation like "paragraph 43" assumes.
        LegalDocumentTextExtractor.ExtractedParagraph p3 = result.paragraphs().get(2);
        assertThat(p3.paragraphNumber()).isEqualTo(3);
        assertThat(p3.pageNumber()).isEqualTo(2);
        assertThat(p3.text()).isEqualTo("First paragraph of page two.");

        // Character offsets point back into the concatenated full text.
        assertThat(result.fullText().substring(p1.charStart(), p1.charEnd())).isEqualTo(p1.text());
    }

    @Test
    void leavesPageNumberNullWhenTheFormatHasNoPageStructure() {
        // No <div class="page"> at all — e.g. a DOCX or plain-text extraction. Paragraphs
        // are still preserved via <p>, but a page number would be invented, so it's null.
        String xhtml = "<html><body><p>Only paragraph.</p><p>Another one.</p></body></html>";

        LegalDocumentTextExtractor.ExtractionResult result = extractor.fromXhtml(xhtml);

        assertThat(result.pageCount()).isNull();
        assertThat(result.paragraphs()).extracting(LegalDocumentTextExtractor.ExtractedParagraph::pageNumber)
                .containsOnlyNulls();
        assertThat(result.paragraphs()).extracting(LegalDocumentTextExtractor.ExtractedParagraph::text)
                .containsExactly("Only paragraph.", "Another one.");
    }

    @Test
    void fallsBackToBlankLineSplittingWhenThereIsNoParagraphMarkupAtAll() {
        String xhtml = "<html><body>First block of text.\n\nSecond block of text.</body></html>";

        LegalDocumentTextExtractor.ExtractionResult result = extractor.fromXhtml(xhtml);

        assertThat(result.paragraphs()).extracting(LegalDocumentTextExtractor.ExtractedParagraph::text)
                .containsExactly("First block of text.", "Second block of text.");
    }

    @Test
    void skipsBlankParagraphsRatherThanProducingEmptyChunksLater() {
        String xhtml = "<html><body><p>   </p><p>Real content.</p></body></html>";

        LegalDocumentTextExtractor.ExtractionResult result = extractor.fromXhtml(xhtml);

        assertThat(result.paragraphs()).hasSize(1);
        assertThat(result.paragraphs().get(0).text()).isEqualTo("Real content.");
    }

    @Test
    void extractsPlainTextThroughTikaEndToEnd() throws Exception {
        byte[] content = "Hello, this is a plain text judgment.".getBytes(StandardCharsets.UTF_8);

        LegalDocumentTextExtractor.ExtractionResult result = extractor.extract(content, "text/plain");

        assertThat(result.fullText()).contains("Hello, this is a plain text judgment.");
        assertThat(result.paragraphs()).isNotEmpty();
    }

    @Test
    void extractionResultParagraphsAreOrdered() {
        String xhtml = """
                <html><body>
                <div class="page"><p>A</p><p>B</p></div>
                <div class="page"><p>C</p></div>
                </body></html>
                """;
        List<LegalDocumentTextExtractor.ExtractedParagraph> paragraphs =
                extractor.fromXhtml(xhtml).paragraphs();
        assertThat(paragraphs).extracting(LegalDocumentTextExtractor.ExtractedParagraph::text)
                .containsExactly("A", "B", "C");
    }
}
