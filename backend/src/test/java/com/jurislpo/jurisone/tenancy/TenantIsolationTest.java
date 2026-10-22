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

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    TestData alphaData, betaData;
    UUID alphaHrId, alphaEmployeeId, betaHrId, betaEmployeeId;

    @BeforeEach
    void setUp() {
        alphaData = new TestData(jdbc).inBusiness("tenant-alpha");
        betaData = new TestData(jdbc).inBusiness("tenant-beta");
        for (TestData testData : new TestData[] {alphaData, betaData}) {
            UUID hrId = testData.role("hrm", "self", "dir", "people.view", "people.add", "dept.manage", "appr.leave");
            UUID employee = testData.role("employee", "self", "dir");
            jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrId, employee);
            if (testData == alphaData) { alphaHrId = hrId; alphaEmployeeId = employee; } else { betaHrId = hrId; betaEmployeeId = employee; }
        }
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    record Person(UUID id, String code, String email, Cookie cookie) {}

    /** Same employee code in both businesses on purpose: codes are unique per business only. */
    Person person(TestData testData, UUID role, String code) throws Exception {
        String email = code.toLowerCase() + "." + UUID.randomUUID().toString().substring(0, 6) + "@" + testData.business().toString().substring(0, 8) + ".test";
        UUID id = testData.employee(code, email, role);
        String set = SignIn.session(mockMvc, email)
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, code, email, new Cookie("jo_at", matcher.group(1)));
    }

    @Test
    void peopleListsAndRecordsStayInsideTheirBusiness() throws Exception {
        String code = "XX-" + (int) (Math.random() * 100_000);
        Person alphaHr = person(alphaData, alphaHrId, "HR" + code);
        Person alphaColleague = person(alphaData, alphaHrId, code);
        Person betaHr = person(betaData, betaHrId, code); // same code, other business: allowed

        // (nobody sees themselves in the list, so alpha's HR looks for a colleague)
        JsonNode list = json.readTree(mockMvc.perform(get("/api/people?search=" + code).cookie(alphaHr.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.at("/data/rows").findValuesAsText("id")).contains(alphaColleague.id().toString()).doesNotContain(betaHr.id().toString(), alphaHr.id().toString());

        // a record of the other business is simply not there
        mockMvc.perform(get("/api/people/" + betaHr.id()).cookie(alphaHr.cookie())).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/people/" + betaHr.id()).cookie(betaHr.cookie())).andExpect(status().isOk());
    }

    @Test
    void departmentNamesAreSeparateAndRequestsCannotBeDecidedAcrossBusinesses() throws Exception {
        Person alphaHr = person(alphaData, alphaHrId, "AL-" + (int) (Math.random() * 100_000));
        Person betaHr = person(betaData, betaHrId, "BE-" + (int) (Math.random() * 100_000));
        Person betaEmp = person(betaData, betaEmployeeId, "BE-" + (int) (Math.random() * 100_000));

        String department = "Research " + UUID.randomUUID().toString().substring(0, 6);
        mockMvc.perform(write(post("/api/departments")).cookie(alphaHr.cookie()).content("{\"name\":\"" + department + "\"}")).andExpect(status().isCreated());
        mockMvc.perform(write(post("/api/departments")).cookie(betaHr.cookie()).content("{\"name\":\"" + department + "\"}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT business_id) FROM departments WHERE name = ?", Integer.class, department)).isEqualTo(2);

        // a pending leave request in beta can't be decided from alpha
        jdbc.update("INSERT INTO leave_types (code, name, annual_quota, accrual_per_month, carry_forward_cap, paid, needs_balance, sort_order, active) "
                + "VALUES ('CL', 'Casual leave', 12, 1, 0, true, true, 1, true) ON CONFLICT (code) DO NOTHING");
        UUID request = UUID.randomUUID();
        jdbc.update("INSERT INTO leave_requests (id, employee_id, leave_type, from_date, to_date, days) VALUES (?, ?, 'CL', ?, ?, 1)",
                request, betaEmp.id(), Date.valueOf(LocalDate.now().plusDays(10)), Date.valueOf(LocalDate.now().plusDays(10)));
        mockMvc.perform(write(put("/api/leave/requests/" + request + "/decision")).cookie(alphaHr.cookie()).content("{\"approve\":true}"))
                .andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT status::text FROM leave_requests WHERE id = ?", String.class, request)).isEqualTo("PENDING");
    }

    @Test
    void anEmailIsOneSignInAcrossThePlatform() throws Exception {
        Person alphaHr = person(alphaData, alphaHrId, "AL-" + (int) (Math.random() * 100_000));
        Person betaEmp = person(betaData, betaEmployeeId, "BE-" + (int) (Math.random() * 100_000));
        UUID deptA = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", deptA, "Ops " + deptA.toString().substring(0, 6), alphaData.business());
        String body = """
            {"firstName":"Copy","lastName":"Cat","fatherName":"Someone","email":"%s","phoneCountry":"+91","phone":"98765 43210","location":"IN_IST","pan":"abcde1234f",
             "aadhaar":"2345 6789 0123","uan":"","address":"12 Lake Road, Pune","designation":"Associate","departmentId":"%s","managerId":"%s",
             "dateOfJoining":"2031-10-12","shift":"IN_DAY","roleId":"%s"}""".formatted(betaEmp.email(), deptA, alphaHr.id(), alphaEmployeeId);
        mockMvc.perform(write(post("/api/people")).cookie(alphaHr.cookie()).content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("That email is already in use."));
    }

    @Test
    void theDatabaseItselfHidesOtherBusinessesRows() {
        // even a query without any business filter returns only the current business's rows
        long total = jdbc.queryForObject("SELECT count(*) FROM employees WHERE business_id IN (?, ?)", Long.class, alphaData.business(), betaData.business());
        long seenFromAlpha = TenantContext.call(alphaData.business(), () -> jdbc.queryForObject("SELECT count(*) FROM employees", Long.class));
        long alphaOnly = jdbc.queryForObject("SELECT count(*) FROM employees WHERE business_id = ?", Long.class, alphaData.business());
        assertThat(seenFromAlpha).isEqualTo(alphaOnly);
        assertThat(total).isGreaterThanOrEqualTo(alphaOnly);
        // and nothing can be written into another business
        UUID foreign = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TenantContext.run(alphaData.business(),
                () -> jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, 'Sneaky', ?)", foreign, betaData.business())))
                .rootCause().hasMessageContaining("row-level security");
    }
}
