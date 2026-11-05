package com.jurislpo.jurisone.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PiiKeyRotationTest {

    static final String KEY_A = FileCryptoTest.KEY_A;
    static final String KEY_B = FileCryptoTest.KEY_B;
    static final String KEY_C = FileCryptoTest.KEY_C;

    final PiiCrypto oldCrypto = new PiiCrypto(KEY_A);
    final PiiCrypto rotated = new PiiCrypto(new Keyring(KEY_B, KEY_A));

    @Test
    void oldValuesStillReadAfterANewKeyIsAddedAndNewWritesUseTheNewKey() {
        String oldValue = oldCrypto.encrypt("ABCPE1234F");
        assertThat(rotated.decrypt(oldValue)).isEqualTo("ABCPE1234F");
        assertThat(rotated.decryptOrLegacy(oldValue)).isEqualTo("ABCPE1234F");
        String newValue = rotated.encrypt("234567891234");
        assertThat(PiiCrypto.isEncrypted(newValue)).isTrue();
        assertThatThrownBy(() -> oldCrypto.decrypt(newValue)).isInstanceOf(IllegalArgumentException.class); // really written with the new key
        assertThat(new PiiCrypto(KEY_B).decrypt(newValue)).isEqualTo("234567891234");
    }

    @Test
    void rotateMovesAValueToTheNewestKeyAndIsIdempotent() {
        String oldValue = oldCrypto.encrypt("ABCPE1234F");
        assertThat(rotated.isOnNewestKey(oldValue)).isFalse();
        String moved = rotated.rotate(oldValue);
        assertThat(moved).isNotEqualTo(oldValue);
        assertThat(rotated.isOnNewestKey(moved)).isTrue();
        assertThat(new PiiCrypto(KEY_B).decrypt(moved)).isEqualTo("ABCPE1234F"); // no old key needed any more
        assertThat(rotated.isOnNewestKey(null)).isTrue();
    }

    @Test
    void aValueFromAnUnknownKeyOrATamperedValueIsRefused() {
        String stranger = new PiiCrypto(KEY_C).encrypt("secret");
        assertThatThrownBy(() -> rotated.decrypt(stranger)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        assertThatThrownBy(() -> rotated.isOnNewestKey(stranger)).isInstanceOf(IllegalArgumentException.class);
        String[] parts = oldCrypto.encrypt("ABCPE1234F").split("\\.");
        String tampered = String.join(".", parts[0], parts[1], parts[2], (parts[3].startsWith("A") ? "B" : "A") + parts[3].substring(1)); // first character: the last one carries padding bits only
        assertThatThrownBy(() -> rotated.decrypt(tampered)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rotated.isOnNewestKey("plain text")).isInstanceOf(IllegalArgumentException.class);
    }
}
