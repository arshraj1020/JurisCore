package com.juriscore.billing.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic an invoice is built on.
 *
 * <p>Asserted directly and in detail, because this is the one place in the platform where
 * being subtly wrong costs a firm money rather than an error message.
 */
class MoneyTest {

    @Test
    @DisplayName("every currency amount lands on two decimals")
    void amountsCarryTwoDecimals() {
        assertThat(Money.amount(new BigDecimal("5"))).isEqualTo(new BigDecimal("5.00"));
        assertThat(Money.amount(new BigDecimal("5.1"))).isEqualTo(new BigDecimal("5.10"));
        assertThat(Money.amount(new BigDecimal("5.005"))).isEqualTo(new BigDecimal("5.01"));
        assertThat(Money.amount(null)).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("HALF_UP, which is what a person does by hand")
    void roundsHalvesAway() {
        assertThat(Money.ROUNDING).isEqualTo(RoundingMode.HALF_UP);
        // HALF_EVEN would give 2.12 here, and a firm explaining that to a client is a
        // conversation that should not have to happen.
        assertThat(Money.amount(new BigDecimal("2.125"))).isEqualTo(new BigDecimal("2.13"));
        assertThat(Money.amount(new BigDecimal("2.135"))).isEqualTo(new BigDecimal("2.14"));
    }

    @ParameterizedTest(name = "{0} × {1} = {2}")
    @CsvSource({
            "1.000,   0.01,        0.01",
            "3.000,   0.01,        0.03",
            "0.333,   100.00,     33.30",
            "2.500,   4000.00, 10000.00",
            "1.005,   100.00,    100.50",
            "0.001,     0.01,      0.00",
            "7.000, 999999.99, 6999999.93"})
    @DisplayName("a line amount is the rounded product of quantity and unit price")
    void lineAmounts(String quantity, String unitPrice, String expected) {
        assertThat(Money.lineAmount(new BigDecimal(quantity), new BigDecimal(unitPrice)))
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    @DisplayName("a hundredth of a rupee survives; nothing silently rounds to zero above it")
    void thePennyCase() {
        assertThat(Money.lineAmount(new BigDecimal("1.000"), new BigDecimal("0.01")))
                .isEqualByComparingTo(new BigDecimal("0.01"));
        // Below the smallest unit there is nothing to keep, and saying so is the honest
        // behaviour: 0.001 × 0.01 really is less than half a paisa.
        assertThat(Money.lineAmount(new BigDecimal("0.001"), new BigDecimal("0.01")))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest(name = "{1}% of {0} = {2}")
    @CsvSource({
            "10000.00, 18.000, 1800.00",
            "  100.00, 18.000,   18.00",
            "    0.05, 18.000,    0.01",
            "    0.01, 18.000,    0.00",
            "  999.99,  5.500,   55.00",
            " 1234.56, 12.345,  152.41",
            "  100.00,  0.000,    0.00"})
    @DisplayName("tax is a percentage of the already-rounded line amount")
    void taxRounding(String amount, String rate, String expected) {
        assertThat(Money.taxOn(new BigDecimal(amount), new BigDecimal(rate)))
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    @DisplayName("a null or zero rate costs nothing to compute")
    void noTaxIsZero() {
        assertThat(Money.taxOn(new BigDecimal("100.00"), null)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(Money.taxOn(new BigDecimal("100.00"), BigDecimal.ZERO))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("summing rounded amounts is exact, which is why the invoice total is an identity")
    void sumsOfRoundedAmountsDoNotDrift() {
        // The floating-point disaster case: 0.1 + 0.2 != 0.3 in a double.
        BigDecimal total = Money.ZERO;
        for (int i = 0; i < 3; i++) {
            total = total.add(Money.amount(new BigDecimal("0.10")));
        }
        assertThat(total).isEqualByComparingTo(new BigDecimal("0.30"));

        // A hundred lines of a third of a rupee, each rounded, sums to exactly what the
        // printed invoice says — not to something a hundredth away from it.
        BigDecimal hundredThirds = Money.ZERO;
        for (int i = 0; i < 100; i++) {
            hundredThirds = hundredThirds.add(Money.lineAmount(
                    new BigDecimal("1.000"), new BigDecimal("0.33")));
        }
        assertThat(hundredThirds).isEqualByComparingTo(new BigDecimal("33.00"));
    }

    @Test
    @DisplayName("large totals stay exact — no precision cliff on the way up")
    void largeTotals() {
        BigDecimal huge = Money.lineAmount(new BigDecimal("1000.000"), new BigDecimal("99999.99"));
        assertThat(huge).isEqualByComparingTo(new BigDecimal("99999990.00"));
        assertThat(Money.sum(huge, new BigDecimal("0.01")))
                .isEqualByComparingTo(new BigDecimal("99999990.01"));
    }

    @Test
    void recognisesSignAndEquality() {
        assertThat(Money.isPositive(new BigDecimal("0.01"))).isTrue();
        assertThat(Money.isPositive(BigDecimal.ZERO)).isFalse();
        assertThat(Money.isPositive(null)).isFalse();
        assertThat(Money.isNegative(new BigDecimal("-0.01"))).isTrue();
        assertThat(Money.equal(new BigDecimal("5"), new BigDecimal("5.00"))).isTrue();
        assertThat(Money.equal(new BigDecimal("5.00"), new BigDecimal("5.01"))).isFalse();
    }

    @Test
    @DisplayName("quantities carry three decimals and rates three, and both are documented")
    void scales() {
        assertThat(Money.SCALE).isEqualTo(2);
        assertThat(Money.QUANTITY_SCALE).isEqualTo(3);
        assertThat(Money.RATE_SCALE).isEqualTo(3);
        assertThat(Money.quantity(new BigDecimal("2.5"))).isEqualTo(new BigDecimal("2.500"));
        assertThat(Money.rate(new BigDecimal("18"))).isEqualTo(new BigDecimal("18.000"));
        assertThat(Money.rate(null)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
