package com.jurislpo.jurisone.people;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The API answers with {@code read-encrypted=true} ; compared with {@link EncryptedNamesReadOffParityTest}. */
@TestPropertySource(properties = "jurisone.security.encrypt-names.read-encrypted=true")
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
