package com.jurislpo.jurisone.tenancy;

import com.jurislpo.jurisone.tenancy.TenantContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Two businesses in one database: neither can see or change the other's data (row-level security). */
class TenantIsolationTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    TestData a, b;
    UUID hrA, empA, hrB, empB;

    @BeforeEach
    void setUp() {
        a = new TestData(jdbc).inBusiness("tenant-alpha");
        b = new TestData(jdbc).inBusiness("tenant-beta");
        for (TestData t : new TestData[] {a, b}) {
            UUID hr = t.role("hrm", "self", "dir", "people.view", "people.add", "dept.manage", "appr.leave");
            UUID employee = t.role("employee", "self", "dir");
            jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hr, employee);
            if (t == a) { hrA = hr; empA = employee; } else { hrB = hr; empB = employee; }
        }
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder r) {
        return r.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    record Person(UUID id, String code, String email, Cookie cookie) {}

    /** Same employee code in both businesses on purpose: codes are unique per business only. */
    Person person(TestData t, UUID role, String code) throws Exception {
        String email = code.toLowerCase() + "." + UUID.randomUUID().toString().substring(0, 6) + "@" + t.business().toString().substring(0, 8) + ".test";
        UUID id = t.employee(code, email, role);
        String set = SignIn.session(mvc, email)
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Person(id, code, email, new Cookie("jo_at", m.group(1)));
    }

    @Test
    void peopleListsAndRecordsStayInsideTheirBusiness() throws Exception {
        String code = "XX-" + (int) (Math.random() * 100_000);
        Person alphaHr = person(a, hrA, "HR" + code);
        Person alphaColleague = person(a, hrA, code);
        Person betaHr = person(b, hrB, code); // same code, other business: allowed

        // (nobody sees themselves in the list, so alpha's HR looks for a colleague)
        JsonNode list = json.readTree(mvc.perform(get("/api/people?search=" + code).cookie(alphaHr.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.at("/data/rows").findValuesAsText("id")).contains(alphaColleague.id().toString()).doesNotContain(betaHr.id().toString(), alphaHr.id().toString());

        // a record of the other business is simply not there
        mvc.perform(get("/api/people/" + betaHr.id()).cookie(alphaHr.cookie())).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/people/" + betaHr.id()).cookie(betaHr.cookie())).andExpect(status().isOk());
    }

    @Test
    void departmentNamesAreSeparateAndRequestsCannotBeDecidedAcrossBusinesses() throws Exception {
        Person alphaHr = person(a, hrA, "AL-" + (int) (Math.random() * 100_000));
        Person betaHr = person(b, hrB, "BE-" + (int) (Math.random() * 100_000));
        Person betaEmp = person(b, empB, "BE-" + (int) (Math.random() * 100_000));

        String department = "Research " + UUID.randomUUID().toString().substring(0, 6);
        mvc.perform(write(post("/api/departments")).cookie(alphaHr.cookie()).content("{\"name\":\"" + department + "\"}")).andExpect(status().isCreated());
        mvc.perform(write(post("/api/departments")).cookie(betaHr.cookie()).content("{\"name\":\"" + department + "\"}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT business_id) FROM departments WHERE name = ?", Integer.class, department)).isEqualTo(2);

        // a pending leave request in beta can't be decided from alpha
        jdbc.update("INSERT INTO leave_types (code, name, annual_quota, accrual_per_month, carry_forward_cap, paid, needs_balance, sort_order, active) "
                + "VALUES ('CL', 'Casual leave', 12, 1, 0, true, true, 1, true) ON CONFLICT (code) DO NOTHING");
        UUID request = UUID.randomUUID();
        jdbc.update("INSERT INTO leave_requests (id, employee_id, leave_type, from_date, to_date, days) VALUES (?, ?, 'CL', ?, ?, 1)",
                request, betaEmp.id(), Date.valueOf(LocalDate.now().plusDays(10)), Date.valueOf(LocalDate.now().plusDays(10)));
        mvc.perform(write(put("/api/leave/requests/" + request + "/decision")).cookie(alphaHr.cookie()).content("{\"approve\":true}"))
                .andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT status::text FROM leave_requests WHERE id = ?", String.class, request)).isEqualTo("PENDING");
    }

    @Test
    void anEmailIsOneSignInAcrossThePlatform() throws Exception {
        Person alphaHr = person(a, hrA, "AL-" + (int) (Math.random() * 100_000));
        Person betaEmp = person(b, empB, "BE-" + (int) (Math.random() * 100_000));
        UUID deptA = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", deptA, "Ops " + deptA.toString().substring(0, 6), a.business());
        String body = """
            {"firstName":"Copy","lastName":"Cat","fatherName":"Someone","email":"%s","phoneCountry":"+91","phone":"98765 43210","location":"IN_IST","pan":"abcde1234f",
             "aadhaar":"2345 6789 0123","uan":"","address":"12 Lake Road, Pune","designation":"Associate","departmentId":"%s","managerId":"%s",
             "dateOfJoining":"2031-10-12","shift":"IN_DAY","roleId":"%s"}""".formatted(betaEmp.email(), deptA, alphaHr.id(), empA);
        mvc.perform(write(post("/api/people")).cookie(alphaHr.cookie()).content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("That email is already in use."));
    }

    @Test
    void theDatabaseItselfHidesOtherBusinessesRows() {
        // even a query without any business filter returns only the current business's rows
        long total = jdbc.queryForObject("SELECT count(*) FROM employees WHERE business_id IN (?, ?)", Long.class, a.business(), b.business());
        long seenFromAlpha = TenantContext.call(a.business(), () -> jdbc.queryForObject("SELECT count(*) FROM employees", Long.class));
        long alphaOnly = jdbc.queryForObject("SELECT count(*) FROM employees WHERE business_id = ?", Long.class, a.business());
        assertThat(seenFromAlpha).isEqualTo(alphaOnly);
        assertThat(total).isGreaterThanOrEqualTo(alphaOnly);
        // and nothing can be written into another business
        UUID foreign = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TenantContext.run(a.business(),
                () -> jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, 'Sneaky', ?)", foreign, b.business())))
                .rootCause().hasMessageContaining("row-level security");
    }
}
