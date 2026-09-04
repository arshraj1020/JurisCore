package com.juriscore.billing.service;

import com.juriscore.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyCodesTest {

    @ParameterizedTest
    @ValueSource(strings = {"INR", "USD", "GBP", "EUR", "AED", "SGD"})
    void acceptsRealCodes(String code) {
        assertThat(CurrencyCodes.isValid(code)).isTrue();
        assertThat(CurrencyCodes.require(code)).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"XXXX", "IN", "rupees", "123", "₹"})
    void refusesAnythingElse(String code) {
        assertThat(CurrencyCodes.isValid(code)).isFalse();
        assertThatThrownBy(() -> CurrencyCodes.require(code)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("lower case is normalised, because the column requires capitals")
    void normalisesCase() {
        assertThat(CurrencyCodes.require("inr")).isEqualTo("INR");
        assertThat(CurrencyCodes.require(" usd ")).isEqualTo("USD");
    }

    @Test
    @DisplayName("the default is INR, for the market this product is being built for")
    void defaultsToRupees() {
        assertThat(CurrencyCodes.DEFAULT).isEqualTo("INR");
        assertThat(CurrencyCodes.require(null)).isEqualTo("INR");
        assertThat(CurrencyCodes.require("   ")).isEqualTo("INR");
    }

    @Test
    void nullIsNotValidOnItsOwn() {
        assertThat(CurrencyCodes.isValid(null)).isFalse();
    }
}
