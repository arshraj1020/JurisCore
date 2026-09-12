package com.juriscore.legalresearch.config;

import com.juriscore.legalresearch.domain.EmbeddingSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalResearchPropertiesTest {

    @Test
    void defaultsMatchTheFixedSchemaDimension() {
        LegalResearchProperties properties = new LegalResearchProperties();

        assertThat(properties.getEmbedding().getDimensions()).isEqualTo(EmbeddingSchema.VECTOR_DIMENSIONS);

        // Should not throw: the default configuration must always be schema-compatible.
        properties.validate();
    }

    @Test
    void refusesToStartWhenConfiguredDimensionDoesNotMatchTheSchema() {
        LegalResearchProperties properties = new LegalResearchProperties();
        properties.getEmbedding().setDimensions(3072);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3072")
                .hasMessageContaining(String.valueOf(EmbeddingSchema.VECTOR_DIMENSIONS));
    }
}
