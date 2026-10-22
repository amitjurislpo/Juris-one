package com.jurislpo.jurisone.crypto;

/** Masked display formats. Only the last four characters are ever stored in clear. */
public final class Masks {
    private Masks() {}

    private static boolean hasLastFour(String lastFour) {
        return lastFour != null && !lastFour.isEmpty();
    }

    public static String aadhaar(String lastFour) { return hasLastFour(lastFour) ? "XXXX XXXX " + lastFour : "—"; }
    public static String pan(String lastFour) { return hasLastFour(lastFour) ? "XXXXXX" + lastFour : "—"; }
    public static String uan(String lastFour) { return hasLastFour(lastFour) ? "XXXX XXXX " + lastFour : "—"; }
    public static String phone(String lastFour) { return hasLastFour(lastFour) ? "•••• •• " + lastFour : "—"; }
    public static String bank(String lastFour) { return hasLastFour(lastFour) ? "XXXX XXXX " + lastFour : "—"; }
}
