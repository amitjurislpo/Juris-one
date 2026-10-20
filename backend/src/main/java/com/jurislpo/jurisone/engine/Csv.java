package com.jurislpo.jurisone.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** RFC 4180 CSV with spreadsheet formula-injection protection (BOM + CRLF). */
public final class Csv {
    private Csv() {}

    private static final Pattern FORMULA = Pattern.compile("^[=+\\-@\\t\\r]");
    private static final Pattern NEEDS_QUOTES = Pattern.compile("[\",\\n\\r]");

    /** Same as JS {@code String(v)} for the values the exports use (null → ""). */
    public static String cell(Object v) {
        String s = v == null ? "" : stringOf(v);
        if (FORMULA.matcher(s).find()) s = "'" + s;
        return NEEDS_QUOTES.matcher(s).find() ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** JS number → string: integers without ".0". */
    static String stringOf(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return Long.toString(d.longValue());
        if (v instanceof Float f && f == Math.rint(f)) return Long.toString(f.longValue());
        return String.valueOf(v);
    }

    public static String toCsv(List<String> header, List<? extends List<?>> rows) {
        List<List<?>> all = new ArrayList<>();
        all.add(header);
        all.addAll(rows);
        StringBuilder b = new StringBuilder("﻿");
        for (List<?> r : all) {
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) b.append(',');
                b.append(cell(r.get(i)));
            }
            b.append("\r\n");
        }
        return b.toString();
    }
}
