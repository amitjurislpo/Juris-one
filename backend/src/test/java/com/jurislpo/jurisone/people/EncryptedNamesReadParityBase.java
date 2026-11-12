package com.jurislpo.jurisone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The same seeded people are read through the API twice, once with {@code read-encrypted=true} and once with {@code false} (two subclasses, two Spring contexts,
 * one database); every answer must be identical once ids and the per-run suffix are masked. Whichever subclass finishes second does the comparison.
 */
abstract class EncryptedNamesReadParityBase extends IntegrationTest {

    /** Mode → (request → normalised answer). Both subclasses run in the same JVM. */
    private static final Map<String, Map<String, String>> ANSWERS = new ConcurrentHashMap<>();
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    static final String[] FIRST_NAMES = {"Aarav", "zoya", "Meera", "Ravi", "Élodie", "Sanjay", "anita", "Kabir", "Divya", "Farhan"};
    static final String[] LAST_NAMES = {"Mehta", "Khan", "Joshi-Rao", "O'Neil", "Dubois", "Iyer", "100%_Rate", "Naik", "Shah"};
    static final String[] DESIGNATIONS = {"Senior Engineer", "engineer", "Accountant", "HR Manager", "Analyst"};

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    abstract boolean isReadEncrypted();

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    void runScenario() throws Exception {
        String mode = isReadEncrypted() ? "read-encrypted" : "read-plaintext";
        String suffix = TestData.letters(8);
        TestData data = new TestData(jdbc).inBusiness("parity-" + suffix);
        UUID hrRole = data.role("hrm", "self", "dir", "people.view", "people.add", "letters.manage");
        UUID leadRole = data.role("lead", "self", "dir");
        UUID employeeRole = data.role("employee", "self", "dir");
        UUID department = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", department, "Operations", data.business());
        data.employee("HR1", "hr." + suffix + "@example.com", hrRole, "Hema Rao", "HR Head");
        UUID manager = data.employee("MGR1", "mgr." + suffix + "@example.com", leadRole, "Manoj Lead", "Team Lead");
        List<UUID> people = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String name = FIRST_NAMES[i % 10] + " " + LAST_NAMES[(i / 10) * 3 + (i % 10) % 3];
            people.add(data.employee("P" + String.format("%02d", i), "p" + i + "." + suffix + "@example.com", employeeRole, name, DESIGNATIONS[i % 5]));
        }
        for (int i = 0; i < people.size(); i++) {
            UUID reportsTo = i >= 1 && i <= 3 ? manager : i >= 5 ? people.get(i % 5) : null;
            jdbc.update("UPDATE employees SET reporting_manager_id = ?, department_id = ? WHERE id = ?", reportsTo, i % 2 == 0 ? department : null, people.get(i));
        }
        for (int i = 0; i < 12; i++) {
            jdbc.update("INSERT INTO employee_onboardings (id, employee_id, status) VALUES (?, ?, 'NOT_STARTED')", UUID.randomUUID(), people.get(i));
        }
        Cookie hr = signIn("hr." + suffix + "@example.com");
        Cookie lead = signIn("mgr." + suffix + "@example.com");

        Map<String, String> answers = new LinkedHashMap<>();
        for (String query : new String[] {"", "?search=ar", "?search=%25", "?search=_", "?search=100%25_", "?search=ENGINEER", "?search=aarav%20mehta", "?search=Mehta", "?search=o%27neil",
                "?sort=name&sortDirection=desc", "?sort=manager", "?sort=manager&sortDirection=desc", "?sort=code&sortDirection=desc", "?sort=department", "?sort=status&sortDirection=desc",
                "?page=2", "?page=2&sort=name&sortDirection=desc", "?status=Current&search=an&sort=department", "?search=%25%25&page=2"}) {
            answers.put("people" + query, ask("/api/v1/people" + query, hr));
        }
        for (String query : new String[] {"", "?search=eng", "?sort=title", "?sort=title&sortDirection=desc", "?sort=email", "?sort=status", "?page=2", "?search=%25", "?search=_ra", "?sort=name&sortDirection=desc&page=2"}) {
            answers.put("directory" + query, ask("/api/v1/directory" + query, hr));
        }
        for (String query : new String[] {"", "?search=zoya", "?search=%25", "?search=aarav&pageSize=5", "?pageSize=5"}) {
            answers.put("onboardings" + query, ask("/api/v1/onboardings" + query, hr));
        }
        for (String query : new String[] {"", "?search=an", "?sort=title&sortDirection=desc", "?sort=name&sortDirection=desc"}) {
            answers.put("team" + query, ask("/api/v1/team" + query, lead));
        }
        for (int i : new int[] {0, 5, 17}) {
            answers.put("person" + i, ask("/api/v1/people/" + people.get(i), hr));
        }
        answers.put("export", ask("/api/v1/people/export", hr));
        answers.put("export-search", ask("/api/v1/people/export?search=eng&sort=manager", hr));
        answers.put("form-options", ask("/api/v1/people/form-options", hr));
        answers.put("form-options-edit", ask("/api/v1/people/form-options?id=" + people.get(7), hr));
        answers.put("letter-employees", ask("/api/v1/letters/employees", hr));

        for (Map.Entry<String, String> answer : answers.entrySet()) {
            answer.setValue(answer.getValue().replace(suffix, "SFX"));
        }
        answers.values().forEach(text -> assertThat(text).doesNotContain("\"status\":500"));
        ANSWERS.put(mode, answers);
        Map<String, String> other = ANSWERS.get(isReadEncrypted() ? "read-plaintext" : "read-encrypted");
        if (other != null) {
            assertThat(answers.keySet()).isEqualTo(other.keySet());
            for (String request : answers.keySet()) {
                assertThat(answers.get(request)).as("the answer to " + request).isEqualTo(other.get(request));
            }
        }
    }

    private String ask(String path, Cookie cookie) throws Exception {
        var response = mockMvc.perform(get(path).cookie(cookie)).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        return response.getStatus() + " " + UUID_TEXT.matcher(body).replaceAll("<uuid>");
    }
}
