package com.jurislpo.jurisone.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    /** The most characters of a typed short name that are looked at; anything longer is cut before any work is done on it. */
    public static final int MAX_INPUT_LENGTH = 100;
    /** The most suggestions the availability check offers. */
    public static final int MAX_SUGGESTIONS = 5;

    private static final String[] SUGGESTED_ENDINGS = {"-2", "-hr", "-co", "-india", "-group", "-3"};

    /** Lowercase letters, digits and single dashes only, with no dash at either end ("Acme & Sons (P) Ltd." becomes "acme-sons-p-ltd"); may be empty. */
    static String clean(String text) {
        String cut = text == null ? "" : text.substring(0, Math.min(text.length(), MAX_INPUT_LENGTH));
        return cut.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }

    /**
     * A safe slug from a business name: "Juris Consultants" becomes "juris-consultants". A result that is empty, too short or
     * reserved gets a fixed fallback ("business", or the name followed by "-co"). It does not look at what is taken: a taken
     * slug is refused by the caller, never silently numbered.
     */
    public static String derive(String name) {
        String base = clean(name);
        if (base.length() > MAX_LENGTH) base = base.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        if (base.isEmpty()) base = "business";
        if (base.length() < MIN_LENGTH || isReserved(base)) base = base + "-co";
        return base;
    }

    /**
     * Slugs to offer instead of a typed one, best first, every one valid and unique (not yet checked against what is taken):
     * the cleaned text itself when it differs from the input, the cleaned text with a short ending, and the initials of its words.
     * More than {@link #MAX_SUGGESTIONS} are returned so that taken ones can be dropped.
     */
    public static List<String> suggestionCandidates(String typed) {
        String base = clean(typed);
        Set<String> found = new LinkedHashSet<>();
        if (base.isEmpty()) return List.of();
        if (base.length() > MAX_LENGTH - 8) base = base.substring(0, MAX_LENGTH - 8).replaceAll("-+$", "");
        found.add(base);
        for (String ending : SUGGESTED_ENDINGS) found.add(base + ending);
        String[] words = base.split("-");
        if (words.length >= 3) {
            StringBuilder initials = new StringBuilder();
            for (String word : words) initials.append(word.charAt(0));
            found.add(initials.toString());
            found.add(initials + "-co");
        }
        return found.stream().filter(candidate -> problem(candidate) == null).filter(candidate -> !candidate.equals(typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT))).toList();
    }
}
