package com.jurislpo.jurisone.people;

import org.junit.jupiter.api.Test;

/** The API answers with {@code read-encrypted=true} (the test default); compared with {@link EncryptedNamesReadOffParityTest}. */
class EncryptedNamesReadOnParityTest extends EncryptedNamesReadParityBase {

    @Override
    boolean isReadEncrypted() {
        return true;
    }

    @Test
    void answersMatchTheOnesFromThePlaintextColumns() throws Exception {
        runScenario();
    }
}
