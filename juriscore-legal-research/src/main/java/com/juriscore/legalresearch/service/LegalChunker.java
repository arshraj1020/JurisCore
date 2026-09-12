package com.juriscore.legalresearch.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups {@link LegalDocumentTextExtractor.ExtractedParagraph}s into chunks sized for
 * embedding and retrieval, without ever splitting a paragraph across two chunks unless the
 * paragraph itself is larger than the limit.
 *
 * <p>"Legal-aware" here means specifically: a chunk boundary always falls on a paragraph
 * boundary the source document actually had, never mid-sentence inside one. A judgment's
 * paragraphs are already the unit a lawyer cites ("paragraph 43"), so preserving them whole
 * is what lets a later citation point at something real rather than an arbitrary slice of
 * text a fixed-size splitter happened to produce.
 *
 * <p>Each chunk keeps the paragraph/page number and character offsets of the paragraph(s)
 * it was built from — {@link ChunkCandidate#paragraphNumber()} is the first paragraph in
 * the chunk, which is what a citation should point at even when a chunk spans a few short
 * consecutive paragraphs.
 */
@Component
public class LegalChunker {

    public record ChunkCandidate(int chunkIndex, String text, Integer paragraphNumber,
                                 Integer pageNumber, Integer charStart, Integer charEnd) {
    }

    public List<ChunkCandidate> chunk(LegalDocumentTextExtractor.ExtractionResult extraction, int maxChunkChars) {
        List<ChunkCandidate> chunks = new ArrayList<>();
        List<LegalDocumentTextExtractor.ExtractedParagraph> paragraphs = extraction.paragraphs();
        if (paragraphs.isEmpty()) {
            return chunks;
        }

        StringBuilder current = new StringBuilder();
        Integer currentParagraphNumber = null;
        Integer currentPageNumber = null;
        Integer currentCharStart = null;
        Integer currentCharEnd = null;

        for (LegalDocumentTextExtractor.ExtractedParagraph paragraph : paragraphs) {
            if (paragraph.text().length() > maxChunkChars) {
                // A single paragraph bigger than the limit (an OCR'd blob with no
                // paragraph breaks, typically). Flush whatever was accumulating, then
                // split this one paragraph into its own fixed-size slices — all sharing
                // its paragraph/page number, because they are still one paragraph.
                flush(chunks, current, currentParagraphNumber, currentPageNumber,
                        currentCharStart, currentCharEnd);
                current = new StringBuilder();
                currentParagraphNumber = null;
                splitOversizedParagraph(chunks, paragraph, maxChunkChars);
                continue;
            }

            boolean wouldOverflow = current.length() > 0
                    && current.length() + 1 + paragraph.text().length() > maxChunkChars;
            if (wouldOverflow) {
                flush(chunks, current, currentParagraphNumber, currentPageNumber,
                        currentCharStart, currentCharEnd);
                current = new StringBuilder();
                currentParagraphNumber = null;
            }

            if (current.length() == 0) {
                currentParagraphNumber = paragraph.paragraphNumber();
                currentPageNumber = paragraph.pageNumber();
                currentCharStart = paragraph.charStart();
            } else {
                current.append('\n');
            }
            current.append(paragraph.text());
            currentCharEnd = paragraph.charEnd();
        }
        flush(chunks, current, currentParagraphNumber, currentPageNumber, currentCharStart, currentCharEnd);

        return chunks;
    }

    private void flush(List<ChunkCandidate> chunks, StringBuilder current, Integer paragraphNumber,
                       Integer pageNumber, Integer charStart, Integer charEnd) {
        if (current.length() == 0) {
            return;
        }
        chunks.add(new ChunkCandidate(chunks.size(), current.toString(), paragraphNumber,
                pageNumber, charStart, charEnd));
    }

    private void splitOversizedParagraph(List<ChunkCandidate> chunks,
                                         LegalDocumentTextExtractor.ExtractedParagraph paragraph,
                                         int maxChunkChars) {
        String text = paragraph.text();
        for (int offset = 0; offset < text.length(); offset += maxChunkChars) {
            String slice = text.substring(offset, Math.min(offset + maxChunkChars, text.length()));
            chunks.add(new ChunkCandidate(chunks.size(), slice, paragraph.paragraphNumber(),
                    paragraph.pageNumber(), paragraph.charStart() + offset,
                    paragraph.charStart() + offset + slice.length()));
        }
    }
}
