package com.juriscore.identity.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class StrongPasswordValidatorTest {

    private final StrongPasswordValidator validator = new StrongPasswordValidator();

    @Test
    @DisplayName("accepts a password meeting every rule")
    void acceptsStrongPassword() {
        assertThat(validator.isValid("Adv0cate!Chamber", null)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("rejects passwords missing a required property")
    @ValueSource(strings = {
            "Short1!",                 // under 12 characters
            "alllowercase1!",          // no uppercase
            "ALLUPPERCASE1!",          // no lowercase
            "NoDigitsHere!!",          // no digit
            "NoSymbolsHere1"           // no symbol
    })
    void rejectsWeakPasswords(String candidate) {
        assertThat(validator.isValid(candidate, null)).isFalse();
    }

    @Test
    @DisplayName("rejects null rather than throwing — bean validation calls us with it")
    void rejectsNull() {
        assertThat(validator.isValid(null, null)).isFalse();
    }

    @Test
    @DisplayName("rejects blocklisted passwords that would otherwise pass the rules")
    void rejectsBlocklisted() {
        // Passes length and every character-class rule, and is still worthless.
        assertThat(validator.isValid("Password@1234", null)).isFalse();
    }

    @Test
    @DisplayName("rejects absurdly long input so BCrypt is never handed unbounded work")
    void rejectsOverlongPassword() {
        String tooLong = "Aa1!".repeat(40);
        assertThat(validator.isValid(tooLong, null)).isFalse();
    }
}
