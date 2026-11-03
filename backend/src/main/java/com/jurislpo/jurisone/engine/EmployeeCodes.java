package com.jurislpo.jurisone.engine;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Employee codes: the business's short prefix plus a running number, with no hyphen (JSC01, JL07). Old codes keep their own digit count;
 * a new automatic code continues after the highest number of the business and grows past the padded width (JSC99 then JSC100).
 */
public final class EmployeeCodes {

    /** A code typed by HR: capital letters then digits, nothing else (at most six digits so the number always fits an int). */
    public static final Pattern CUSTOM_CODE = Pattern.compile("^[A-Z]{1,10}[0-9]{1,6}$");

    public static final java.util.List<String> SERIES_MODES = java.util.List.of("AUTO", "MANUAL");

    private EmployeeCodes() {}

    /** The next automatic code: {@code prefix} + {@code maxNumber + 1} padded to {@code digits} (never cut). */
    public static String next(String prefix, int digits, Integer maxNumber) {
        return prefix + String.format(Locale.ROOT, "%0" + Math.max(digits, 1) + "d", (maxNumber == null ? 0 : maxNumber) + 1);
    }

    /** The regular expression (for PostgreSQL) matching the codes of one prefix: the prefix then digits only. */
    public static String seriesPattern(String prefix) {
        return "^" + prefix + "[0-9]{1,9}$";
    }
}
