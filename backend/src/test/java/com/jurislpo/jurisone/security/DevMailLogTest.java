package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.service.MailService;
import com.jurislpo.jurisone.service.MailTemplates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/** With no mail host (dev) the mail service logs one masked line and never the body; the full text stays readable in email_outbox. */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Dev mail log")
class DevMailLogTest extends IntegrationTest {

    @Autowired MailService mail;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("the console shows recipient (masked), subject and length, never the name, code or link in the body")
    void bodyIsNeverLogged(CapturedOutput output) {
        String text = "Hi Zubin Quartermaine,\n\nYour one-time code is 483920. Open https://acme.example.com/onboarding/tokenABCDEF123456 to continue.";
        mail.send("zubin.quartermaine@example.com", new MailTemplates.Content("Your JurisOne sign-in code", text, "<p>Hi Zubin Quartermaine</p>"));

        assertThat(output.getAll())
                .contains("[email:dev]", "to=z***@example.com", "subject=Your JurisOne sign-in code", "bodyLength=" + text.length())
                .doesNotContain("Zubin", "Quartermaine", "zubin.quartermaine", "483920", "tokenABCDEF123456", "onboarding/");
        // tests (and developers) read what was sent from the outbox table instead
        assertThat(jdbc.queryForObject("SELECT text FROM email_outbox WHERE \"to\" = ?", String.class, "zubin.quartermaine@example.com")).isEqualTo(text);
    }
}
