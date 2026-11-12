package com.jurislpo.jurisone.people;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The API answers with {@code read-encrypted=false}; compared with {@link EncryptedNamesReadOnParityTest}. */
@TestPropertySource(properties = "jurisone.security.encrypt-names.read-encrypted=false")
class EncryptedNamesReadOffParityTest extends EncryptedNamesReadParityBase {

    @Override
    boolean isReadEncrypted() {
        return false;
    }

    @Test
    void answersMatchTheOnesFromTheEncryptedColumns() throws Exception {
        runScenario();
    }
}
