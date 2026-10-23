package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.engine.WorkflowRules;
import com.jurislpo.jurisone.exception.UserException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Input checks shared by the task services (400 with a sentence the user can act on). */
final class TaskInputs {
    private TaskInputs() {}

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Trimmed optional text: null when blank; 400 when longer than {@code max}. */
    static String optionalText(String value, int max, String tooLongMessage) {
        if (isBlank(value)) return null;
        String text = value.trim();
        if (text.length() > max) throw new UserException(HttpStatus.BAD_REQUEST, tooLongMessage);
        return text;
    }

    static UUID optionalUuid(String value, String message) {
        return isBlank(value) ? null : Check.uuid(value.trim(), message);
    }

    static LocalDate optionalDate(String value, String message) {
        if (isBlank(value)) return null;
        try {
            return LocalDate.parse(Check.isoDate(value.trim(), message));
        } catch (DateTimeException e) {
            throw new UserException(HttpStatus.BAD_REQUEST, message);
        }
    }

    /** {@code HH:mm}, 24-hour. */
    static LocalTime optionalTime(String value) {
        if (isBlank(value)) return null;
        String text = value.trim();
        if (!text.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the due time as HH:mm (24-hour).");
        return LocalTime.parse(text);
    }

    /** A palette colour name, or null. */
    static String optionalColor(String value) {
        return isBlank(value) ? null : Check.oneOf(value.trim(), WorkflowRules.COLORS);
    }

    /** Distinct UUIDs of a list (null = empty); 400 with {@code message} for a malformed one. */
    static List<UUID> uuids(List<String> values, String message) {
        if (values == null) return List.of();
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (String value : values) ids.add(Check.uuid(value, message));
        return new ArrayList<>(ids);
    }

    /** The page number of a {@code ?page=} parameter (1 when missing or invalid). */
    static int page(String value) {
        try {
            return Math.max(1, Math.min(10_000, Integer.parseInt(value == null ? "1" : value.trim())));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
