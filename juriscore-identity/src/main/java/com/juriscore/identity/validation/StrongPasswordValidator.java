package com.juriscore.identity.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

/**
 * Length carries most of the strength, so the floor is 12 rather than the usual 8.
 * The character-class rules exist mainly to stop the obvious "password1234"; the
 * blocklist catches the handful that pass the rules and are still worthless.
 */
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    private static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 128;

    /** Passwords that satisfy every rule above and are still the first thing anyone tries. */
    private static final Set<String> BLOCKED = Set.of(
            "password@1234", "password@123!", "qwerty@12345", "welcome@12345",
            "juriscore@123", "admin@1234567", "letmein@12345");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            return false;
        }
        if (BLOCKED.contains(value.toLowerCase())) {
            return false;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean symbol = false;
        for (char c : value.toCharArray()) {
            if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                symbol = true;
            }
        }
        return upper && lower && digit && symbol;
    }
}
