package com.jurislpo.jurisone.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The employee onboarding wizard: saving and resuming, validation, nominations, masking, submitting, read-only while waiting for HR, access rules. */
class EmployeeOnboardingApiTest extends IntegrationTest {

    static final byte[] PDF = "%PDF-1.4\n%doc\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    TestData data;
    String suffix;
    UUID employeeId, otherEmployeeId, oldEmployeeId, hrId, plainId;
    Cookie employee, other, old, hr, plain;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    UUID employee(String code, String status) {
        UUID id = data.employee(code + "-" + suffix, code.toLowerCase() + "." + suffix + "@example.com", data.role("employee", "self", "dir"));
        jdbc.update("INSERT INTO employee_onboardings (id, business_id, employee_id, status) VALUES (?, ?, ?, ?)", UUID.randomUUID(), data.business(), id, status);
        return id;
    }

    @BeforeEach
    void business() throws Exception {
        suffix = TestData.letters(8);
        data = new TestData(jdbc).inBusiness("wizard-" + suffix);
        employeeId = employee("EA", "NOT_STARTED");
        otherEmployeeId = employee("EB", "NOT_STARTED");
        oldEmployeeId = employee("EC", "NOT_REQUIRED");
        hrId = data.employee("HR-" + suffix, "hr." + suffix + "@example.com", data.role("hrm", "self", "dir", "people.view", "people.add"));
        employee = signIn("ea." + suffix + "@example.com");
        other = signIn("eb." + suffix + "@example.com");
        old = signIn("ec." + suffix + "@example.com");
        hr = signIn("hr." + suffix + "@example.com");
    }

    // ───────────────────────── payloads ─────────────────────────

    static final String STEP1 = """
        {"personal":{"bloodGroup":"B +ve","personalEmail":"Amit@Example.com","fatherName":"Jaspal Singh","maritalStatus":"Married","marriageDate":"2020-02-10","spouseName":"Neha",
          "countryOfOrigin":"India","nationality":"Indian","isInternationalEmployee":false,"isPhysicallyChallenged":false,"gender":"Male","dateOfBirth":"1990-05-01"},
         "permanentAddress":{"line1":"12 Lake Road","line2":"Sector 4","line3":"Near park","city":"Delhi","district":"South","state":"Delhi","pin":"110001","country":"India","phone":"98100 11223"},
         "presentAddress":{"isSameAsPermanent":true},
         "emergencyContact":{"name":"Neha","mobile":"9810044556","relationship":"Wife"},
         "education":[{"qualification":"B.Tech","fromYear":2008,"toYear":2012,"institute":"MDU, Rohtak","gradeOrPercentage":"8.1"}],
         "courses":[]}""";

    static final String STEP3 = """
        {"bank":{"country":"India","bankAccountNumber":"50100123456789","ifsc":"icic0000571","bankName":"ICICI Bank","branch":"Delhi","branchAddress":"1 Road","nameAsPerBank":"Amit Jaspal","accountType":"Saving"},
         "statutory":{"previousPfNumber":null,"uan":"123456789012","pan":"abcde1234f","namePerPan":"Amit Jaspal","aadhaar":"2345 6789 0123","namePerAadhaar":"Amit Jaspal"}}""";

    ResultActions putJson(Cookie cookie, String path, String body) throws Exception {
        return mockMvc.perform(write(put(path)).cookie(cookie).content(body));
    }

