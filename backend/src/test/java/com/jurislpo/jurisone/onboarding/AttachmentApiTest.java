package com.jurislpo.jurisone.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.Education;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.Employment;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.OnboardingData;
import com.jurislpo.jurisone.service.OnboardingAttachmentService;
import com.jurislpo.jurisone.service.OwnOnboardingSource;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Wizard step 6 files and the owner-only secrets. The onboarding itself is described by a small in-memory source; the attachment table and the files are real. */
class AttachmentApiTest extends IntegrationTest {

    static final byte[] PDF = "%PDF-1.4\n%doc\n".getBytes(StandardCharsets.US_ASCII);
    static final Map<UUID, OwnOnboardingSource.OwnOnboarding> ONBOARDINGS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        OwnOnboardingSource ownOnboardingSource() {
            return employeeId -> Optional.ofNullable(ONBOARDINGS.get(employeeId));
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PiiCrypto piiCrypto;
    @Autowired OnboardingAttachmentService attachments;
    @Autowired JurisOneProperties properties;

    String suffix, code;
    UUID employeeId, onboardingId, educationId, employmentId;
    Cookie cookie;
    TestData data;

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    UUID onboardingFor(UUID employee, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO employee_onboardings (id, business_id, employee_id, status) VALUES (?, ?, ?, ?)", id, data.business(), employee, status);
        return id;
    }

    OwnOnboardingSource.OwnOnboarding state(UUID id, String employeeCode, boolean isSubmitted) {
        return new OwnOnboardingSource.OwnOnboarding(id, employeeCode, isSubmitted,
                new OnboardingData(List.of(new Education(educationId, "B.Com")), List.of(new Employment(employmentId, "Lex & Co")), false),
                piiCrypto.encrypt("50100123456789"), piiCrypto.encrypt("ABCPE1234F"), piiCrypto.encrypt("234567890123"));
    }

    @BeforeEach
    void setUp() throws Exception {
        suffix = TestData.letters(6);
        code = "AT" + suffix.toUpperCase();
        data = new TestData(jdbc).inBusiness("attach-" + suffix);
        employeeId = data.employee(code, "emp." + suffix + "@example.com", data.role("employee", "self"));
        // the migration creates a row only for employees that existed then; a new one may or may not get one from the app
        jdbc.update("DELETE FROM employee_onboardings WHERE employee_id = ?", employeeId);
        onboardingId = onboardingFor(employeeId, "IN_PROGRESS");
        educationId = UUID.randomUUID();
        employmentId = UUID.randomUUID();
        ONBOARDINGS.put(employeeId, state(onboardingId, code, false));
        cookie = signIn("emp." + suffix + "@example.com");
    }

    ResultActions upload(Cookie who, String slotKey, String fileName, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/onboarding/mine/attachments").file(new MockMultipartFile("file", fileName, "application/octet-stream", bytes)).param("slotKey", slotKey)
                .cookie(who).header("origin", "http://localhost:3005").header("host", "localhost:3005"));
    }

    JsonNode uploaded(String slotKey, String fileName, byte[] bytes) throws Exception {
        return json.readTree(upload(cookie, slotKey, fileName, bytes).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data");
    }

    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.cookie(cookie).header("origin", "http://localhost:3005").header("host", "localhost:3005");
    }

    Path absolutePathOf(String attachmentId) {
        return Path.of(properties.storageDir()).toAbsolutePath().normalize()
                .resolve(jdbc.queryForObject("SELECT storage_path FROM onboarding_attachments WHERE id = ?::uuid", String.class, attachmentId));
    }

    @Test
    void uploadStoresTheFileUnderMonthAndEmployeeCodeAndServesItBack() throws Exception {
        JsonNode file = uploaded("qualification:" + educationId, "../../My Degree.pdf", PDF);
        String id = file.at("/id").asText();
        assertThat(file.at("/fileName").asText()).doesNotContain("/").doesNotContain("..");
        assertThat(file.at("/mime").asText()).isEqualTo("application/pdf");
        assertThat(file.at("/slotKey").asText()).isEqualTo("qualification:" + educationId);
        String storagePath = jdbc.queryForObject("SELECT storage_path FROM onboarding_attachments WHERE id = ?::uuid", String.class, id);
        assertThat(storagePath).startsWith("onboarding-forms/" + YearMonth.now(ZoneOffset.UTC) + "/" + code + "/qualification-" + educationId + "/" + id + "-");
        assertThat(jdbc.queryForObject("SELECT sha256 FROM onboarding_attachments WHERE id = ?::uuid", String.class, id)).hasSize(64);
        assertThat(absolutePathOf(id)).exists();

        mockMvc.perform(get("/api/onboarding/mine/attachments/" + id + "/file").cookie(cookie)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("inline")));
        mockMvc.perform(get("/api/onboarding/mine/attachments/" + id + "/file?download=true").cookie(cookie)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment")));
        assertThat(attachments.uploadedSlotKeys(onboardingId)).containsExactly("qualification:" + educationId);
        assertThat(attachments.filesOf(onboardingId)).hasSize(1);
    }

    @Test
    void wrongTypeAndTooBigAndEmptyAreRefused() throws Exception {
        upload(cookie, "bank", "x.exe", PDF).andExpect(status().isBadRequest());
        upload(cookie, "bank", "x.pdf", "not a pdf at all".getBytes()).andExpect(status().isBadRequest()); // right extension, wrong content
        upload(cookie, "bank", "x.png", PDF).andExpect(status().isBadRequest());
        upload(cookie, "bank", "x.pdf", new byte[0]).andExpect(status().isBadRequest());
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        upload(cookie, "bank", "big.pdf", big).andExpect(status().isPayloadTooLarge());
        byte[] exactlyTenMegabytes = new byte[10 * 1024 * 1024];
        System.arraycopy(PDF, 0, exactlyTenMegabytes, 0, PDF.length);
        upload(cookie, "bank", "ten.pdf", exactlyTenMegabytes).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onboarding_attachments WHERE onboarding_id = ?", Integer.class, onboardingId)).isEqualTo(1);
    }

