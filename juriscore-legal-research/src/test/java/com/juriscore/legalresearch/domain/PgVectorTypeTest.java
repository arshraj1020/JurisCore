package com.juriscore.legalresearch.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgVectorTypeTest {

    @Test
    void roundTripsThroughPgvectorTextFormat() {
        float[] original = {0.125f, -1.5f, 3.0f, 0.0f};

        String formatted = PgVectorType.format(original);
        float[] parsed = PgVectorType.parse(formatted);

        assertThat(formatted).isEqualTo("[0.125,-1.5,3.0,0.0]");
        assertThat(parsed).containsExactly(original);
    }

    @Test
    void parsesEmptyVector() {
        assertThat(PgVectorType.parse("[]")).isEmpty();
    }
}