    JsonNode data(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString()).at("/data");
    }

    JsonNode mine(Cookie cookie) throws Exception {
        return data(mockMvc.perform(get("/api/v1/onboarding/mine").cookie(cookie)).andExpect(status().isOk()));
    }

    JsonNode saveOk(Cookie cookie, int step, String body) throws Exception {
        return data(putJson(cookie, "/api/v1/onboarding/mine/steps/" + step, body).andExpect(status().isOk()));
    }

    JsonNode errors(Cookie cookie, int step, String body) throws Exception {
        return json.readTree(putJson(cookie, "/api/v1/onboarding/mine/steps/" + step, body).andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString()).get("errors");
    }

    String family() {
        return """
            {"familyMembers":[{"name":"Neha","relationship":"Wife","dateOfBirth":"1992-03-04","gender":"Female","bloodGroup":"A +ve","nationality":"Indian","isMinor":false,"hasMentalIllness":false},
             {"name":"Aarav","relationship":"Son","dateOfBirth":"2018-01-01","gender":"Male","bloodGroup":"B +ve","nationality":"Indian","isMinor":true,"hasMentalIllness":false,
              "guardian":{"name":"Neha","relationship":"Mother","mobile":"9810044556"}}]}""";
    }

    void uploadPdf(Cookie cookie, String slotKey) throws Exception {
        mockMvc.perform(multipart("/api/v1/onboarding/mine/attachments").file(new MockMultipartFile("file", "proof.pdf", "application/pdf", PDF)).param("slotKey", slotKey).cookie(cookie)
                .header("origin", "http://localhost:3005").header("host", "localhost:3005")).andExpect(status().isCreated());
    }

    /** Fills all six steps (family and nominations included) up to, but not including, the attachments. */
    void fillSteps(Cookie cookie) throws Exception {
        saveOk(cookie, 1, STEP1);
        saveOk(cookie, 2, "{\"employments\":[{\"companyName\":\"Lex & Co\",\"designation\":\"Associate\",\"lastDrawnSalary\":50000.50,\"fromDate\":\"2013-01-01\",\"toDate\":\"2016-01-01\"}]}");
        saveOk(cookie, 3, STEP3);
        JsonNode members = saveOk(cookie, 4, family()).get("familyMembers");
        saveOk(cookie, 5, "{\"nominations\":{\"epf\":[{\"familyMemberId\":\"" + members.get(0).get("id").asText() + "\",\"percentage\":100}],\"eps\":{\"familyMemberId\":\""
                + members.get(0).get("id").asText() + "\"},\"esi\":null,\"gratuity\":[]}}");
    }

    // ───────────────────────── tests ─────────────────────────

    @Test
    void theOldCandidateFlowIsGone() throws Exception {
        mockMvc.perform(get("/api/v1/public/onboardings/some-token-value-that-is-long-enough")).andExpect(status().is4xxClientError());
        mockMvc.perform(write(post("/api/v1/onboardings")).cookie(hr).content("{}")).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name IN ('onboardings', 'onboarding_documents')", Integer.class)).isZero();
    }

    @Test
    void notRequiredOrSignedOutIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/onboarding/mine").cookie(old)).andExpect(status().isNotFound());
        putJson(old, "/api/v1/onboarding/mine/steps/2", "{\"employments\":[]}").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/onboarding/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void stepOneValidatesAndSavesNothingWhenInvalid() throws Exception {
        JsonNode errors = errors(employee, 1, "{}");
        assertThat(errors.has("personal")).isTrue();
        assertThat(errors.has("permanentAddress")).isTrue();
        assertThat(errors.has("education")).isTrue();

        String invalid = STEP1.replace("\"bloodGroup\":\"B +ve\"", "\"bloodGroup\":\"\"").replace("\"marriageDate\":\"2020-02-10\"", "\"marriageDate\":null")
                .replace("\"pin\":\"110001\"", "\"pin\":\"11\"").replace("\"toYear\":2012", "\"toYear\":2005").replace("\"isPhysicallyChallenged\":false", "\"isPhysicallyChallenged\":true")
                .replace("\"dateOfBirth\":\"1990-05-01\"", "\"dateOfBirth\":\"2999-01-01\"").replace("\"mobile\":\"9810044556\"", "\"mobile\":\"123\"").replace("\"personalEmail\":\"Amit@Example.com\"", "\"personalEmail\":\"nope\"");
        errors = errors(employee, 1, invalid);
        assertThat(errors.get("personal.bloodGroup").asText()).isEqualTo("Blood Group is mandatory");
        assertThat(errors.get("personal.marriageDate").asText()).isEqualTo("Marriage Date is mandatory");
        assertThat(errors.get("personal.disabilityType").asText()).isEqualTo("Disability Type is mandatory");
        assertThat(errors.get("personal.dateOfBirth").asText()).contains("future");
        assertThat(errors.get("personal.personalEmail").asText()).isEqualTo("Enter a valid email");
        assertThat(errors.get("permanentAddress.pin").asText()).contains("6 digits");
        assertThat(errors.get("emergencyContact.mobile").asText()).contains("10 digits");
        assertThat(errors.get("education[0].toYear").asText()).contains("cannot be before");
        assertThat(mine(employee).get("status").asText()).isEqualTo("NOT_STARTED"); // nothing was saved
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onboarding_personal WHERE onboarding_id = (SELECT id FROM employee_onboardings WHERE employee_id = ?)", Integer.class, employeeId)).isZero();
    }

    @Test
    void savingAStepCanBeResumedAndMovesOn() throws Exception {
        JsonNode saved = saveOk(employee, 1, STEP1);
        assertThat(saved.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(saved.get("currentStep").asInt()).isEqualTo(2);
        assertThat(saved.at("/stepStatus/1").asText()).isEqualTo("COMPLETE");
        assertThat(saved.at("/stepStatus/2").asText()).isEqualTo("UNVISITED");

        JsonNode again = mine(employee); // reload: the same data
        assertThat(again.at("/personal/bloodGroup").asText()).isEqualTo("B +ve");
        assertThat(again.at("/personal/personalEmail").asText()).isEqualTo("amit@example.com");
        assertThat(again.at("/personal/marriageDate").asText()).isEqualTo("2020-02-10");
        assertThat(again.at("/permanentAddress/district").asText()).isEqualTo("South");
        assertThat(again.at("/presentAddress/isSameAsPermanent").asBoolean()).isTrue();
        assertThat(again.at("/presentAddress/district").asText()).isEqualTo("South"); // "same as above" copies every value
        assertThat(again.at("/presentAddress/phone").asText()).isEqualTo("9810011223");
        assertThat(again.at("/emergencyContact/relationship").asText()).isEqualTo("Wife");
        assertThat(again.at("/education/0/institute").asText()).isEqualTo("MDU, Rohtak");
        assertThat(again.at("/header/employeeCode").asText()).isEqualTo("EA-" + suffix);
        assertThat(again.get("isReadOnly").asBoolean()).isFalse();

        // the stepper click remembers the position without saving data
        JsonNode moved = data(putJson(employee, "/api/v1/onboarding/mine/current-step", "{\"step\":4}").andExpect(status().isOk()));
        assertThat(moved.get("currentStep").asInt()).isEqualTo(4);
        assertThat(mine(employee).get("currentStep").asInt()).isEqualTo(4);
        putJson(employee, "/api/v1/onboarding/mine/current-step", "{\"step\":9}").andExpect(status().isBadRequest());
        putJson(employee, "/api/v1/onboarding/mine/steps/7", "{}").andExpect(status().isBadRequest());

        // the slots follow the education entries; removing an entry removes its slot
        String educationId = again.at("/education/0/id").asText();
        assertThat(slotKeys(again)).contains("address_permanent", "address_present", "qualification:" + educationId, "bank", "pan", "aadhaar").doesNotContain("passport");
        String replaced = STEP1.replace("\"education\":[{", "\"education\":[{\"id\":\"" + educationId + "\",");
        JsonNode kept = saveOk(employee, 1, replaced);
        assertThat(kept.at("/education/0/id").asText()).isEqualTo(educationId); // the same id keeps its slot
    }

    String slotKeys(JsonNode payload) {
        StringBuilder keys = new StringBuilder();
        payload.get("attachmentSlots").forEach(slot -> keys.append(slot.get("slotKey").asText()).append(' '));
        return keys.toString();
    }

    @Test
    void removingAnEducationRemovesItsFilesToo() throws Exception {
        String twoEducations = STEP1.replace("\"courses\":[]", "\"courses\":[]").replace("\"education\":[{", "\"education\":[{\"qualification\":\"10th\",\"fromYear\":2000,\"toYear\":2002,\"institute\":\"School\",\"gradeOrPercentage\":\"90%\"},{");
        JsonNode saved = saveOk(employee, 1, twoEducations);
        String firstId = saved.at("/education/0/id").asText();
        uploadPdf(employee, "qualification:" + firstId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onboarding_attachments WHERE slot_key = ?", Integer.class, "qualification:" + firstId)).isEqualTo(1);
        String path = jdbc.queryForObject("SELECT storage_path FROM onboarding_attachments WHERE slot_key = ?", String.class, "qualification:" + firstId);
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of("target/test-storage").resolve(path))).isTrue();

        saveOk(employee, 1, STEP1); // only one education now, new id for it
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onboarding_attachments WHERE slot_key = ?", Integer.class, "qualification:" + firstId)).isZero();
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of("target/test-storage").resolve(path))).isFalse();
    }

    @Test
    void employmentBlocksNeedCompanyAndDatesOnceUsed() throws Exception {
        JsonNode errors = errors(employee, 2, "{\"employments\":[{\"designation\":\"Clerk\"},{\"companyName\":\"X\",\"fromDate\":\"2016-01-01\",\"toDate\":\"2013-01-01\"}]}");
        assertThat(errors.get("employments[0].companyName").asText()).isEqualTo("Company Name is mandatory");
        assertThat(errors.get("employments[0].fromDate").asText()).isEqualTo("From Date is mandatory");
        assertThat(errors.get("employments[0].toDate").asText()).isEqualTo("To Date is mandatory");
        assertThat(errors.get("employments[1].toDate").asText()).contains("cannot be before");
        // freshers can leave it empty; a blank block is dropped; the salary is kept as integer paise
        saveOk(employee, 2, "{\"employments\":[{}]}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onboarding_employments WHERE onboarding_id = (SELECT id FROM employee_onboardings WHERE employee_id = ?)", Integer.class, employeeId)).isZero();
        JsonNode saved = saveOk(employee, 2, "{\"employments\":[{\"companyName\":\"Lex\",\"lastDrawnSalary\":50000.5,\"fromDate\":\"2013-01-01\",\"toDate\":\"2016-01-01\"}]}");
        assertThat(jdbc.queryForObject("SELECT last_drawn_salary_paise FROM onboarding_employments WHERE onboarding_id = (SELECT id FROM employee_onboardings WHERE employee_id = ?)", Long.class, employeeId)).isEqualTo(5000050L);
        assertThat(saved.at("/employments/0/lastDrawnSalary").decimalValue()).isEqualByComparingTo("50000.50");
        assertThat(slotKeys(saved)).contains("employment:" + saved.at("/employments/0/id").asText());
    }

    @Test
    void identityNumbersAreEncryptedMaskedAndKeptWhenNotSent() throws Exception {
        JsonNode errors = errors(employee, 3, "{\"bank\":{\"country\":\"India\",\"bankAccountNumber\":\"123\",\"ifsc\":\"BAD\",\"accountType\":\"Gold\"},\"statutory\":{\"pan\":\"X\",\"aadhaar\":\"12\",\"uan\":\"1\",\"passportNumber\":\"P1\"}}");
        assertThat(errors.get("bank.bankAccountNumber").asText()).contains("9 to 18");
        assertThat(errors.get("bank.ifsc").asText()).contains("valid IFSC");
        assertThat(errors.get("bank.nameAsPerBank").asText()).isEqualTo("Name as per Bank Account is mandatory");
        assertThat(errors.get("statutory.pan").asText()).contains("ABCDE1234F");
        assertThat(errors.get("statutory.aadhaar").asText()).contains("12 digits");
        assertThat(errors.get("statutory.uan").asText()).contains("12 digits");
        assertThat(errors.get("statutory.passportExpiry").asText()).isEqualTo("Expiry Date is mandatory");

        JsonNode saved = saveOk(employee, 3, STEP3);
        assertThat(saved.at("/bank/bankAccountNumber").asText()).isEqualTo("XXXXXXXX6789");
        assertThat(saved.at("/bank/hasBankAccountNumber").asBoolean()).isTrue();
        assertThat(saved.at("/bank/ifsc").asText()).isEqualTo("ICIC0000571");
        assertThat(saved.at("/statutory/pan").asText()).isEqualTo("XXXXXXXX234F");
        assertThat(saved.at("/statutory/aadhaar").asText()).isEqualTo("XXXXXXXX0123");
        assertThat(saved.at("/statutory/uan").asText()).isEqualTo("XXXXXXXX9012");
        assertThat(saved.at("/statutory/hasPan").asBoolean()).isTrue();
        assertThat(saved.toString()).doesNotContain("50100123456789").doesNotContain("ABCDE1234F").doesNotContain("234567890123");
        String stored = jdbc.queryForObject("SELECT account_number_enc || pan_enc || aadhaar_enc || uan_enc FROM onboarding_bank b JOIN onboarding_statutory s USING (onboarding_id) WHERE onboarding_id = (SELECT id FROM employee_onboardings WHERE employee_id = ?)", String.class, employeeId);
        assertThat(stored).doesNotContain("50100123456789").doesNotContain("ABCDE1234F");

        // the client sends null for a field it did not change: the stored value stays
        String unchanged = STEP3.replace("\"bankAccountNumber\":\"50100123456789\"", "\"bankAccountNumber\":null").replace("\"pan\":\"abcde1234f\"", "\"pan\":null")
                .replace("\"aadhaar\":\"2345 6789 0123\"", "\"aadhaar\":null").replace("\"uan\":\"123456789012\"", "\"uan\":null");
        saveOk(employee, 3, unchanged);
        JsonNode secrets = data(mockMvc.perform(get("/api/v1/onboarding/mine/secrets").cookie(employee)).andExpect(status().isOk()));
        assertThat(secrets.get("bankAccountNumber").asText()).isEqualTo("50100123456789");
        assertThat(secrets.get("pan").asText()).isEqualTo("ABCDE1234F");
        assertThat(secrets.get("aadhaar").asText()).isEqualTo("234567890123");
        // sending the masked value back also keeps it
        saveOk(employee, 3, STEP3.replace("\"bankAccountNumber\":\"50100123456789\"", "\"bankAccountNumber\":\"XXXXXXXX6789\""));
        assertThat(data(mockMvc.perform(get("/api/v1/onboarding/mine/secrets").cookie(employee))).get("bankAccountNumber").asText()).isEqualTo("50100123456789");

        // HR sees them masked, and there is no way to reveal them
        JsonNode forHr = data(mockMvc.perform(get("/api/v1/people/" + employeeId + "/onboarding").cookie(hr)).andExpect(status().isOk()));
        assertThat(forHr.at("/bank/bankAccountNumber").asText()).isEqualTo("XXXXXXXX6789");
        assertThat(forHr.get("isReadOnly").asBoolean()).isTrue();
        assertThat(forHr.toString()).doesNotContain("50100123456789");
        mockMvc.perform(get("/api/v1/people/" + employeeId + "/onboarding?reveal=true").cookie(hr)).andExpect(status().isOk());

        // a passport number needs an expiry, and brings its own mandatory slot
        JsonNode withPassport = saveOk(employee, 3, STEP3.replace("\"namePerAadhaar\":\"Amit Jaspal\"", "\"namePerAadhaar\":\"Amit Jaspal\",\"passportNumber\":\"P1234567\",\"passportExpiry\":\"2032-01-01\""));
        assertThat(slotKeys(withPassport)).contains("passport");
    }

    @Test
    void nominationsFollowThePercentageRules() throws Exception {
        JsonNode members = saveOk(employee, 4, family()).get("familyMembers");
        String wife = members.get(0).get("id").asText(), son = members.get(1).get("id").asText();
        assertThat(members.get(1).at("/guardian/name").asText()).isEqualTo("Neha");
        assertThat(members.get(0).get("guardian").isNull()).isTrue();

        assertThat(errors(employee, 5, nominations("[{\"familyMemberId\":\"" + wife + "\",\"percentage\":50}]", "null")).get("nominations.epf[0].percentage").asText())
                .isEqualTo("Nomination % must be 100 in case of single nominee.");
        assertThat(errors(employee, 5, nominations("[{\"familyMemberId\":\"" + wife + "\",\"percentage\":60},{\"familyMemberId\":\"" + son + "\",\"percentage\":30}]", "null"))
                .get("nominations.epf[0].percentage").asText()).contains("100");
        assertThat(errors(employee, 5, nominations("[{\"familyMemberId\":\"" + wife + "\",\"percentage\":50},{\"familyMemberId\":\"" + wife + "\",\"percentage\":50}]", "null"))
                .get("nominations.epf[1].familyMemberId").asText()).contains("only once");
        assertThat(errors(employee, 5, nominations("[]", "{\"familyMemberId\":\"" + UUID.randomUUID() + "\"}")).has("nominations.eps.familyMemberId")).isTrue();

        JsonNode saved = saveOk(employee, 5, nominations("[{\"familyMemberId\":\"" + wife + "\",\"percentage\":60},{\"familyMemberId\":\"" + son + "\",\"percentage\":40}]", "{\"familyMemberId\":\"" + wife + "\"}"));
        assertThat(saved.at("/nominations/epf/1/percentage").asInt()).isEqualTo(40);
        assertThat(saved.at("/nominations/eps/familyMemberId").asText()).isEqualTo(wife);
        assertThat(saved.at("/stepStatus/5").asText()).isEqualTo("COMPLETE");

        // removing a member in step 4 removes the nominations that pointed at him and marks step 5 incomplete
        String onlyWife = "{\"familyMembers\":[{\"id\":\"" + wife + "\",\"name\":\"Neha\",\"relationship\":\"Wife\",\"dateOfBirth\":\"1992-03-04\",\"gender\":\"Female\",\"bloodGroup\":\"A +ve\",\"nationality\":\"Indian\"}]}";
        JsonNode afterRemoval = saveOk(employee, 4, onlyWife);
        assertThat(afterRemoval.at("/nominations/epf").size()).isEqualTo(1);
        assertThat(afterRemoval.at("/nominations/epf/0/familyMemberId").asText()).isEqualTo(wife);
        assertThat(afterRemoval.at("/stepStatus/5").asText()).isEqualTo("INCOMPLETE");
    }

    String nominations(String epf, String eps) {
        return "{\"nominations\":{\"epf\":" + epf + ",\"eps\":" + eps + ",\"esi\":null,\"gratuity\":[]}}";
    }

    @Test
    void familyMembersNeedTheirDetailsAndAMinorNeedsAGuardian() throws Exception {
        JsonNode errors = errors(employee, 4, "{\"familyMembers\":[{\"name\":\"\",\"isMinor\":true},{\"name\":\"X\",\"relationship\":\"Wife\",\"dateOfBirth\":\"2018-01-01\",\"gender\":\"Female\",\"bloodGroup\":\"A +ve\",\"nationality\":\"Indian\",\"isMinor\":true,\"guardian\":{\"name\":\"G\"}}]}");
        assertThat(errors.get("familyMembers[0].dateOfBirth").asText()).isEqualTo("DOB is mandatory");
        assertThat(errors.get("familyMembers[0].guardian.name").asText()).isEqualTo("Guardian Name is mandatory");
        assertThat(errors.get("familyMembers[1].guardian.relationship").asText()).isEqualTo("Relationship is mandatory");
        assertThat(errors.get("familyMembers[1].guardian.mobile").asText()).isEqualTo("Mobile is mandatory");
    }

    @Test
    void submissionNeedsEverythingAndIsReadOnlyUntilHrDecides() throws Exception {
        // nothing filled: every step is reported, with the missing mandatory files
        JsonNode errors = json.readTree(mockMvc.perform(write(post("/api/v1/onboarding/mine/submission")).cookie(employee)).andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString()).get("errors");
        assertThat(errors.has("personal")).isTrue();
        assertThat(errors.has("bank")).isTrue();
        assertThat(errors.has("attachments.address_permanent")).isTrue();

        fillSteps(employee);
        errors = json.readTree(mockMvc.perform(write(post("/api/v1/onboarding/mine/submission")).cookie(employee)).andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString()).get("errors");
        assertThat(errors.size()).isEqualTo(6); // address x2, qualification, bank, PAN, Aadhaar
        putJson(employee, "/api/v1/onboarding/mine/steps/6", "{}").andExpect(status().isUnprocessableEntity());

        JsonNode payload = mine(employee);
        for (JsonNode slot : payload.get("attachmentSlots")) {
            if (slot.get("isMandatory").asBoolean()) uploadPdf(employee, slot.get("slotKey").asText());
        }
        assertThat(saveOk(employee, 6, "{}").at("/stepStatus/6").asText()).isEqualTo("COMPLETE");

        mockMvc.perform(write(post("/api/v1/onboarding/mine/submission")).cookie(employee)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.isReadOnly").value(true));
        assertThat(jdbc.queryForObject("SELECT submitted_at IS NOT NULL FROM employee_onboardings WHERE employee_id = ?", Boolean.class, employeeId)).isTrue();

        // locked: every write answers 409
        putJson(employee, "/api/v1/onboarding/mine/steps/1", STEP1).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Onboarding is already submitted."));
        putJson(employee, "/api/v1/onboarding/mine/current-step", "{\"step\":1}").andExpect(status().isConflict());
        mockMvc.perform(write(post("/api/v1/onboarding/mine/submission")).cookie(employee)).andExpect(status().isConflict());
        mockMvc.perform(multipart("/api/v1/onboarding/mine/attachments").file(new MockMultipartFile("file", "x.pdf", "application/pdf", PDF)).param("slotKey", "bank").cookie(employee)
                .header("origin", "http://localhost:3005").header("host", "localhost:3005")).andExpect(status().isConflict());
        // reading still works
        assertThat(mine(employee).get("status").asText()).isEqualTo("SUBMITTED");

        // the old "reopen" endpoint is gone: HR decides with approvals / rejections (see OnboardingReviewApiTest)
        putJson(hr, "/api/v1/people/" + employeeId + "/onboarding/status", "{\"status\":\"CHANGES_REQUESTED\"}").andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action IN ('onboarding.submitted', 'onboarding.step_saved')", Integer.class)).isGreaterThanOrEqualTo(7);
    }

    @Test
    void hrSeesTheFilesOfAnEmployeeAndTheProfileDocumentsListThem() throws Exception {
        saveOk(employee, 1, STEP1);
        uploadPdf(employee, "address_permanent");
        String fileId = jdbc.queryForObject("SELECT id::text FROM onboarding_attachments WHERE onboarding_id = (SELECT id FROM employee_onboardings WHERE employee_id = ?)", String.class, employeeId);
        mockMvc.perform(get("/api/v1/people/" + employeeId + "/onboarding/attachments/" + fileId + "/file?download=true").cookie(hr)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        mockMvc.perform(get("/api/v1/people/" + employeeId + "/documents").cookie(hr)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].kind").value("address_permanent"))
                .andExpect(jsonPath("$.data[0].label").value("Address : Permanent Address")).andExpect(jsonPath("$.data[0].fileName").value("proof.pdf"));
        mockMvc.perform(get("/api/v1/people/" + employeeId + "/documents/" + fileId + "/file").cookie(hr)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/people/" + otherEmployeeId + "/onboarding/attachments/" + fileId + "/file").cookie(hr)).andExpect(status().isNotFound()); // not that employee's file
        jdbc.update("UPDATE employee_onboardings SET status = 'APPROVED' WHERE employee_id = ?", employeeId); // approved: the portal is open again
        mockMvc.perform(get("/api/v1/profile").cookie(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.documents[0].fileName").value("proof.pdf"));
    }

    @Test
    void anEmployeeCannotReachSomeoneElsesOnboarding() throws Exception {
        saveOk(employee, 1, STEP1);
        uploadPdf(employee, "address_permanent");
        String fileId = jdbc.queryForObject("SELECT id::text FROM onboarding_attachments WHERE onboarding_id = (SELECT id FROM employee_onboardings WHERE employee_id = ?)", String.class, employeeId);
        // /mine is always the caller's own
        assertThat(mine(other).at("/personal/bloodGroup").isNull()).isTrue();
        assertThat(mine(other).get("status").asText()).isEqualTo("NOT_STARTED");
        mockMvc.perform(get("/api/v1/onboarding/mine/attachments/" + fileId + "/file").cookie(other)).andExpect(status().isNotFound());
        // the HR view needs the permission
        mockMvc.perform(get("/api/v1/people/" + employeeId + "/onboarding").cookie(other)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/people/" + employeeId + "/onboarding/attachments/" + fileId + "/file").cookie(other)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/people/" + employeeId + "/onboarding")).andExpect(status().isUnauthorized());
        // an employee of another business is invisible to HR
        TestData stranger = new TestData(jdbc).inBusiness("wizard-other-" + suffix);
        UUID strangerId = stranger.employee("ST-" + suffix, "st." + suffix + "@example.com", stranger.role("employee", "self"));
        jdbc.update("INSERT INTO employee_onboardings (id, business_id, employee_id, status) VALUES (?, ?, ?, 'NOT_STARTED')", UUID.randomUUID(), stranger.business(), strangerId);
        mockMvc.perform(get("/api/v1/people/" + strangerId + "/onboarding").cookie(hr)).andExpect(status().isNotFound());
    }

    @Test
    void theSessionAndShellSayWhetherTheWizardMustBeShown() throws Exception {
        mockMvc.perform(get("/api/v1/shell").cookie(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.onboarding.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.onboarding.isRequired").value(true));
        mockMvc.perform(get("/api/v1/me").cookie(employee)).andExpect(jsonPath("$.data.onboarding.isRequired").value(true));
        mockMvc.perform(get("/api/v1/shell").cookie(old)).andExpect(jsonPath("$.data.onboarding.status").value("NOT_REQUIRED")).andExpect(jsonPath("$.data.onboarding.isRequired").value(false));
        mockMvc.perform(get("/api/v1/shell").cookie(hr)).andExpect(jsonPath("$.data.onboarding.status").value("NOT_REQUIRED")); // no wizard row at all
        saveOk(employee, 1, STEP1);
        mockMvc.perform(get("/api/v1/shell").cookie(employee)).andExpect(jsonPath("$.data.onboarding.status").value("IN_PROGRESS")).andExpect(jsonPath("$.data.onboarding.isRequired").value(true));
        jdbc.update("UPDATE employee_onboardings SET status = 'SUBMITTED' WHERE employee_id = ?", employeeId);
        mockMvc.perform(get("/api/v1/shell").cookie(employee)).andExpect(jsonPath("$.data.onboarding.status").value("SUBMITTED")).andExpect(jsonPath("$.data.onboarding.isRequired").value(true));
        jdbc.update("UPDATE employee_onboardings SET status = 'CHANGES_REQUESTED' WHERE employee_id = ?", employeeId);
        mockMvc.perform(get("/api/v1/shell").cookie(employee)).andExpect(jsonPath("$.data.onboarding.isRequired").value(true));
        jdbc.update("UPDATE employee_onboardings SET status = 'APPROVED' WHERE employee_id = ?", employeeId);
        mockMvc.perform(get("/api/v1/shell").cookie(employee)).andExpect(jsonPath("$.data.onboarding.status").value("APPROVED")).andExpect(jsonPath("$.data.onboarding.isRequired").value(false));
    }
}
