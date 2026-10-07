package com.jurislpo.jurisone.crypto;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * argon2id with the parameters of src/lib/password.ts (@node-rs/argon2: m=19456 KiB, t=2, p=1, 16-byte salt,
 * 32-byte hash). Verification reads the parameters from the stored PHC string, so every existing hash works.
 */
@Component
public class Passwords {

    public static final String RULES = "At least 10 characters, with a letter and a number or symbol.";

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private volatile String dummy;

    public String hash(String pw) {
        return encoder.encode(pw);
    }

    public boolean verify(String stored, String pw) {
        if (stored == null || stored.isEmpty()) return false;
        try {
            return encoder.matches(pw, stored);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A hash to verify against when the account doesn't exist, so timing doesn't reveal it. */
    public String dummyHash() {
        String d = dummy;
        if (d == null) dummy = d = hash("timing-equaliser-not-a-real-password");
        return d;
    }

    /** The policy message, or null when the password is acceptable. */
    public static String problem(String pw) {
        if (pw.length() < 10) return "Use at least 10 characters.";
        if (pw.length() > 200) return "That password is too long.";
        if (!pw.matches("(?s).*[A-Za-z].*") || !pw.matches("(?s).*[^A-Za-z].*")) return "Include at least one letter and one number or symbol.";
        return null;
    }
}
