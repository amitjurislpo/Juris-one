package com.jurislpo.jurisone.common;

import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.PeopleRules;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

/** Input checks with zod 4's default messages (400), for the few inputs the forms can't get wrong. */
public final class Check {
    private Check() {}

    public static UUID uuid(String value) {
        if (value == null || !PeopleRules.UUID_RE.matcher(value).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid UUID");
        return UUID.fromString(value);
    }

    /** {@code z.string().uuid(message)}. */
    public static UUID uuid(String value, String message) {
        if (value == null || !PeopleRules.UUID_RE.matcher(value).matches()) throw new UserException(HttpStatus.BAD_REQUEST, message);
        return UUID.fromString(value);
    }

    public static String oneOf(String value, List<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw new UserException(HttpStatus.BAD_REQUEST,
                    "Invalid option: expected one of " + allowed.stream().map(a -> "\"" + a + "\"").collect(Collectors.joining("|")));
        }
        return value;
    }

    public static String requireString(String value) {
        if (value == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected string, received undefined");
        return value;
    }

    /** {@code z.string().trim().min(n, msg).max(m, msg2)}. */
    public static String trimmed(String value, int min, String minMsg, int max, String maxMsg) {
        String text = requireString(value).trim();
        if (text.length() < min) throw new UserException(HttpStatus.BAD_REQUEST, minMsg);
        if (text.length() > max) throw new UserException(HttpStatus.BAD_REQUEST, maxMsg);
        return text;
    }

    /** A real calendar day written YYYY-MM-DD (not just the right shape: 2041-13-45 and 2041-02-30 are not days). */
    public static boolean isIsoDate(String value) {
        if (value == null || !value.matches("^\\d{4}-\\d{2}-\\d{2}$")) return false;
        try {
            java.time.LocalDate.parse(value);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    public static String isoDate(String value, String message) {
        if (value == null || !value.matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new UserException(HttpStatus.BAD_REQUEST, message);
        try {
            java.time.LocalDate.parse(value); // right shape: also refuse a day that does not exist (2041-13-45, 2041-02-30) here, not as a database error later
        } catch (java.time.format.DateTimeParseException e) {
            throw new UserException(HttpStatus.BAD_REQUEST, message);
        }
        return value;
    }
}
