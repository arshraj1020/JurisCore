package com.juriscore.common.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Turns a display name into a URL-safe handle: "Sharma &amp; Associates" -> "sharma-associates". */
public final class Slugs {

    private static final Pattern NON_LATIN = Pattern.compile("[^\\p{Alnum}]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("(^-+|-+$)");

    private Slugs() {
    }

    public static String of(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");
        String slug = NON_LATIN.matcher(normalized).replaceAll("-").toLowerCase(Locale.ROOT);
        slug = EDGE_DASHES.matcher(slug).replaceAll("");
        return slug.length() > 100 ? slug.substring(0, 100) : slug;
    }
}
