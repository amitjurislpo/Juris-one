package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The real console output of the running application (logback-spring.xml), not just the masking function. */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Log masking in the application's console output")
class LogMaskingOutputTest extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger("com.jurislpo.jurisone.test.Hostile");

    @Test
    @DisplayName("personal data in a message or an exception never reaches the console")
    void consoleIsMasked(CapturedOutput output) {
        log.error("could not save meera.iyer@example.com pan ABCDE1234F", new IllegalStateException("Key (email)=(secret.person@example.com) already exists. aadhaar 234567891234"));
        assertThat(output.getAll())
                .contains("could not save m***@example.com pan XXXXXX234F", "Key (email)=(***) already exists", "XXXX1234")
                .doesNotContain("meera.iyer", "ABCDE1234F", "secret.person", "234567891234");
    }
}
