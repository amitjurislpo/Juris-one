package com.jurislpo.jurisone.exit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.TaxEngine;
import com.jurislpo.jurisone.service.PayrollService;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;

/** Employee exit and full & final settlement: the happy path, who sees amounts, state rules and tenant isolation. */
class ExitApiTest extends IntegrationTest {

    static final String PW = "correct-horse-battery-7";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PayrollService payroll;

    TestData t;
    String s;
    UUID businessId, employeeId, otherEmployeeId;
    Cookie hr, draft, fin, owner, emp, other, otherHr, otherFin;
    LocalDate lwd;
    String ym;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").header("user-agent", "JUnit").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = mvc.perform(write(post("/api/auth/session")).content("{\"identifier\":\"" + email + "\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Cookie("jo_at", m.group(1));
    }

    /** A person with a work email derived from the code; returns the id, the cookie is in the supplied setter. */
    UUID person(TestData td, String code, UUID role) {
        return td.employee(code, code.toLowerCase() + "@example.com", role, PW);
    }

    void structure(UUID employee, long basic, long hra, long lta, long special) {
        jdbc.update("INSERT INTO salary_structures (id, employee_id, effective_from, basic, hra, lta, special, reason) VALUES (?, ?, ?, ?, ?, ?, ?, 'JOINING')",
                UUID.randomUUID(), employee, Date.valueOf(LocalDate.of(2025, 1, 1)), basic, hra, lta, special);
    }

    @BeforeEach
    void setUp() throws Exception {
        s = TestData.letters(8);
        t = new TestData(jdbc).inBusiness("exit-" + s);
        businessId = t.business();
        UUID hrRole = t.role("hrm", "self", "dir", "people.view", "people.add", "exit.manage");
        UUID draftRole = t.role("fe", "self", "dir", "people.view", "pay.view");
        UUID finRole = t.role("fm", "self", "dir", "people.view", "pay.view", "pay.close", "struct.manage", "tax.view");
        UUID ownerRole = t.role("owner", "dir", "roles.manage", "exit.manage", "people.view");
        UUID empRole = t.role("employee", "self", "dir");
        person(t, "XH-" + s, hrRole);
        person(t, "XD-" + s, draftRole);
        person(t, "XF-" + s, finRole);
        person(t, "XO-" + s, ownerRole);
        employeeId = person(t, "XE-" + s, empRole);
        structure(employeeId, 5_000_000, 2_000_000, 500_000, 2_500_000); // ₹1,00,000 a month
        // the last working day was the last day of last month; payroll for that month is still open with no LOP
        lwd = Format.todayIn("Asia/Kolkata").withDayOfMonth(1).minusDays(1);
        ym = YearMonth.from(lwd).toString();
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO payroll_runs (id, business_id, month) VALUES (?, ?, ?)", run, businessId, ym);
        jdbc.update("INSERT INTO payroll_adjustments (run_id, employee_id, lop_days, bonus, other_deduction, updated_at) VALUES (?, ?, 0, 0, 0, now())", run, employeeId);
        // tax tables for that financial year (global, shared by every test)
        String fy = TaxEngine.fyOf(ym).fy();
        jdbc.update("INSERT INTO tax_years (fy, regime, std_deduction, rebate_limit, marginal_relief, cess_rate, slabs, surcharge) VALUES (?, 'NEW', 75000, 1200000, true, 0.04, "
                + "CAST('[{\"upto\":400000,\"rate\":0},{\"upto\":800000,\"rate\":0.05},{\"upto\":1200000,\"rate\":0.1},{\"upto\":null,\"rate\":0.2}]' AS jsonb), CAST('[]' AS jsonb)) ON CONFLICT DO NOTHING", fy);
        jdbc.update("INSERT INTO tax_years (fy, regime, std_deduction, rebate_limit, marginal_relief, cess_rate, slabs, surcharge) VALUES (?, 'OLD', 50000, 500000, false, 0.04, "
                + "CAST('[{\"upto\":250000,\"rate\":0},{\"upto\":500000,\"rate\":0.05},{\"upto\":1000000,\"rate\":0.2},{\"upto\":null,\"rate\":0.3}]' AS jsonb), CAST('[]' AS jsonb)) ON CONFLICT DO NOTHING", fy);
        // 12 days of earned leave on the ledger
        jdbc.update("INSERT INTO leave_types (code, name, annual_quota, accrual_per_month, paid, needs_balance, sort_order) VALUES ('EL', 'Earned leave', 18, 1.5, true, true, 2) ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, at) VALUES (?, ?, 'EL', 12, 'ACCRUAL', now())", UUID.randomUUID(), employeeId);

        hr = signIn("xh-" + s + "@example.com");
        draft = signIn("xd-" + s + "@example.com");
        fin = signIn("xf-" + s + "@example.com");
        owner = signIn("xo-" + s + "@example.com");
        emp = signIn("xe-" + s + "@example.com");

        // another business, to prove isolation
        TestData o = new TestData(jdbc).inBusiness("exit-other-" + s);
        UUID oHr = o.role("hrm", "self", "dir", "people.view", "people.add", "exit.manage");
        UUID oFin = o.role("fm", "self", "dir", "pay.view", "pay.close");
        person(o, "OH-" + s, oHr);
        person(o, "OF-" + s, oFin);
        otherEmployeeId = person(o, "OE-" + s, o.role("employee", "self", "dir"));
        otherHr = signIn("oh-" + s + "@example.com");
        otherFin = signIn("of-" + s + "@example.com");
    }

