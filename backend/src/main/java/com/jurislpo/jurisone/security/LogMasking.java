package com.jurislpo.jurisone.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides personal data and credentials in text that is about to be logged or stored as an error message. It is a safety net for text
 * nobody wrote on purpose (exception messages, database error details), not a licence to log such values: code should still not pass them.
 */
public final class LogMasking {
    private LogMasking() {}

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern JSON_WEB_TOKEN = Pattern.compile("\\beyJ[A-Za-z0-9_\\-]{5,}\\.[A-Za-z0-9_\\-]{5,}\\.[A-Za-z0-9_\\-]*");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/\\-]+=*");
    /** PostgreSQL error details: {@code Failing row contains (…every column…)} and {@code Key (email)=(a@b.com) already exists}. */
    private static final Pattern FAILING_ROW = Pattern.compile("Failing row contains \\([^\\n]*");
    private static final Pattern DUPLICATE_KEY = Pattern.compile("Key \\(([^)]*)\\)=\\([^\\n]*?\\)(?= (?:already|is not|is still))");
    private static final Pattern PAN = Pattern.compile("(?<![A-Za-z0-9])[A-Z]{5}[0-9]{4}[A-Z](?![A-Za-z0-9])");
    private static final Pattern AADHAAR_GROUPED = Pattern.compile("(?<![\\w-])\\d{4}[ -]\\d{4}[ -](\\d{4})(?![\\w-])");
    /** Bank account, Aadhaar, UAN, phone and similar: a run of 9 to 18 digits that is not part of a longer word, id or decimal. */
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<![\\w.-])(\\d{5,14})(\\d{4})(?![\\w.-])");

    /** The text with every recognised secret or personal value replaced; null stays null. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        String masked = replaceEmails(text);
        masked = JSON_WEB_TOKEN.matcher(masked).replaceAll("eyJ***");
        masked = BEARER.matcher(masked).replaceAll("$1***");
        masked = FAILING_ROW.matcher(masked).replaceAll("Failing row contains (***)");
        masked = DUPLICATE_KEY.matcher(masked).replaceAll("Key ($1)=(***)");
        masked = replacePans(masked);
        masked = AADHAAR_GROUPED.matcher(masked).replaceAll("XXXX XXXX $1");
        return LONG_DIGITS.matcher(masked).replaceAll("XXXX$2");
    }

    private static String replaceEmails(String text) {
        Matcher matcher = EMAIL.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(AuditTrail.maskEmail(matcher.group())));
        return matcher.appendTail(out).toString();
    }

    private static String replacePans(String text) {
        Matcher matcher = PAN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out, "XXXXXX" + matcher.group().substring(6));
        return matcher.appendTail(out).toString();
    }
}
