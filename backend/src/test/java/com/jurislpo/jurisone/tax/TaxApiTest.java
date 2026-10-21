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

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    TestData data;
    UUID empRole, finRole;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        empRole = data.role("tax-employee", "self", "dir");
        finRole = data.role("tax-finance", "self", "dir", "people.view", "tax.view", "proofs.verify");
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    static int seq = 8000;

    record Person(UUID id, Cookie cookie) {}

    Person person(UUID role) throws Exception {
        String c = "JC-" + (++seq) + (int) (Math.random() * 1000);
        UUID id = data.employee(c, c.toLowerCase() + "@example.com", role);
        String set = SignIn.session(mvc, c.toLowerCase() + "@example.com").andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Person(id, new Cookie("jo_at", m.group(1)));
    }

    MockHttpServletRequestBuilder upload(Person p, String item, byte[] bytes, String name) {
        return multipart("/api/declarations/proof").file(new MockMultipartFile("file", name, "application/pdf", bytes)).param("item", item).cookie(p.cookie())
                .header("origin", "http://localhost:3005").header("host", "localhost:3005");
    }

    @Test
    void declareProveSubmitAndVerify() throws Exception {
        Person emp = person(empRole), fin = person(finRole);

        mvc.perform(write(put("/api/declarations/items/bogus")).cookie(emp.cookie()).content("{\"amount\":10}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Unknown item"));
        mvc.perform(write(put("/api/declarations/items/ppf")).cookie(emp.cookie()).content("{\"amount\":10.5}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Whole rupees only"));
        mvc.perform(write(put("/api/declarations/items/ppf")).cookie(emp.cookie()).content("{\"amount\":50000}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT amount FROM declaration_items WHERE employee_id = ? AND item_key = 'ppf'", Long.class, emp.id())).isEqualTo(5_000_000L);

        // only the old regime submits; every draft needs a proof
        mvc.perform(write(post("/api/declarations/submissions")).cookie(emp.cookie()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Declarations only matter on the old regime."));
        mvc.perform(write(put("/api/declarations/regime")).cookie(emp.cookie()).content("{\"regime\":\"OLD\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("You're on the old regime — TDS updates from the next payroll"));
        mvc.perform(write(post("/api/declarations/submissions")).cookie(emp.cookie()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Attach a proof for: Public provident fund (PPF)."));

        // content, not the extension, decides the type
        mvc.perform(upload(emp, "ppf", "hello".getBytes(), "x.pdf")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Upload a PDF, JPG or PNG."));
        mvc.perform(upload(emp, "ppf", PDF, "ppf receipt ✓.pdf")).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("ppf receipt _.pdf"));

        mvc.perform(write(post("/api/declarations/submissions")).cookie(emp.cookie()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.msg").value("1 item sent to Finance for verification"));
        mvc.perform(write(put("/api/declarations/items/ppf")).cookie(emp.cookie()).content("{\"amount\":60000}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("This item is waiting for Finance — you can change it after they decide."));

        String itemId = jdbc.queryForObject("SELECT id::text FROM declaration_items WHERE employee_id = ? AND item_key = 'ppf'", String.class, emp.id());

        // the owner and Finance can open the proof; another employee can't
        mvc.perform(get("/api/declarations/proof/" + itemId).cookie(emp.cookie())).andExpect(status().isOk()).andExpect(content().bytes(PDF))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "application/pdf"));
        mvc.perform(get("/api/declarations/proof/" + itemId).cookie(fin.cookie())).andExpect(status().isOk());
        mvc.perform(get("/api/declarations/proof/" + itemId).cookie(person(empRole).cookie())).andExpect(status().isForbidden());
        mvc.perform(get("/api/declarations/proof/not-an-id").cookie(emp.cookie())).andExpect(status().isNotFound());

        // Finance: a rejection needs a reason; you can't verify your own
        mvc.perform(write(put("/api/proofs/items/" + itemId + "/decision")).cookie(fin.cookie()).content("{\"approve\":false}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Give a reason so the employee can fix it."));
        mvc.perform(write(put("/api/proofs/" + fin.id() + "/decision")).cookie(fin.cookie()).content("{\"approve\":true}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("You can't verify your own proofs."));
        mvc.perform(get("/api/proofs").cookie(fin.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[?(@.id == '" + emp.id() + "')].pend").value(1));
        mvc.perform(get("/api/proofs/" + emp.id()).cookie(fin.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].label").value("Public provident fund (PPF)")).andExpect(jsonPath("$.data.items[0].sec").value("80C"))
                .andExpect(jsonPath("$.data.items[0].hasProof").value(true)).andExpect(jsonPath("$.data.tdsNow").doesNotExist());
        mvc.perform(write(put("/api/proofs/" + emp.id() + "/decision")).cookie(fin.cookie()).content("{\"approve\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("1 proof approved"));
        mvc.perform(write(put("/api/proofs/items/" + itemId + "/decision")).cookie(fin.cookie()).content("{\"approve\":true}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("This item was already decided."));
        assertThat(jdbc.queryForObject("SELECT text FROM notifications WHERE employee_id = ?", String.class, emp.id()))
                .isEqualTo("1 investment proof approved — your TDS goes down from the next payroll");

        // an employee can't use Finance's routes
        mvc.perform(get("/api/proofs/" + emp.id()).cookie(emp.cookie())).andExpect(status().isForbidden());
    }

    @Test
    void financeOverridesTheRegimeAndPreviousEmployer() throws Exception {
        Person emp = person(empRole), fin = person(finRole);
        mvc.perform(write(put("/api/tax/" + emp.id() + "/regime")).cookie(fin.cookie()).content("{\"regime\":\"OLD\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("Regime set to old — TDS updates from this payroll"));
        mvc.perform(write(put("/api/declarations/regime")).cookie(emp.cookie()).content("{\"regime\":\"NEW\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Finance has set your regime. Contact Finance to change it."));
        mvc.perform(write(put("/api/tax/" + emp.id() + "/previous-employer")).cookie(fin.cookie()).content("{\"income\":100,\"tds\":200}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("TDS can't be more than the income."));
        mvc.perform(write(put("/api/tax/" + emp.id() + "/previous-employer")).cookie(fin.cookie()).content("{\"income\":300000,\"tds\":20000}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT prev_employer_income, prev_employer_tds FROM tax_profiles WHERE employee_id = ?", emp.id()).get(0))
                .containsEntry("prev_employer_income", 30_000_000L).containsEntry("prev_employer_tds", 2_000_000L);
        mvc.perform(get("/api/tax/" + emp.id()).cookie(fin.cookie()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("No salary structure — nothing to compute yet."));
        mvc.perform(get("/api/tax/export").cookie(fin.cookie())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Employee ID,Name,Regime,Projected income")));
        mvc.perform(get("/api/tax/export").cookie(emp.cookie())).andExpect(status().isForbidden());
    }
}
