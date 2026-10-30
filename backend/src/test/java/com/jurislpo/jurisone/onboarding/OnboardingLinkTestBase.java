package com.jurislpo.jurisone.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** HR starts an onboarding; the invitation mail's link is checked against what the address settings say it must be. */
abstract class OnboardingLinkTestBase extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    /** The link the invitation must carry for a business with this code. */
    abstract String expectedLink(String businessCode);

    @Test
    void theInvitationLinkFollowsTheAddressSettings() throws Exception {
        String suffix = TestData.letters(8);
        String businessCode = "link-" + suffix;
        TestData data = new TestData(jdbc).inBusiness(businessCode);
        UUID employeeRoleId = data.role("employee", "self", "dir");
        UUID hrRoleId = data.role("hrm", "self", "dir", "people.view", "people.add");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRoleId, employeeRoleId);
        if (jdbc.queryForObject("SELECT count(*) FROM shifts WHERE code = 'IN_NIGHT'", Integer.class) == 0) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) VALUES (?, 'IN_NIGHT', 'Night', '19:30', '04:30', 'IN'::\"Region\")", UUID.randomUUID());
        }
        UUID departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", departmentId, "Legal " + suffix, data.business());
        UUID managerId = data.employee("MG-" + suffix, "mg." + suffix + "@example.com", employeeRoleId);
        data.employee("HR-" + suffix, "hr." + suffix + "@example.com", hrRoleId);
        String set = SignIn.session(mockMvc, "hr." + suffix + "@example.com").andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        Cookie hr = new Cookie("jo_at", matcher.group(1));

        String email = "new." + suffix + "@example.com";
        String body = """
            {"firstName":"Meera","lastName":"Joshi","workEmail":"%s","phoneCountry":"+91","phone":"98765 43210","location":"IN_IST","designation":"Associate",
             "departmentId":"%s","managerId":"%s","dateOfJoining":"2031-10-12","roleId":"%s"}""".formatted(email, departmentId, managerId, employeeRoleId);
        mockMvc.perform(post("/api/v1/onboardings").cookie(hr).header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        String text = jdbc.queryForObject("SELECT text FROM email_outbox WHERE \"to\" = ?", String.class, email);
        assertThat(text).contains(expectedLink(businessCode));
    }
}
