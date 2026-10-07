package com.jurislpo.jurisone.common;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * List state from the query string (src/lib/list.ts): ?q=&sort=&dir=&page= — invalid values fall back silently.
 */
public record ListParams(String q, String sort, String dir, int page, int skip, int take) {

    /** {@code str(sp, k)}: first value, cut to 200 chars. */
    public static String str(Map<String, String> sp, String k, String dflt) {
        String v = sp.get(k);
        if (v == null) return dflt;
        return v.substring(0, Math.min(200, v.length()));
    }

    /** {@code pick}: the value if allowed, else the default. */
    public static String pick(Map<String, String> sp, String k, List<String> allowed, String dflt) {
        String v = str(sp, k, "");
        return allowed.contains(v) ? v : dflt;
    }

    public static ListParams of(Map<String, String> sp, List<String> sorts, String dflt, int pageSize, String dfltDir) {
        int page;
        try {
            page = Integer.parseInt(str(sp, "page", "1").trim().replaceAll("^(\\d+).*$", "$1"));
        } catch (NumberFormatException e) {
            page = 1;
        }
        page = Math.max(1, Math.min(10_000, page == 0 ? 1 : page));
        return new ListParams(str(sp, "q", "").trim(), pick(sp, "sort", sorts, dflt), pick(sp, "dir", List.of("asc", "desc"), dfltDir), page,
                (page - 1) * pageSize, pageSize);
    }

    public static ListParams of(Map<String, String> sp, List<String> sorts, String dflt) {
        return of(sp, sorts, dflt, 25, "asc");
    }

    public boolean asc() {
        return "asc".equals(dir);
    }

    /** JS {@code (x > y ? 1 : x < y ? -1 : 0) * dir} on the chosen key; stable. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> Comparator<T> comparator(Map<String, Function<T, ? extends Comparable>> keys) {
        Function<T, ? extends Comparable> key = keys.get(sort);
        Comparator<T> c = (a, b) -> {
            Comparable x = key.apply(a), y = key.apply(b);
            if (x == null && y == null) return 0;
            if (x == null) return -1;
            if (y == null) return 1;
            return Integer.signum(x.compareTo(y));
        };
        return asc() ? c : c.reversed();
    }
}