    // ───────────── helpers ─────────────

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    String resign() throws Exception {
        mvc.perform(write(post("/api/exits/me")).cookie(emp).content("{\"reason\":\"Better opportunity\",\"requestedLastDay\":\"" + Format.todayIn("Asia/Kolkata") + "\"}"))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id::text FROM employee_exits WHERE employee_id = ? ORDER BY created_at DESC LIMIT 1", String.class, employeeId);
    }

    void move(Cookie c, String id, String to, int expected) throws Exception {
        mvc.perform(write(put("/api/exits/" + id + "/status")).cookie(c).content("{\"status\":\"" + to + "\",\"comment\":\"Not needed any more\"}")).andExpect(status().is(expected));
    }

    void notice(String id, int days, String reason) throws Exception {
        mvc.perform(write(put("/api/exits/" + id + "/notice")).cookie(hr)
                .content("{\"lastWorkingDay\":\"" + lwd + "\",\"noticeDays\":" + days + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}")).andExpect(status().isOk());
    }

    /** Resigned → notice (10 days, shortened) → clearance → every item cleared → FNF_PENDING. */
    String toFnfPending() throws Exception {
        String id = resign();
        notice(id, 10, "Buy-out agreed");
        move(hr, id, "NOTICE", 200);
        move(hr, id, "CLEARANCE", 200);
        for (String item : jdbc.queryForList("SELECT id::text FROM exit_clearance_items WHERE exit_id = ?::uuid", String.class, id)) {
            mvc.perform(write(put("/api/exits/" + id + "/clearance/" + item)).cookie(hr).content("{\"cleared\":true,\"remarks\":\"Done\"}")).andExpect(status().isOk());
        }
        move(hr, id, "FNF_PENDING", 200);
        return id;
    }

    String lines(String additions, String deductions) {
        return "{\"additions\":" + additions + ",\"deductions\":" + deductions + ",\"note\":\"Final dues\"}";
    }

    // ───────────── happy path ─────────────

    @Test
    void resignThroughNoticeClearanceSettlementAndClose() throws Exception {
        // the employee resigns; HR is told
        String id = resign();
        mvc.perform(get("/api/exits/me").cookie(emp)).andExpect(status().isOk()).andExpect(jsonPath("$.data.exit.status").value("RESIGNED"))
                .andExpect(jsonPath("$.data.exit.noticeDays").value(30)).andExpect(jsonPath("$.data.canWithdraw").value(true))
                .andExpect(jsonPath("$.data.clearance.length()").value(4)).andExpect(jsonPath("$.data.canResign").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications n JOIN employees e ON e.id = n.employee_id WHERE e.code = ?", Integer.class, "XH-" + s)).isEqualTo(1);
        mvc.perform(get("/api/exits").cookie(hr)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].employeeCode").value("XE-" + s))
                .andExpect(jsonPath("$.data[0].settlementStatus").doesNotExist());

        // accepting needs the last working day; a shortened notice needs a reason
        move(hr, id, "NOTICE", 422);
        mvc.perform(write(put("/api/exits/" + id + "/notice")).cookie(hr).content("{\"lastWorkingDay\":\"" + lwd + "\",\"noticeDays\":10}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Say why the notice is shortened or waived."));
        notice(id, 10, "Buy-out agreed");
        move(hr, id, "NOTICE", 200);
        move(hr, id, "CLEARANCE", 200);

        // clearance: open items hold the exit back; Finance clears its own item only
        move(hr, id, "FNF_PENDING", 422);
        String financeItem = jdbc.queryForObject("SELECT id::text FROM exit_clearance_items WHERE exit_id = ?::uuid AND dept = 'FINANCE'", String.class, id);
        String itItem = jdbc.queryForObject("SELECT id::text FROM exit_clearance_items WHERE exit_id = ?::uuid AND dept = 'IT'", String.class, id);
        mvc.perform(write(put("/api/exits/" + id + "/clearance/" + itItem)).cookie(draft).content("{\"cleared\":true}")).andExpect(status().isForbidden());
        mvc.perform(write(put("/api/exits/" + id + "/clearance/" + financeItem)).cookie(draft).content("{\"cleared\":true,\"remarks\":\"No dues\"}")).andExpect(status().isOk());
        for (String item : jdbc.queryForList("SELECT id::text FROM exit_clearance_items WHERE exit_id = ?::uuid AND NOT cleared", String.class, id)) {
            mvc.perform(write(put("/api/exits/" + id + "/clearance/" + item)).cookie(hr).content("{\"cleared\":true,\"remarks\":\"Done\"}")).andExpect(status().isOk());
        }
        move(hr, id, "FNF_PENDING", 200);

        // HR must never get an amount: the settlement is Finance's (and the owner's)
        mvc.perform(get("/api/exits/" + id + "/settlement").cookie(hr)).andExpect(status().isForbidden());
        mvc.perform(write(put("/api/exits/" + id + "/settlement")).cookie(hr).content(lines("[]", "[]"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/exits/" + id + "/settlement/statement").cookie(hr)).andExpect(status().isForbidden());
        mvc.perform(get("/api/exits/" + id + "/settlement").cookie(emp)).andExpect(status().isForbidden());
        String hrDetail = mvc.perform(get("/api/exits/" + id).cookie(hr)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FNF_PENDING"))
                .andReturn().getResponse().getContentAsString();
        assertThat(hrDetail).doesNotContain("netPaise").doesNotContain("result").doesNotContain("salary").doesNotContain("gratuity").doesNotContain("amount");

        // Finance (pay.view) drafts: bonus 10,000 and an advance of 2,000 recovered
        mvc.perform(write(put("/api/exits/" + id + "/settlement")).cookie(draft).content(lines("[{\"label\":\"Retention bonus\",\"amount\":1000000}]",
                "[{\"label\":\"Salary advance\",\"amount\":200000}]"))).andExpect(status().isOk());
        JsonNode view = body(mvc.perform(get("/api/exits/" + id + "/settlement").cookie(draft)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.canApprove").value(false)).andReturn());
        JsonNode r = view.at("/data/result");
        int days = YearMonth.parse(ym).lengthOfMonth();
        assertThat(r.get("daysInMonth").asInt()).isEqualTo(days);
        assertThat(r.get("salaryEarned").asLong()).isEqualTo(10_000_000L); // the whole last month, no LOP
        assertThat(r.get("encashableDays").asDouble()).isEqualTo(12.0);
        assertThat(r.get("leaveEncashment").asLong()).isEqualTo(Math.round(12.0 * 10_000_000 / days / 100) * 100);
        assertThat(r.get("noticeShortfallDays").asInt()).isEqualTo(10); // notice started today, the last day was before it: nothing served
        assertThat(r.get("noticeRecovery").asLong()).isEqualTo(Math.round(10.0 * 10_000_000 / days / 100) * 100);
        assertThat(r.get("gratuity").asLong()).isZero(); // off by default
        assertThat(r.get("additionsTotal").asLong()).isEqualTo(1_000_000L);
        assertThat(r.get("deductionsTotal").asLong()).isEqualTo(200_000L);
        assertThat(r.get("net").asLong()).isEqualTo(r.get("totalEarnings").asLong() - r.get("totalDeductions").asLong());
        long netDraft = r.get("net").asLong();

        // only pay.close approves
        mvc.perform(write(put("/api/exits/" + id + "/settlement/status")).cookie(draft).content("{\"status\":\"APPROVED\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/exits/" + id + "/settlement").cookie(fin)).andExpect(jsonPath("$.data.canApprove").value(true));
        mvc.perform(get("/api/exits/" + id + "/settlement/statement").cookie(fin)).andExpect(status().isConflict()); // not approved yet
        mvc.perform(write(put("/api/exits/" + id + "/settlement/status")).cookie(fin).content("{\"status\":\"APPROVED\"}")).andExpect(status().isOk());

        // settled: frozen snapshot, leave encashed off the ledger, the exit is SETTLED
        assertThat(jdbc.queryForObject("SELECT status FROM employee_exits WHERE id = ?::uuid", String.class, id)).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT net_paise FROM fnf_settlements WHERE exit_id = ?::uuid", Long.class, id)).isEqualTo(netDraft);
        assertThat(jdbc.queryForObject("SELECT snapshot IS NOT NULL FROM fnf_settlements WHERE exit_id = ?::uuid", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT sum(delta) FROM leave_ledger WHERE employee_id = ? AND leave_type = 'EL'", Double.class, employeeId)).isEqualTo(0.0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_ledger WHERE employee_id = ? AND reason = 'ENCASHED'", Integer.class, employeeId)).isEqualTo(1);
        mvc.perform(get("/api/exits/" + id + "/settlement").cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.netPaise").value(netDraft));
        mvc.perform(get("/api/exits/" + id).cookie(hr)).andExpect(jsonPath("$.data.settlementStatus").value("APPROVED"));

        // immutable: no more drafts or approvals through the API, and the database refuses an update or a delete
        mvc.perform(write(put("/api/exits/" + id + "/settlement")).cookie(draft).content(lines("[]", "[]"))).andExpect(status().isConflict());
        mvc.perform(write(put("/api/exits/" + id + "/settlement/status")).cookie(fin).content("{\"status\":\"APPROVED\"}")).andExpect(status().isConflict());
        assertThatThrownBy(() -> jdbc.update("UPDATE fnf_settlements SET net_paise = 1 WHERE exit_id = ?::uuid", id)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permanent record");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM fnf_settlements WHERE exit_id = ?::uuid", id)).isInstanceOf(DataAccessException.class);

        // the PDF statement
        byte[] pdf = mvc.perform(get("/api/exits/" + id + "/settlement/statement").cookie(fin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        // the employee was settled through the F&F, so payroll no longer computes them
        assertThat(payroll.computeRun(ym, java.util.List.of(employeeId))).isEmpty();

        // closing: the existing "mark as past employee" runs — access off, sessions revoked
        mvc.perform(get("/api/me").cookie(emp)).andExpect(status().isOk());
        move(hr, id, "CLOSED", 200);
        assertThat(jdbc.queryForObject("SELECT status::text FROM employees WHERE id = ?", String.class, employeeId)).isEqualTo("EXITED");
        assertThat(jdbc.queryForObject("SELECT exit_date FROM employees WHERE id = ?", Date.class, employeeId).toLocalDate()).isEqualTo(lwd);
        mvc.perform(get("/api/me").cookie(emp)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT status FROM employee_exits WHERE id = ?::uuid", String.class, id)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'employee.exit' AND entity_id = ?", Integer.class, employeeId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action IN ('exit.resign', 'exit.notice', 'exit.status', 'exit.clearance', 'fnf.draft', 'fnf.approve')",
                Integer.class)).isGreaterThanOrEqualTo(10);
    }

    // ───────────── gratuity, policy ─────────────

    @Test
    void gratuityFollowsThePolicyToggleAndTheFiveYearRule() throws Exception {
        jdbc.update("UPDATE employees SET date_of_joining = ? WHERE id = ?", Date.valueOf(lwd.minusYears(6).minusMonths(3)), employeeId);
        String id = toFnfPending();
        mvc.perform(get("/api/exits/" + id + "/settlement").cookie(fin)).andExpect(jsonPath("$.data.result.gratuity").value(0));

        // switched on by HR: 15/26 x last Basic (₹50,000) x 6 completed years
        mvc.perform(write(put("/api/exits/policy")).cookie(hr).content("{\"gratuityEnabled\":true}")).andExpect(status().isOk());
        mvc.perform(get("/api/exits/policy").cookie(hr)).andExpect(jsonPath("$.data.gratuityEnabled").value(true)).andExpect(jsonPath("$.data.noticeDays").value(30));
        mvc.perform(get("/api/exits/" + id + "/settlement").cookie(fin)).andExpect(jsonPath("$.data.result.serviceYears").value(6))
                .andExpect(jsonPath("$.data.result.gratuity").value(Math.round(15.0 * 5_000_000 * 6 / 26 / 100) * 100));

        // under five years: nothing
        jdbc.update("UPDATE employees SET date_of_joining = ? WHERE id = ?", Date.valueOf(lwd.minusYears(4).minusMonths(11)), employeeId);
        mvc.perform(get("/api/exits/" + id + "/settlement").cookie(fin)).andExpect(jsonPath("$.data.result.serviceYears").value(4))
                .andExpect(jsonPath("$.data.result.gratuity").value(0));

        // policy changes are validated, and are HR's (exit.manage)
        mvc.perform(write(put("/api/exits/policy")).cookie(hr).content("{\"noticeDays\":400}")).andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/exits/policy")).cookie(hr).content("{\"encashableLeaveTypes\":[\"NOPE\"]}")).andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/exits/policy")).cookie(fin).content("{\"noticeDays\":15}")).andExpect(status().isForbidden());
    }

    // ───────────── state rules ─────────────

    @Test
    void stateTransitionsAreEnforced() throws Exception {
        String id = resign();
        // a second resignation is refused while one is open
        mvc.perform(write(post("/api/exits/me")).cookie(emp).content("{\"reason\":\"Again\",\"requestedLastDay\":\"" + Format.todayIn("Asia/Kolkata") + "\"}"))
                .andExpect(status().isConflict());
        // HR can't start a second case, and a case needs a kind
        mvc.perform(write(post("/api/exits")).cookie(hr).content("{\"employeeId\":\"" + employeeId + "\",\"reason\":\"Conduct\"}")).andExpect(status().isBadRequest()); // no kind
        mvc.perform(write(post("/api/exits")).cookie(hr).content("{\"employeeId\":\"" + employeeId + "\",\"kind\":\"TERMINATION\",\"reason\":\"Conduct\"}"))
                .andExpect(status().isConflict()); // already has an open case

        // order: NOTICE -> CLEARANCE -> FNF_PENDING -> (settled) -> CLOSED
        move(hr, id, "CLEARANCE", 409);
        move(hr, id, "FNF_PENDING", 409);
        move(hr, id, "CLOSED", 409);
        move(hr, id, "SETTLED", 400); // only approving the settlement settles
        move(hr, id, "NOTICE", 422); // no last working day yet
        notice(id, 30, null);
        move(hr, id, "NOTICE", 200);
        move(hr, id, "NOTICE", 409);
        // settlement before clearance is a conflict, as is clearing an item too early or too late
        mvc.perform(write(put("/api/exits/" + id + "/settlement")).cookie(draft).content(lines("[]", "[]"))).andExpect(status().isConflict());
        mvc.perform(write(put("/api/exits/" + id + "/settlement/status")).cookie(fin).content("{\"status\":\"APPROVED\"}")).andExpect(status().isConflict());
        move(hr, id, "CLEARANCE", 200);
        move(hr, id, "FNF_PENDING", 422); // items open
        // too late for the employee to withdraw
        mvc.perform(write(put("/api/exits/me/status")).cookie(emp).content("{\"status\":\"WITHDRAWN\"}")).andExpect(status().isConflict());
        // approving with no draft is a 422, and closing before settling is a 422 once at FNF_PENDING
        for (String item : jdbc.queryForList("SELECT id::text FROM exit_clearance_items WHERE exit_id = ?::uuid", String.class, id)) {
            mvc.perform(write(put("/api/exits/" + id + "/clearance/" + item)).cookie(hr).content("{\"cleared\":true}")).andExpect(status().isOk());
        }
        move(hr, id, "FNF_PENDING", 200);
        mvc.perform(write(put("/api/exits/" + id + "/settlement/status")).cookie(fin).content("{\"status\":\"APPROVED\"}")).andExpect(status().isUnprocessableEntity());
        move(hr, id, "CLOSED", 422);
        // notice and checklist are frozen from here
        mvc.perform(write(put("/api/exits/" + id + "/notice")).cookie(hr).content("{\"lastWorkingDay\":\"" + lwd + "\"}")).andExpect(status().isConflict());
        mvc.perform(write(put("/api/exits/" + id + "/clearance/" + jdbc.queryForObject("SELECT id::text FROM exit_clearance_items WHERE exit_id = ?::uuid LIMIT 1", String.class, id))
                ).cookie(hr).content("{\"cleared\":false}")).andExpect(status().isConflict());
        // cancelling needs a reason; afterwards nothing moves
        mvc.perform(write(put("/api/exits/" + id + "/status")).cookie(hr).content("{\"status\":\"CANCELLED\"}")).andExpect(status().isUnprocessableEntity());
        move(hr, id, "CANCELLED", 200);
        move(hr, id, "CANCELLED", 409);
        move(hr, id, "CLEARANCE", 409);
        // and the employee can resign afresh
        mvc.perform(get("/api/exits/me").cookie(emp)).andExpect(jsonPath("$.data.exit.status").value("CANCELLED")).andExpect(jsonPath("$.data.canResign").value(true));
    }

    @Test
    void anEmployeeCanWithdrawAResignationButNotAnHrTermination() throws Exception {
        String id = resign();
        mvc.perform(write(put("/api/exits/me/status")).cookie(emp).content("{\"status\":\"RESIGNED\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/exits/me/status")).cookie(emp).content("{\"status\":\"WITHDRAWN\",\"comment\":\"Changed my mind\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM employee_exits WHERE id = ?::uuid", String.class, id)).isEqualTo("WITHDRAWN");
        mvc.perform(write(put("/api/exits/me/status")).cookie(emp).content("{\"status\":\"WITHDRAWN\"}")).andExpect(status().isNotFound());

        // HR terminates: the employee can't withdraw it
        mvc.perform(write(post("/api/exits")).cookie(hr).content("{\"employeeId\":\"" + employeeId + "\",\"kind\":\"TERMINATION\",\"reason\":\"Conduct\",\"lastWorkingDay\":\"" + lwd + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").exists());
        mvc.perform(get("/api/exits/me").cookie(emp)).andExpect(jsonPath("$.data.exit.status").value("INITIATED")).andExpect(jsonPath("$.data.canWithdraw").value(false));
        mvc.perform(write(put("/api/exits/me/status")).cookie(emp).content("{\"status\":\"WITHDRAWN\"}")).andExpect(status().isConflict());
        // a waived notice still needs a reason
        mvc.perform(write(post("/api/exits")).cookie(hr).content("{\"employeeId\":\"" + otherEmployeeId + "\",\"kind\":\"OTHER\",\"reason\":\"Absconding\"}"))
                .andExpect(status().isNotFound()); // not in this business
    }

    // ───────────── who may do what ─────────────

    @Test
    void permissionsGateEveryEndpoint() throws Exception {
        String id = resign();
        // an employee has no HR pages
        mvc.perform(get("/api/exits").cookie(emp)).andExpect(status().isForbidden());
        mvc.perform(get("/api/exits/" + id).cookie(emp)).andExpect(status().isForbidden());
        mvc.perform(write(put("/api/exits/" + id + "/status")).cookie(emp).content("{\"status\":\"NOTICE\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/exits/policy").cookie(emp)).andExpect(status().isForbidden());
        // HR (exit.manage) and Finance (pay.view) read the cases; only HR changes them
        mvc.perform(get("/api/exits").cookie(hr)).andExpect(status().isOk());
        mvc.perform(get("/api/exits").cookie(draft)).andExpect(status().isOk());
        mvc.perform(get("/api/exits/" + id).cookie(draft)).andExpect(status().isOk());
        mvc.perform(write(put("/api/exits/" + id + "/status")).cookie(draft).content("{\"status\":\"NOTICE\"}")).andExpect(status().isForbidden());
        mvc.perform(write(post("/api/exits")).cookie(draft).content("{\"employeeId\":\"" + employeeId + "\",\"kind\":\"OTHER\",\"reason\":\"abc\"}")).andExpect(status().isForbidden());
        // the owner sees amounts by role; HR never does
        mvc.perform(get("/api/exits/" + id + "/settlement").cookie(owner)).andExpect(status().isOk());
        mvc.perform(get("/api/exits/" + id + "/settlement").cookie(hr)).andExpect(status().isForbidden());
        // signed out
        mvc.perform(get("/api/exits")).andExpect(status().isUnauthorized());
        // the exit.manage permission is HR's and the owner's, not a salary permission
        assertThat(com.jurislpo.jurisone.engine.Permissions.SALARY).doesNotContain("exit.manage");
        // an employee's own page needs `self`: the owner (no self) has none
        mvc.perform(get("/api/exits/me").cookie(owner)).andExpect(status().isForbidden());
    }

    // ───────────── tenant isolation ─────────────

    @Test
    void anotherBusinessCannotSeeOrChangeAnExit() throws Exception {
        String id = toFnfPending();
        mvc.perform(get("/api/exits").cookie(otherHr)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/exits/" + id).cookie(otherHr)).andExpect(status().isNotFound());
        mvc.perform(write(put("/api/exits/" + id + "/status")).cookie(otherHr).content("{\"status\":\"CANCELLED\",\"comment\":\"Not yours\"}")).andExpect(status().isNotFound());
        mvc.perform(write(put("/api/exits/" + id + "/notice")).cookie(otherHr).content("{\"lastWorkingDay\":\"" + lwd + "\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/exits/" + id + "/settlement").cookie(otherFin)).andExpect(status().isNotFound());
        mvc.perform(write(put("/api/exits/" + id + "/settlement")).cookie(otherFin).content(lines("[]", "[]"))).andExpect(status().isNotFound());
        mvc.perform(write(put("/api/exits/" + id + "/settlement/status")).cookie(otherFin).content("{\"status\":\"APPROVED\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/exits/" + id + "/settlement/statement").cookie(otherFin)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM employee_exits WHERE id = ?::uuid", String.class, id)).isEqualTo("FNF_PENDING");
        // and the row is stamped with its own business
        assertThat(jdbc.queryForObject("SELECT business_id FROM employee_exits WHERE id = ?::uuid", UUID.class, id)).isEqualTo(businessId);
    }

    // ───────────── payroll interplay ─────────────

    @Test
    void whenThePayrollMonthIsAlreadyClosedOnlyTheOtherItemsAreSettled() throws Exception {
        String id = toFnfPending();
        // payroll for the last month closed before the settlement: its salary was paid there
        jdbc.update("UPDATE payroll_runs SET status = 'CLOSED', closed_at = now() WHERE business_id = ? AND month = ?", businessId, ym);
        UUID run = jdbc.queryForObject("SELECT id FROM payroll_runs WHERE business_id = ? AND month = ?", UUID.class, businessId, ym);
        String slip = "{\"grossEarned\":10000000,\"pf\":180000,\"pt\":20000,\"tds\":0,\"components\":[{\"label\":\"Basic\",\"rate\":5000000,\"earned\":5000000},"
                + "{\"label\":\"House rent allowance\",\"rate\":2000000,\"earned\":2000000}]}";
        jdbc.update("INSERT INTO payslips (run_id, employee_id, snapshot) VALUES (?, ?, CAST(? AS jsonb))", run, employeeId, slip);
        JsonNode r = body(mvc.perform(get("/api/exits/" + id + "/settlement").cookie(fin)).andExpect(status().isOk()).andReturn()).at("/data/result");
        assertThat(r.get("salaryAlreadyPaid").asBoolean()).isTrue();
        assertThat(r.get("salaryEarned").asLong()).isZero();
        assertThat(r.get("pf").asLong()).isZero();
        assertThat(r.get("leaveEncashment").asLong()).isPositive();
    }
}
