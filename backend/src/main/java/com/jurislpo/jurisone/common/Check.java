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

    public static String str(String value) {
        if (value == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected string, received undefined");
        return value;
    }

    /** {@code z.string().trim().min(n, msg).max(m, msg2)}. */
    public static String trimmed(String value, int min, String minMsg, int max, String maxMsg) {
        String text = str(value).trim();
        if (text.length() < min) throw new UserException(HttpStatus.BAD_REQUEST, minMsg);
        if (text.length() > max) throw new UserException(HttpStatus.BAD_REQUEST, maxMsg);
        return text;
    }

    public static String isoDate(String value, String message) {
        if (value == null || !value.matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new UserException(HttpStatus.BAD_REQUEST, message);
        return value;
    }
}
