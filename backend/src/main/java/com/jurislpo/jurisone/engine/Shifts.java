package com.jurislpo.jurisone.engine;

import java.util.List;
import java.util.Map;

/** Shift labels, port of src/lib/shifts.ts. */
public final class Shifts {
    private Shifts() {}

    static String t12(String hm) {
        String[] p = hm.split(":");
        int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
        return ((h + 11) % 12 + 1) + ":" + String.format(java.util.Locale.ROOT, "%02d", m) + " " + (h < 12 ? "AM" : "PM");
    }

    /** "Night · 7:30 PM – 4:30 AM IST"; "—" when there is no shift. */
    public static String label(String code, String startLocal, String endLocal) {
        if (code == null) return "—";
        String shortName = "IN_NIGHT".equals(code) ? "Night" : "Day";
        return shortName + " · " + t12(startLocal) + " – " + t12(endLocal) + ("US_DAY".equals(code) ? " local" : " IST");
    }

    public record Option(String v, String l) {}

    public static final Map<String, List<Option>> OPTIONS = Map.of(
            "IN", List.of(new Option("IN_NIGHT", "Night · 7:30 PM – 4:30 AM IST"), new Option("IN_DAY", "Day · 9:30 AM – 6:30 PM IST")),
            "US", List.of(new Option("US_DAY", "Day · 9:00 AM – 6:00 PM local")));
}
