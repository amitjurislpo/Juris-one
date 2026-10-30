package com.jurislpo.jurisone.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Declarations → proof upload → submit → Finance decision, and who may see a proof. */
class TaxApiTest extends IntegrationTest {

    static final byte[] PDF = "%PDF-1.4 test".getBytes();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    TestData data;
    UUID empRole, finRole;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        empRole = data.role("tax-employee", "self", "dir");
        finRole = data.role("tax-finance", "self", "dir", "people.view", "tax.view", "proofs.verify");
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    static int sequence = 8000;

    record Person(UUID id, Cookie cookie) {}

    Person person(UUID role) throws Exception {
        String code = "JC-" + (++sequence) + (int) (Math.random() * 1000);
        UUID id = data.employee(code, code.toLowerCase() + "@example.com", role);
        String set = SignIn.session(mockMvc, code.toLowerCase() + "@example.com").andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, new Cookie("jo_at", matcher.group(1)));
    }

    MockHttpServletRequestBuilder upload(Person person, String item, byte[] bytes, String name) {
        return multipart("/api/v1/declarations/proofs").file(new MockMultipartFile("file", name, "application/pdf", bytes)).param("item", item).cookie(person.cookie())
                .header("origin", "http://localhost:3005").header("host", "localhost:3005");
    }

    @Test
    void declareProveSubmitAndVerify() throws Exception {
        Person employee = person(empRole), finance = person(finRole);

        mockMvc.perform(write(put("/api/v1/declarations/items/bogus")).cookie(employee.cookie()).content("{\"amount\":10}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Unknown item"));
        mockMvc.perform(write(put("/api/v1/declarations/items/ppf")).cookie(employee.cookie()).content("{\"amount\":10.5}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Whole rupees only"));
        mockMvc.perform(write(put("/api/v1/declarations/items/ppf")).cookie(employee.cookie()).content("{\"amount\":50000}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT amount FROM declaration_items WHERE employee_id = ? AND item_key = 'ppf'", Long.class, employee.id())).isEqualTo(5_000_000L);

        // only the old regime submits; every draft needs a proof
        mockMvc.perform(write(post("/api/v1/declarations/submissions")).cookie(employee.cookie()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Declarations only matter on the old regime."));
        mockMvc.perform(write(put("/api/v1/declarations/regime")).cookie(employee.cookie()).content("{\"regime\":\"OLD\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("You're on the old regime — TDS updates from the next payroll"));
        mockMvc.perform(write(post("/api/v1/declarations/submissions")).cookie(employee.cookie()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Attach a proof for: Public provident fund (PPF)."));

        // content, not the extension, decides the type
        mockMvc.perform(upload(employee, "ppf", "hello".getBytes(), "x.pdf")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Upload a PDF, JPG or PNG."));
        mockMvc.perform(upload(employee, "ppf", PDF, "ppf receipt ✓.pdf")).andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("ppf receipt _.pdf"));

        mockMvc.perform(write(post("/api/v1/declarations/submissions")).cookie(employee.cookie()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("1 item sent to Finance for verification"));
        mockMvc.perform(write(put("/api/v1/declarations/items/ppf")).cookie(employee.cookie()).content("{\"amount\":60000}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("This item is waiting for Finance — you can change it after they decide."));

        String itemId = jdbc.queryForObject("SELECT id::text FROM declaration_items WHERE employee_id = ? AND item_key = 'ppf'", String.class, employee.id());

        // the owner and Finance can open the proof; another employee can't
        mockMvc.perform(get("/api/v1/declarations/proofs/" + itemId).cookie(employee.cookie())).andExpect(status().isOk()).andExpect(content().bytes(PDF))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "application/pdf"));
        mockMvc.perform(get("/api/v1/declarations/proofs/" + itemId).cookie(finance.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/declarations/proofs/" + itemId).cookie(person(empRole).cookie())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/declarations/proofs/not-an-id").cookie(employee.cookie())).andExpect(status().isNotFound());

        // Finance: a rejection needs a reason; you can't verify your own
        mockMvc.perform(write(put("/api/v1/proofs/items/" + itemId + "/decision")).cookie(finance.cookie()).content("{\"approve\":false}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Give a reason so the employee can fix it."));
        mockMvc.perform(write(put("/api/v1/proofs/" + finance.id() + "/decision")).cookie(finance.cookie()).content("{\"approve\":true}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("You can't verify your own proofs."));
        mockMvc.perform(get("/api/v1/proofs").cookie(finance.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[?(@.id == '" + employee.id() + "')].pending").value(1));
        mockMvc.perform(get("/api/v1/proofs/" + employee.id()).cookie(finance.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].label").value("Public provident fund (PPF)")).andExpect(jsonPath("$.data.items[0].section").value("80C"))
                .andExpect(jsonPath("$.data.items[0].hasProof").value(true)).andExpect(jsonPath("$.data.tdsNow").doesNotExist());
        mockMvc.perform(write(put("/api/v1/proofs/" + employee.id() + "/decision")).cookie(finance.cookie()).content("{\"approve\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("1 proof approved"));
        mockMvc.perform(write(put("/api/v1/proofs/items/" + itemId + "/decision")).cookie(finance.cookie()).content("{\"approve\":true}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("This item was already decided."));
        assertThat(jdbc.queryForObject("SELECT text FROM notifications WHERE employee_id = ?", String.class, employee.id()))
                .isEqualTo("1 investment proof approved — your TDS goes down from the next payroll");

        // an employee can't use Finance's routes
        mockMvc.perform(get("/api/v1/proofs/" + employee.id()).cookie(employee.cookie())).andExpect(status().isForbidden());
    }

    @Test
    void financeOverridesTheRegimeAndPreviousEmployer() throws Exception {
        Person employee = person(empRole), finance = person(finRole);
        mockMvc.perform(write(put("/api/v1/tax/" + employee.id() + "/regime")).cookie(finance.cookie()).content("{\"regime\":\"OLD\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Regime set to old — TDS updates from this payroll"));
        mockMvc.perform(write(put("/api/v1/declarations/regime")).cookie(employee.cookie()).content("{\"regime\":\"NEW\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Finance has set your regime. Contact Finance to change it."));
        mockMvc.perform(write(put("/api/v1/tax/" + employee.id() + "/previous-employer")).cookie(finance.cookie()).content("{\"income\":100,\"tds\":200}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("TDS can't be more than the income."));
        mockMvc.perform(write(put("/api/v1/tax/" + employee.id() + "/previous-employer")).cookie(finance.cookie()).content("{\"income\":300000,\"tds\":20000}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT prev_employer_income, prev_employer_tds FROM tax_profiles WHERE employee_id = ?", employee.id()).get(0))
                .containsEntry("prev_employer_income", 30_000_000L).containsEntry("prev_employer_tds", 2_000_000L);
        mockMvc.perform(get("/api/v1/tax/" + employee.id()).cookie(finance.cookie()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("No salary structure — nothing to compute yet."));
        mockMvc.perform(get("/api/v1/tax/export").cookie(finance.cookie())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Employee ID,Name,Regime,Projected income")));
        mockMvc.perform(get("/api/v1/tax/export").cookie(employee.cookie())).andExpect(status().isForbidden());
    }
}
