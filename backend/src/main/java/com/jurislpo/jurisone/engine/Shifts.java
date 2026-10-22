package com.jurislpo.jurisone.engine;

import java.util.List;
import java.util.Map;

/** Shift labels. */
public final class Shifts {
    private Shifts() {}

    static String twelveHourLabel(String time) {
        String[] parts = time.split(":");
        int hours = Integer.parseInt(parts[0]), minutes = Integer.parseInt(parts[1]);
        return ((hours + 11) % 12 + 1) + ":" + String.format(java.util.Locale.ROOT, "%02d", minutes) + " " + (hours < 12 ? "AM" : "PM");
    }

    /** "Night · 7:30 PM – 4:30 AM IST"; "—" when there is no shift. */
    public static String label(String code, String startLocal, String endLocal) {
        if (code == null) return "—";
        String shortName = "IN_NIGHT".equals(code) ? "Night" : "Day";
        return shortName + " · " + twelveHourLabel(startLocal) + " – " + twelveHourLabel(endLocal) + ("US_DAY".equals(code) ? " local" : " IST");
    }

    public record Option(String value, String label) {}

    public static final Map<String, List<Option>> OPTIONS = Map.of(
            "IN", List.of(new Option("IN_NIGHT", "Night · 7:30 PM – 4:30 AM IST"), new Option("IN_DAY", "Day · 9:30 AM – 6:30 PM IST")),
            "US", List.of(new Option("US_DAY", "Day · 9:00 AM – 6:00 PM local")));
}
