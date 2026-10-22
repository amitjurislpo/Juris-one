package com.jurislpo.jurisone.crypto;

import com.jurislpo.jurisone.crypto.Masks;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.crypto.Tokens;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of tests/unit/crypto.test.ts + compatibility with values produced by the Node code. */
@DisplayName("PII encryption")
class CryptoTest {

    /** Throwaway test key (32 × 0x07), the one used to produce NODE_CIPHERTEXT (a ciphertext made by the original Node implementation). */
    static final String TEST_KEY = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=";
    static final String NODE_CIPHERTEXT = "v1.AwMDAwMDAwMDAwMD.ztCOsv-s2wt0laFaIVS8WQ.F82XNnoeaXpDYHJu2GM";
    final PiiCrypto crypto = new PiiCrypto(TEST_KEY);

    @Test
    @DisplayName("round-trips and never stores plaintext")
    void roundTrip() {
        String aadhaar = "234567891234";
        String encrypted = crypto.encrypt(aadhaar);
        assertThat(encrypted).doesNotContain(aadhaar).startsWith("v1.");
        assertThat(crypto.decrypt(encrypted)).isEqualTo(aadhaar);
    }

    @Test
    @DisplayName("uses a fresh IV each time")
    void freshIv() {
        assertThat(crypto.encrypt("ABCDE1234F")).isNotEqualTo(crypto.encrypt("ABCDE1234F"));
    }

    @Test
    @DisplayName("rejects tampered ciphertext")
    void tampered() {
        String[] parts = crypto.encrypt("ABCDE1234F").split("\\.");
        parts[3] = Base64.getUrlEncoder().withoutPadding().encodeToString("tampered".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> crypto.decrypt(String.join(".", parts))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("treats empty values as null")
    void empty() {
        assertThat(crypto.encrypt("")).isNull();
        assertThat(crypto.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("masks to the last four only")
    void masks() {
        assertThat(PiiCrypto.last4("2345 6789 1234")).isEqualTo("1234");
        assertThat(Masks.aadhaar(PiiCrypto.last4("234567891234"))).isEqualTo("XXXX XXXX 1234");
        assertThat(Masks.pan(PiiCrypto.last4("ABCDE1234F"))).isEqualTo("XXXXXX234F");
    }

    @Test
    @DisplayName("hashes tokens deterministically")
    void hashesWithSha256() {
        assertThat(Tokens.sha256("abc")).hasSize(64).isEqualTo(Tokens.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"); // Node createHash
    }

    @Test
    @DisplayName("decrypts ciphertext written by the Node app")
    void nodeCompat() {
        assertThat(crypto.decrypt(NODE_CIPHERTEXT)).isEqualTo("2345 6789 1234");
    }
}
