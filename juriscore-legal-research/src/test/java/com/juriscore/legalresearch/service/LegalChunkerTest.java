package com.juriscore.legalresearch.service;

import com.juriscore.legalresearch.service.LegalDocumentTextExtractor.ExtractedParagraph;
import com.juriscore.legalresearch.service.LegalDocumentTextExtractor.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegalChunkerTest {

    private final LegalChunker chunker = new LegalChunker();

    @Test
    void groupsConsecutiveShortParagraphsIntoOneChunk() {
        List<ExtractedParagraph> paragraphs = List.of(
                new ExtractedParagraph(1, 1, "Short paragraph one.", 0, 20),
                new ExtractedParagraph(2, 1, "Short paragraph two.", 21, 41));
        ExtractionResult extraction = new ExtractionResult("ignored", paragraphs, 1);

        List<LegalChunker.ChunkCandidate> chunks = chunker.chunk(extraction, 1000);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("Short paragraph one.", "Short paragraph two.");
        assertThat(chunks.get(0).paragraphNumber()).isEqualTo(1);
        assertThat(chunks.get(0).pageNumber()).isEqualTo(1);
    }

    @Test
    void startsANewChunkRatherThanExceedingTheLimit() {
        String para1 = "A".repeat(50);
        String para2 = "B".repeat(50);
        List<ExtractedParagraph> paragraphs = List.of(
                new ExtractedParagraph(1, 1, para1, 0, 50),
                new ExtractedParagraph(2, 1, para2, 51, 101));
        ExtractionResult extraction = new ExtractionResult("ignored", paragraphs, 1);

        // Limit small enough that both paragraphs together would overflow it.
        List<LegalChunker.ChunkCandidate> chunks = chunker.chunk(extraction, 60);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).isEqualTo(para1);
        assertThat(chunks.get(1).text()).isEqualTo(para2);
        assertThat(chunks.get(1).paragraphNumber()).isEqualTo(2);
        // chunkIndex is stable ordering, used as the persistence key alongside judgmentId.
        assertThat(chunks.get(0).chunkIndex()).isEqualTo(0);
        assertThat(chunks.get(1).chunkIndex()).isEqualTo(1);
    }

    @Test
    void splitsASingleParagraphLargerThanTheLimitRatherThanProducingOneHugeChunk() {
        String hugeParagraph = "X".repeat(250);
        List<ExtractedParagraph> paragraphs = List.of(
                new ExtractedParagraph(1, 1, hugeParagraph, 0, 250));
        ExtractionResult extraction = new ExtractionResult("ignored", paragraphs, 1);

        List<LegalChunker.ChunkCandidate> chunks = chunker.chunk(extraction, 100);

        assertThat(chunks).hasSize(3); // 100 + 100 + 50
        assertThat(chunks).allMatch(c -> c.paragraphNumber() == 1);
        assertThat(chunks).allMatch(c -> c.pageNumber() == 1);
        String reassembled = chunks.stream().map(LegalChunker.ChunkCandidate::text)
                .reduce("", String::concat);
        assertThat(reassembled).isEqualTo(hugeParagraph);
    }

    @Test
    void preservesCharacterOffsetsForCitationPurposes() {
        List<ExtractedParagraph> paragraphs = List.of(
                new ExtractedParagraph(1, null, "Paragraph text.", 5, 21));
        ExtractionResult extraction = new ExtractionResult("ignored", paragraphs, null);

        LegalChunker.ChunkCandidate chunk = chunker.chunk(extraction, 1000).get(0);

        assertThat(chunk.charStart()).isEqualTo(5);
        assertThat(chunk.charEnd()).isEqualTo(21);
        assertThat(chunk.pageNumber()).isNull();
    }

    @Test
    void emptyExtractionProducesNoChunks() {
        ExtractionResult extraction = new ExtractionResult("", List.of(), null);

        assertThat(chunker.chunk(extraction, 1000)).isEmpty();
    }
}
