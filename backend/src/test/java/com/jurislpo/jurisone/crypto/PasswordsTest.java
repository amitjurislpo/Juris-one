package com.jurislpo.jurisone.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of tests/unit/password.test.ts + verification of a hash produced by @node-rs/argon2. */
@DisplayName("passwords")
class PasswordsTest {

    final Passwords passwords = new Passwords();
    /** @node-rs/argon2 hash (m=19456, t=2, p=1) of the test password used below. */
    static final String NODE_HASH = "$argon2id$v=19$m=19456,t=2,p=1$rGjPq1aoZucbYwr5QA6Myg$9v5O1AZSRxnv54PDwrmgAzUSt5/eCNPWQBmE+4GHyts";

    @Test
    @DisplayName("hashes with argon2id and verifies")
    void hashAndVerify() {
        String h = passwords.hash("correct-horse-battery-7");
        assertThat(h).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(passwords.verify(h, "correct-horse-battery-7")).isTrue();
        assertThat(passwords.verify(h, "wrong-password")).isFalse();
        assertThat(passwords.verify(null, "x")).isFalse();
    }

    @Test
    @DisplayName("enforces the policy")
    void policy() {
        assertThat(Passwords.problem("short1")).contains("10 characters");
        assertThat(Passwords.problem("onlyletters")).contains("number or symbol");
        assertThat(Passwords.problem("correct-horse")).isNull();
        assertThat(Passwords.problem("Juris2026xyz")).isNull();
    }

    @Test
    @DisplayName("verifies hashes created by the Node app")
    void nodeCompat() {
        assertThat(passwords.verify(NODE_HASH, "correct-horse-battery-7")).isTrue();
        assertThat(passwords.verify(NODE_HASH, "correct-horse-battery-8")).isFalse();
    }
}
