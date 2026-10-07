package com.jurislpo.jurisone.engine;

/** Numeric helpers with JavaScript semantics, so ported maths gives identical results. */
public final class Num {
    private Num() {}

    /** JS {@code Math.round}: halves round toward +∞ (Java's {@link Math#round(double)} matches). */
    public static long jsRound(double x) {
        return Math.round(x);
    }

    /** {@code Math.round(n * 100) / 100} — the app's round2. */
    public static double round2(double n) {
        return Math.round(n * 100) / 100.0;
    }
}
