package com.jurislpo.jurisone.common;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * List state from the query string: ?search=&sort=&sortDirection=&page= — invalid values fall back silently.
 */
public record ListParams(String search, String sort, String sortDirection, int page, int skip, int take) {

    /** {@code str(queryParams, key, defaultValue)}: first value, cut to 200 chars. */
    public static String text(Map<String, String> queryParams, String key, String defaultValue) {
        String value = queryParams.get(key);
        if (value == null) return defaultValue;
        return value.substring(0, Math.min(200, value.length()));
    }

    /** {@code pick}: the value if allowed, else the default. */
    public static String pick(Map<String, String> queryParams, String key, List<String> allowed, String defaultValue) {
        String value = text(queryParams, key, "");
        return allowed.contains(value) ? value : defaultValue;
    }

    public static ListParams of(Map<String, String> queryParams, List<String> sorts, String defaultSort, int pageSize, String defaultDirection) {
        int page;
        try {
            page = Integer.parseInt(text(queryParams, "page", "1").trim().replaceAll("^(\\d+).*$", "$1"));
        } catch (NumberFormatException e) {
            page = 1;
        }
        page = Math.max(1, Math.min(10_000, page == 0 ? 1 : page));
        return new ListParams(text(queryParams, "search", "").trim(), pick(queryParams, "sort", sorts, defaultSort), pick(queryParams, "sortDirection", List.of("asc", "desc"), defaultDirection), page,
                (page - 1) * pageSize, pageSize);
    }

    public static ListParams of(Map<String, String> queryParams, List<String> sorts, String defaultSort) {
        return of(queryParams, sorts, defaultSort, 25, "asc");
    }

    public boolean asc() {
        return "asc".equals(sortDirection);
    }

    /** JS {@code (x > y ? 1 : x < y ? -1 : 0) * sortDirection} on the chosen key; stable. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> Comparator<T> comparator(Map<String, Function<T, ? extends Comparable>> keys) {
        Function<T, ? extends Comparable> key = keys.get(sort);
        Comparator<T> comparator = (first, second) -> {
            Comparable firstKey = key.apply(first), secondKey = key.apply(second);
            if (firstKey == null && secondKey == null) return 0;
            if (firstKey == null) return -1;
            if (secondKey == null) return 1;
            return Integer.signum(firstKey.compareTo(secondKey));
        };
        return asc() ? comparator : comparator.reversed();
    }
}
