package com.jurislpo.jurisone.engine;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code @{employee-uuid}} tokens in a task description or comment (the app's mention picker inserts them). */
public final class Mentions {
    private Mentions() {}

    private static final Pattern TOKEN = Pattern.compile("@\\{([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})}");

    /** The mentioned employee ids, in order of first appearance, without duplicates. */
    public static Set<UUID> find(String text) {
        Set<UUID> found = new LinkedHashSet<>();
        if (text == null) return found;
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) found.add(UUID.fromString(matcher.group(1)));
        return found;
    }
}
