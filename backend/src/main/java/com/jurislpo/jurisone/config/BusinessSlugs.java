package com.jurislpo.jurisone.config;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The rules for a business's short name (the slug, column {@code businesses.code}) that becomes its web address
 * ({@code <slug>.<base-domain>}): a DNS label of 3 to 40 lowercase letters, digits and hyphens, not starting or ending with
 * a hyphen, no hyphens at positions 3 and 4 (so no {@code xn--} punycode), and not one of the reserved names. The same rules
 * are enforced in the database (migration V24, except the reserved list, which lives only here).
 */
public final class BusinessSlugs {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 40;

    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9]([a-z0-9-]{1,38}[a-z0-9])$");

    /** Names that must never be a business's address: platform hosts, infrastructure and product names. */
    public static final Set<String> RESERVED = Set.of("admin", "www", "api", "app", "mail", "smtp", "static", "assets", "cdn", "login", "auth", "sso", "status",
            "support", "help", "billing", "platform", "jurisone", "juristrack", "track", "one", "test", "staging", "dev", "uat", "sqe", "prd", "localhost", "ns1", "ns2");

    private BusinessSlugs() {}

    /** True for a reserved name or anything that looks like punycode ({@code xn--…}). */
    public static boolean isReserved(String slug) {
        return RESERVED.contains(slug) || slug.startsWith("xn--");
    }

    /** What is wrong with the slug, or null when it is acceptable (the slug is already trimmed and lowercased). */
    public static String problem(String slug) {
        if (slug == null || slug.length() < MIN_LENGTH || slug.length() > MAX_LENGTH) {
            return "The short name is " + MIN_LENGTH + " to " + MAX_LENGTH + " characters long.";
        }
        if (!FORMAT.matcher(slug).matches()) {
            return "The short name uses lowercase letters, numbers and dashes, and does not start or end with a dash.";
        }
        if (slug.startsWith("--", 2)) {
            return "The short name can't have dashes in its third and fourth place.";
        }
        if (isReserved(slug)) return "\"" + slug + "\" is reserved. Choose another short name.";
        return null;
    }

    /**
     * A safe slug from a business name: "Juris Consultants" becomes "juris-consultants". A result that is empty, too short or
     * reserved gets a fixed fallback ("business", or the name followed by "-co"); a taken one gets "-2", "-3"… ({@code isTaken}).
     */
    public static String derive(String name, Predicate<String> isTaken) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.length() > MAX_LENGTH) base = base.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        if (base.isEmpty()) base = "business";
        if (base.length() < MIN_LENGTH || isReserved(base)) base = base + "-co";
        String candidate = base;
        for (int number = 2; isTaken.test(candidate); number++) {
            String ending = "-" + number;
            candidate = base.substring(0, Math.min(base.length(), MAX_LENGTH - ending.length())).replaceAll("-+$", "") + ending;
        }
        return candidate;
    }
}
