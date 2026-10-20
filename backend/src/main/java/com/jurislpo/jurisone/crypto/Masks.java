package com.jurislpo.jurisone.crypto;

/** Masked display formats. Only the last four characters are ever stored in clear. */
public final class Masks {
    private Masks() {}

    private static boolean has(String l4) {
        return l4 != null && !l4.isEmpty();
    }

    public static String aadhaar(String l4) { return has(l4) ? "XXXX XXXX " + l4 : "—"; }
    public static String pan(String l4) { return has(l4) ? "XXXXXX" + l4 : "—"; }
    public static String uan(String l4) { return has(l4) ? "XXXX XXXX " + l4 : "—"; }
    public static String phone(String l4) { return has(l4) ? "•••• •• " + l4 : "—"; }
    public static String bank(String l4) { return has(l4) ? "XXXX XXXX " + l4 : "—"; }
}
