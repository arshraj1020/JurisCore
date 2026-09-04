package com.juriscore.billing.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;

import java.util.Currency;
import java.util.Locale;

/**
 * Validates a currency code against ISO 4217, using the JDK's own table rather than a
 * hand-maintained list that would rot.
 *
 * <p>JurisCore stores a currency on every invoice and every payment and <strong>never
 * converts between them</strong>. There is no FX rate anywhere in this codebase, no rate
 * provider and no base currency: a payment in a currency other than its invoice's is
 * refused rather than converted, because a conversion needs a rate, a date and a source,
 * and inventing any of the three is how a firm ends up billing the wrong amount.
 *
 * <p>The default is INR. JurisCore is being built for the Indian legal market, and a firm
 * that wants something else says so per invoice or sets it on its billing profile.
 */
public final class CurrencyCodes {

    public static final String DEFAULT = "INR";

    private CurrencyCodes() {
    }

    public static boolean isValid(String code) {
        if (code == null || code.length() != 3) {
            return false;
        }
        try {
            Currency.getInstance(code.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Normalises to upper case, or 400s. Null falls back to {@link #DEFAULT}. */
    public static String require(String code) {
        String candidate = code == null || code.isBlank() ? DEFAULT : code.trim().toUpperCase(Locale.ROOT);
        if (!isValid(candidate)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "'" + code + "' is not an ISO 4217 currency code");
        }
        return candidate;
    }
}