    @Test
    void docDocxJpgAndPngAreAccepted() throws Exception {
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};
        byte[] zip = {0x50, 0x4b, 0x03, 0x04, 0, 0};
        byte[] jpg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0};
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0};
        upload(cookie, "pan", "a.doc", ole).andExpect(status().isCreated());
        upload(cookie, "pan", "a.docx", zip).andExpect(status().isCreated());
        upload(cookie, "pan", "a.JPG", jpg).andExpect(status().isCreated());
        upload(cookie, "pan", "a.png", png).andExpect(status().isCreated());
    }

    @Test
    void onlyCurrentlyValidSlotsAreAccepted() throws Exception {
        upload(cookie, "nonsense", "x.pdf", PDF).andExpect(status().isUnprocessableEntity());
        upload(cookie, "passport", "x.pdf", PDF).andExpect(status().isUnprocessableEntity()); // no passport number entered
        upload(cookie, "qualification:" + UUID.randomUUID(), "x.pdf", PDF).andExpect(status().isUnprocessableEntity());
        upload(cookie, "employment:" + employmentId, "x.pdf", PDF).andExpect(status().isCreated());
    }

    @Test
    void anotherEmployeesFileIsNotFoundAndNotRemovable() throws Exception {
        JsonNode file = uploaded("bank", "bank.pdf", PDF);
        String otherEmail = "other." + suffix + "@example.com";
        String otherCode = "OT" + suffix.toUpperCase();
        UUID other = data.employee(otherCode, otherEmail, data.role("employee"));
        jdbc.update("DELETE FROM employee_onboardings WHERE employee_id = ?", other);
        ONBOARDINGS.put(other, state(onboardingFor(other, "IN_PROGRESS"), otherCode, false));
        Cookie otherCookie = signIn(otherEmail);
        mockMvc.perform(get("/api/onboarding/mine/attachments/" + file.at("/id").asText() + "/file").cookie(otherCookie)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/onboarding/mine/attachments/" + file.at("/id").asText()).cookie(otherCookie).header("origin", "http://localhost:3005").header("host", "localhost:3005"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onboarding_attachments WHERE id = ?::uuid", Integer.class, file.at("/id").asText())).isEqualTo(1);
    }

    @Test
    void notRequiredIsNotFoundAndSignedOutIsRefused() throws Exception {
        ONBOARDINGS.remove(employeeId);
        upload(cookie, "bank", "x.pdf", PDF).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/onboarding/mine/secrets").cookie(cookie)).andExpect(status().isNotFound());
        mockMvc.perform(multipart("/api/onboarding/mine/attachments").file(new MockMultipartFile("file", "x.pdf", "application/pdf", PDF)).param("slotKey", "bank")
                .header("origin", "http://localhost:3005").header("host", "localhost:3005")).andExpect(status().isUnauthorized());
    }

    @Test
    void afterSubmitWritesAreRefusedButReadingStillWorks() throws Exception {
        JsonNode file = uploaded("bank", "bank.pdf", PDF);
        ONBOARDINGS.put(employeeId, state(onboardingId, code, true));
        upload(cookie, "pan", "x.pdf", PDF).andExpect(status().isConflict());
        mockMvc.perform(write(delete("/api/onboarding/mine/attachments/" + file.at("/id").asText()))).andExpect(status().isConflict());
        mockMvc.perform(get("/api/onboarding/mine/attachments/" + file.at("/id").asText() + "/file").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void deleteRemovesTheRowAndTheFile() throws Exception {
        JsonNode file = uploaded("bank", "bank.pdf", PDF);
        Path absolute = absolutePathOf(file.at("/id").asText());
        assertThat(absolute).exists();
        mockMvc.perform(write(delete("/api/onboarding/mine/attachments/" + file.at("/id").asText()))).andExpect(status().isOk());
        assertThat(absolute).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onboarding_attachments WHERE onboarding_id = ?", Integer.class, onboardingId)).isZero();
        mockMvc.perform(get("/api/onboarding/mine/attachments/" + file.at("/id").asText() + "/file").cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test
    void deleteAllForRemovesRowsAndFilesOfASlotPrefix() throws Exception {
        JsonNode first = uploaded("qualification:" + educationId, "a.pdf", PDF);
        uploaded("qualification:" + educationId, "b.pdf", PDF);
        uploaded("bank", "c.pdf", PDF);
        Path absolute = absolutePathOf(first.at("/id").asText());
        attachments.deleteAllFor(onboardingId, "qualification:" + educationId);
        assertThat(absolute).doesNotExist();
        assertThat(attachments.uploadedSlotKeys(onboardingId)).containsExactly("bank");
        assertThat(Files.exists(absolute.getParent())).isTrue(); // only files are removed
    }

    @Test
    void secretsAreDecryptedForTheOwnerAndAudited() throws Exception {
        mockMvc.perform(get("/api/onboarding/mine/secrets").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bankAccountNumber").value("50100123456789")).andExpect(jsonPath("$.data.pan").value("ABCPE1234F"))
                .andExpect(jsonPath("$.data.aadhaar").value("234567890123")).andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'onboarding.secrets_read' AND actor_id = ?", Integer.class, employeeId)).isEqualTo(1);
    }
}
