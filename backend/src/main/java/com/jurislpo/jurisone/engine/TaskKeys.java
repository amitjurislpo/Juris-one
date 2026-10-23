package com.jurislpo.jurisone.engine;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Project keys ({@code HR}, {@code PAY}) and task keys ({@code HR-12}). The key of a task is computed, never stored. */
public final class TaskKeys {
    private TaskKeys() {}

    public static final Pattern PROJECT_KEY = Pattern.compile("^[A-Z][A-Z0-9]{1,5}$");
    private static final Pattern TASK_KEY = Pattern.compile("^([A-Za-z][A-Za-z0-9]{1,5})-(\\d{1,9})$");

    public record ParsedKey(String projectKey, int number) {}

    public static boolean isProjectKey(String text) {
        return text != null && PROJECT_KEY.matcher(text).matches();
    }

    public static String formatKey(String projectKey, int number) {
        return projectKey + "-" + number;
    }

    /** {@code "hr-12"} → {@code (HR, 12)}; empty when the text is not a task key. */
    public static Optional<ParsedKey> parseKey(String text) {
        if (text == null) return Optional.empty();
        Matcher matcher = TASK_KEY.matcher(text.trim());
        if (!matcher.matches()) return Optional.empty();
        return Optional.of(new ParsedKey(matcher.group(1).toUpperCase(Locale.ROOT), Integer.parseInt(matcher.group(2))));
    }
}
