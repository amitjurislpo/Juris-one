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
    public static String cell(Object value) {
        String text = value == null ? "" : stringOf(value);
        if (FORMULA.matcher(text).find()) text = "'" + text;
        return NEEDS_QUOTES.matcher(text).find() ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    /** JS number → string: integers without ".0". */
    static String stringOf(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return Long.toString(d.longValue());
        if (value instanceof Float f && f == Math.rint(f)) return Long.toString(f.longValue());
        return String.valueOf(value);
    }

    public static String toCsv(List<String> header, List<? extends List<?>> rows) {
        List<List<?>> all = new ArrayList<>();
        all.add(header);
        all.addAll(rows);
        StringBuilder builder = new StringBuilder("﻿");
        for (List<?> r : all) {
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) builder.append(',');
                builder.append(cell(r.get(i)));
            }
            builder.append("\r\n");
        }
        return builder.toString();
    }
}
