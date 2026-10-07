package com.jurislpo.jurisone.common;

import com.jurislpo.jurisone.engine.PeopleRules;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

/** Input checks with zod 4's default messages (400), for the few inputs the forms can't get wrong. */
public final class Check {
    private Check() {}

    public static UUID uuid(String v) {
        if (v == null || !PeopleRules.UUID_RE.matcher(v).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid UUID");
        return UUID.fromString(v);
    }

    /** {@code z.string().uuid(message)}. */
    public static UUID uuid(String v, String message) {
        if (v == null || !PeopleRules.UUID_RE.matcher(v).matches()) throw new UserException(HttpStatus.BAD_REQUEST, message);
        return UUID.fromString(v);
    }

    public static String oneOf(String v, List<String> allowed) {
        if (v == null || !allowed.contains(v)) {
            throw new UserException(HttpStatus.BAD_REQUEST,
                    "Invalid option: expected one of " + allowed.stream().map(a -> "\"" + a + "\"").collect(Collectors.joining("|")));
        }
        return v;
    }

    public static String str(String v) {
        if (v == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected string, received undefined");
        return v;
    }

    /** {@code z.string().trim().min(n, msg).max(m, msg2)}. */
    public static String trimmed(String v, int min, String minMsg, int max, String maxMsg) {
        String s = str(v).trim();
        if (s.length() < min) throw new UserException(HttpStatus.BAD_REQUEST, minMsg);
        if (s.length() > max) throw new UserException(HttpStatus.BAD_REQUEST, maxMsg);
        return s;
    }

    public static String isoDate(String v, String message) {
        if (v == null || !v.matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new UserException(HttpStatus.BAD_REQUEST, message);
        return v;
    }
}
