package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.crypto.Masks;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.crypto.Tokens;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.engine.PeopleRules.EmployeeInput;
import com.jurislpo.jurisone.repository.OnboardingRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Onboarding by link (SAAS_PLAN §1.6). HR ({@code people.add}) invites a candidate on their personal email; the
 * candidate fills a guided form and uploads documents through a one-time link (7 days, resendable); HR verifies each
 * document, sends it back or verifies the whole onboarding, then adds the employee — with portal access off until HR
 * turns it on. The candidate's answers are stored encrypted; every step is audited.
 */
@Service
public class OnboardingService {

    static final Duration LINK_VALID = Duration.ofDays(7);
    static final int MAX_FILE_MB = 10;
    static final int MAX_DOCS = 30;

    /** Document kind → label, in the order the candidate sees them. */
    public static final Map<String, String> KINDS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ID_PROOF", "ID proof (passport, voter ID or driving licence)");
        m.put("ADDRESS_PROOF", "Address proof");
        m.put("PAN", "PAN card");
        m.put("AADHAAR", "Aadhaar card");
        m.put("SALARY_SLIP", "Salary slips (last 3 months)");
        m.put("EDUCATION", "Education certificates");
        m.put("RELIEVING_LETTER", "Relieving letter from the previous employer");
        m.put("PHOTO", "Passport-size photo");
        m.put("OTHER", "Other");
        KINDS = java.util.Collections.unmodifiableMap(m);
    }

    public static final List<String> DEFAULT_REQUIRED = List.of("ID_PROOF", "ADDRESS_PROOF", "PAN", "AADHAAR", "SALARY_SLIP");

    /** How many files a required kind needs. */
    static int needed(String kind) {
        return "SALARY_SLIP".equals(kind) ? 3 : 1;
    }

    static final List<String> OPEN = List.of("INVITED", "IN_PROGRESS", "CHANGES_REQUESTED");
    static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    record Kind(String ext, String mime, byte[] magic) {}

    static final List<Kind> FILES = List.of(new Kind("pdf", "application/pdf", new byte[] {0x25, 0x50, 0x44, 0x46}),
            new Kind("png", "image/png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47}), new Kind("jpg", "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}));

    /** What the candidate fills in. Stored as encrypted JSON. */
    public record Form(String firstName, String middleName, String lastName, String fatherName, String dateOfBirth, String gender, String phoneCountry,
                       String phone, String currentAddress, String permanentAddress, String pan, String aadhaar, String uan, String bankName, String bankAccount,
                       String ifsc, String previousEmployer, String previousDesignation, String emergencyName, String emergencyRelation,
                       String emergencyPhoneCountry, String emergencyPhone) {

        /** A blank form with the name HR typed split into first / middle / last. */
        static Form empty(String name) {
            String[] w = name.trim().split("\\s+");
            String first = w[0], last = w.length > 1 ? w[w.length - 1] : "", middle = w.length > 2 ? String.join(" ", Arrays.copyOfRange(w, 1, w.length - 1)) : "";
            return new Form(first, middle, last, "", "", "", "+91", "", "", "", "", "", "", "", "", "", "", "", "", "", "+91", "");
        }

        public String fullName() {
            return java.util.stream.Stream.of(firstName, middleName, lastName).filter(x -> x != null && !x.isBlank()).collect(java.util.stream.Collectors.joining(" "));
        }
    }

    private final OnboardingRepository repo;
    private final PiiCrypto pii;
    private final ObjectMapper json;
    private final MailService mail;
    private final NotificationService notifications;
    private final AuditService audit;
    private final PeopleService people;
    private final SubscriptionService subscriptions;
    private final SettingsService settings;
    private final Path projectDir;
    private final String uploadDir;
    private final String appUrl;

    public OnboardingService(OnboardingRepository repo, PiiCrypto pii, ObjectMapper json, MailService mail, NotificationService notifications, AuditService audit,
                             PeopleService people, SubscriptionService subscriptions, SettingsService settings, JurisOneProperties props,
                             @Value("${jurisone.upload-dir:./storage}") String uploadDir, @Value("${jurisone.app-url:http://localhost:3005}") String appUrl) {
        this.repo = repo;
        this.pii = pii;
        this.json = json;
        this.mail = mail;
        this.notifications = notifications;
        this.audit = audit;
        this.people = people;
        this.subscriptions = subscriptions;
        this.settings = settings;
        this.projectDir = Path.of(props.projectDir() == null ? "." : props.projectDir()).toAbsolutePath().normalize();
        this.uploadDir = uploadDir;
        this.appUrl = appUrl.replaceAll("/$", "");
    }

    // ───────────────────────── the business's default document list ─────────────────────────

    static List<String> validDocs(List<String> docs) {
        if (docs == null) throw new UserException(HttpStatus.BAD_REQUEST, "Choose the documents.");
        for (String k : docs) if (k == null || !KINDS.containsKey(k) || "OTHER".equals(k)) throw new UserException(HttpStatus.BAD_REQUEST, "Unknown document: " + k);
        return docs.stream().distinct().toList();
    }

    /** What a new onboarding asks for unless HR changes the list for that candidate (per business; ID, address, PAN, Aadhaar, 3 salary slips by default). */
    public List<String> defaultDocs() {
        List<String> d = settings.onboarding().requiredDocs();
        return d == null ? DEFAULT_REQUIRED : d.stream().filter(k -> KINDS.containsKey(k) && !"OTHER".equals(k)).distinct().toList();
    }

    public Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requiredDocs", defaultDocs());
        Map<String, String> kinds = new LinkedHashMap<>(KINDS);
        kinds.remove("OTHER");
        out.put("kinds", kinds);
        return out;
    }

    @Transactional
    public String saveDefaults(CurrentUser u, List<String> docs, String ip) {
        List<String> before = defaultDocs();
        List<String> valid = validDocs(docs);
        settings.save("onboarding.policy", json.valueToTree(Map.of("requiredDocs", valid)));
        audit.log(u.id(), "onboarding.default_documents", "setting", "onboarding.policy", Map.of("requiredDocs", before), Map.of("requiredDocs", valid), ip);
        return valid.isEmpty() ? "New onboardings ask for no documents by default" : "Default documents saved (" + valid.size() + ")";
    }

    // ───────────────────────── helpers ─────────────────────────

    public static String iso(Object ts) {
        return ts == null ? null : Format.jsIso(((Timestamp) ts).toInstant());
    }

    static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static String trim(String v, int max, String field) {
        String t = v == null ? "" : v.trim();
        if (t.length() > max) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the " + field + " under " + max + " characters.");
        return t;
    }

    Form readForm(Map<String, Object> row) {
        String enc = (String) row.get("form_enc");
        if (enc == null) return Form.empty((String) row.get("candidate_name"));
        try {
            return json.readValue(pii.decrypt(enc), Form.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    String writeForm(Form f) {
        try {
            return pii.encrypt(json.writeValueAsString(f));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = repo.findById(id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Onboarding not found.");
        return rows.get(0);
    }

    List<Map<String, Object>> documents(UUID onboardingId) {
        return repo.findDocuments(onboardingId);
    }

    /** Each required kind with how many files it needs, has (not rejected) and has verified. */
    static List<Map<String, Object>> checklist(List<String> required, List<Map<String, Object>> docs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : required) {
            long has = docs.stream().filter(d -> k.equals(d.get("kind")) && !"REJECTED".equals(d.get("status"))).count();
            long ok = docs.stream().filter(d -> k.equals(d.get("kind")) && "VERIFIED".equals(d.get("status"))).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", k);
            m.put("label", KINDS.get(k));
            m.put("needed", needed(k));
            m.put("uploaded", has);
            m.put("verified", ok);
            out.add(m);
        }
        return out;
    }

    static List<String> requiredOf(Map<String, Object> row) {
        Object a = row.get("required_docs");
        try {
            return a instanceof java.sql.Array arr ? Arrays.asList((String[]) arr.getArray()) : List.of();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new one-time link; only its hash is kept. Returns the token. */
    String newLink(UUID id) {
        String token = Tokens.randomToken(32);
        repo.setLink(id, Tokens.sha256(token), Timestamp.from(Instant.now().plus(LINK_VALID)));
        return token;
    }

    void emailLink(Map<String, Object> row, String token, String note) {
        String business = repo.findBusinessName((UUID) row.get("business_id"));
        String expires = Instant.now().plus(LINK_VALID).atZone(java.time.ZoneId.of(SubscriptionService.ZONE)).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
        mail.send((String) row.get("personal_email"), MailTemplates.onboarding((String) row.get("candidate_name"), business,
                (String) row.get("designation"), appUrl + "/onboard/" + token, expires, note));
    }

    // ───────────────────────── HR ─────────────────────────

    public List<Map<String, Object>> list() {
        return repo.findAll();
    }

    /** The planned job; HR can change it until the employee is added. */
    public record Plan(String candidateName, String personalEmail, String designation, String departmentId, String roleId, String managerId, String joiningDate,
                       String location, List<String> requiredDocs) {}

    record ValidPlan(String name, String email, String designation, UUID departmentId, UUID roleId, UUID managerId, LocalDate joining, String location,
                     String[] required) {}

    ValidPlan validate(Plan in, UUID editing) {
        if (in == null) throw new UserException(HttpStatus.BAD_REQUEST, "Onboarding details are missing.");
        String name = Check.trimmed(in.candidateName(), 2, "Enter the candidate's name.", 120, "Keep the name under 120 characters.");
        String email = in.personalEmail() == null ? "" : in.personalEmail().trim().toLowerCase(Locale.ROOT);
        if (!PeopleRules.EMAIL_RE.matcher(email).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the candidate's personal email.");
        List<String> open = repo.findOpenCandidateNames(email, editing);
        if (!open.isEmpty()) throw new UserException("An onboarding for " + email + " is already open (" + open.get(0) + ").");
        String designation = trim(in.designation(), 120, "designation");
        UUID dept = blankUuid(in.departmentId());
        if (dept != null && !repo.departmentIsActive(dept)) throw new UserException("Pick a department.");
        UUID role = blankUuid(in.roleId());
        if (role != null && !repo.roleIsAssignable(role)) throw new UserException("Pick a role.");
        UUID manager = blankUuid(in.managerId());
        if (manager != null && !repo.employeeIsCurrent(manager)) {
            throw new UserException("Pick a current employee as reporting manager.");
        }
        LocalDate joining = in.joiningDate() == null || in.joiningDate().isBlank() ? null : LocalDate.parse(Check.isoDate(in.joiningDate(), "Pick the joining date."));
        String location = in.location() == null || in.location().isBlank() ? "IN_IST" : Check.oneOf(in.location(), PeopleRules.LOCATIONS.stream().map(PeopleRules.Location::v).toList());
        List<String> req = in.requiredDocs() == null ? defaultDocs() : validDocs(in.requiredDocs());
        return new ValidPlan(name, email, designation.isEmpty() ? null : designation, dept, role, manager, joining, location,
                req.stream().distinct().toArray(String[]::new));
    }

    static UUID blankUuid(String v) {
        return v == null || v.isBlank() ? null : Check.uuid(v);
    }

    /** Starts an onboarding and emails the candidate their link. */
    @Transactional
    public String start(CurrentUser u, Plan in, String ip) {
        ValidPlan p = validate(in, null);
        UUID id = UUID.randomUUID();
        repo.insert(id, p.name(), p.email(), p.designation(), p.departmentId(), p.roleId(), p.managerId(), p.joining() == null ? null : Date.valueOf(p.joining()),
                p.location(), p.required(), u.id());
        String token = newLink(id);
        emailLink(row(id), token, null);
        audit.log(u.id(), "onboarding.start", "onboarding", id.toString(), null, Map.of("candidate", p.name(), "email", p.email()), ip);
        return id.toString();
    }

    @Transactional
    public void update(CurrentUser u, String idRaw, Plan in, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> r = row(id);
        if (List.of("ADDED", "CANCELLED").contains(r.get("status"))) throw new UserException("This onboarding is closed.");
        ValidPlan p = validate(in, id);
        if (!p.email().equals(r.get("personal_email")) && !OPEN.contains(r.get("status"))) throw new UserException("The email can't change after the candidate has submitted.");
        repo.updatePlan(id, p.name(), p.email(), p.designation(), p.departmentId(), p.roleId(), p.managerId(), p.joining() == null ? null : Date.valueOf(p.joining()),
                p.location(), p.required());
        audit.log(u.id(), "onboarding.update", "onboarding", id.toString(), null, Map.of("candidate", p.name()), ip);
    }

    /** A fresh link (the old one stops working), emailed again. */
    @Transactional
    public String resend(CurrentUser u, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> r = row(id);
        if (!OPEN.contains(r.get("status"))) throw new UserException("The candidate has already submitted — there's nothing to fill in.");
        emailLink(r, newLink(id), null);
        audit.log(u.id(), "onboarding.resend", "onboarding", id.toString(), ip);
        return "A new link was sent to " + r.get("personal_email");
    }

    /** Everything HR reviews: the plan, the candidate's answers (identity numbers masked), documents and the checklist. */
    public Map<String, Object> detail(String idRaw) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> r = row(id);
        Form f = readForm(r);
        List<Map<String, Object>> docs = documents(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("candidateName", r.get("candidate_name"));
        out.put("personalEmail", r.get("personal_email"));
        out.put("designation", r.get("designation"));
        out.put("departmentId", str(r.get("department_id")));
        out.put("roleId", str(r.get("role_id")));
        out.put("managerId", str(r.get("manager_id")));
        out.put("joiningDate", r.get("joining_date") == null ? null : r.get("joining_date").toString());
        out.put("location", r.get("location"));
        out.put("requiredDocs", requiredOf(r));
        out.put("status", r.get("status"));
        out.put("hrComment", r.get("hr_comment"));
        out.put("linkExpiresAt", iso(r.get("token_expires_at")));
        out.put("submittedAt", iso(r.get("submitted_at")));
        out.put("verifiedAt", iso(r.get("verified_at")));
        out.put("employeeId", str(r.get("employee_id")));
        out.put("employeeCode", r.get("employee_id") == null ? null : repo.findEmployeeCode((UUID) r.get("employee_id")));
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("fullName", f.fullName());
        form.put("firstName", f.firstName());
        form.put("middleName", f.middleName());
        form.put("lastName", f.lastName());
        form.put("fatherName", f.fatherName());
        form.put("dateOfBirth", f.dateOfBirth());
        form.put("gender", f.gender());
        form.put("phone", f.phone().isEmpty() ? "" : f.phoneCountry() + " " + f.phone());
        form.put("currentAddress", f.currentAddress());
        form.put("permanentAddress", f.permanentAddress());
        form.put("pan", f.pan().isEmpty() ? null : Masks.pan(PiiCrypto.last4(f.pan())));
        form.put("aadhaar", f.aadhaar().isEmpty() ? null : Masks.aadhaar(PiiCrypto.last4(f.aadhaar())));
        form.put("uan", f.uan().isEmpty() ? null : Masks.uan(PiiCrypto.last4(f.uan())));
        form.put("bankName", f.bankName());
        form.put("bankAccount", f.bankAccount().isEmpty() ? null : Masks.bank(PiiCrypto.last4(f.bankAccount())));
        form.put("ifsc", f.ifsc());
        form.put("previousEmployer", f.previousEmployer());
        form.put("previousDesignation", f.previousDesignation());
        form.put("emergencyName", f.emergencyName());
        form.put("emergencyRelation", f.emergencyRelation());
        form.put("emergencyPhone", f.emergencyPhone().isEmpty() ? "" : f.emergencyPhoneCountry() + " " + f.emergencyPhone());
        out.put("form", form);
        out.put("documents", docs);
        out.put("checklist", checklist(requiredOf(r), docs));
        return out;
    }

    public record DocDecision(String status, String comment) {}

    /** Verify or reject one document (a rejection needs a comment the candidate will see). */
    @Transactional
    public String decideDocument(CurrentUser u, String idRaw, String docIdRaw, DocDecision in, String ip) {
        UUID id = Check.uuid(idRaw);
        UUID doc = Check.uuid(docIdRaw);
        Map<String, Object> r = row(id);
        if (!List.of("SUBMITTED", "IN_PROGRESS", "CHANGES_REQUESTED", "INVITED").contains(r.get("status"))) throw new UserException("This onboarding can no longer change.");
        String status = Check.oneOf(in == null ? null : in.status(), List.of("VERIFIED", "REJECTED"));
        String comment = in.comment() == null ? "" : in.comment().trim();
        if ("REJECTED".equals(status) && comment.length() < 3) throw new UserException(HttpStatus.BAD_REQUEST, "Tell the candidate what's wrong with the document.");
        if (comment.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the comment under 300 characters.");
        int n = repo.decideDocument(doc, id, status, comment.isEmpty() ? null : comment, u.id());
        if (n == 0) throw new UserException(HttpStatus.NOT_FOUND, "Document not found.");
        repo.touch(id);
        audit.log(u.id(), "onboarding.document_" + status.toLowerCase(Locale.ROOT), "onboarding_document", doc.toString(), null, Map.of("onboarding", id.toString()), ip);
        return "VERIFIED".equals(status) ? "Document verified" : "Document rejected — the candidate will be asked for a new one";
    }

    public record StatusInput(String status, String comment) {}

    /** CHANGES_REQUESTED (with a comment; a new link is emailed), VERIFIED (everything required is verified) or CANCELLED. */
    @Transactional
    public String setStatus(CurrentUser u, String idRaw, StatusInput in, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> r = row(id);
        String from = (String) r.get("status");
        String to = Check.oneOf(in == null ? null : in.status(), List.of("CHANGES_REQUESTED", "VERIFIED", "CANCELLED"));
        String comment = in.comment() == null ? "" : in.comment().trim();
        switch (to) {
            case "CHANGES_REQUESTED" -> {
                if (!List.of("SUBMITTED", "VERIFIED").contains(from)) throw new UserException("Only a submitted onboarding can be sent back.");
                if (comment.length() < 3) throw new UserException(HttpStatus.BAD_REQUEST, "Tell the candidate what to change.");
                if (comment.length() > 1000) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the comment under 1000 characters.");
                repo.requestChanges(id, comment);
                emailLink(r, newLink(id), comment);
            }
            case "VERIFIED" -> {
                if (!"SUBMITTED".equals(from)) throw new UserException("Only a submitted onboarding can be verified.");
                List<Map<String, Object>> docs = documents(id);
                if (docs.stream().anyMatch(d -> "PENDING".equals(d.get("status")))) throw new UserException("Verify or reject every document first.");
                for (Map<String, Object> c : checklist(requiredOf(r), docs)) {
                    if (((Number) c.get("verified")).intValue() < (int) c.get("needed")) {
                        throw new UserException(c.get("label") + ": " + c.get("needed") + " verified file" + ((int) c.get("needed") == 1 ? "" : "s") + " needed — send it back to the candidate.");
                    }
                }
                repo.markVerified(id, u.id());
            }
            default -> {
                if ("ADDED".equals(from) || "CANCELLED".equals(from)) throw new UserException("This onboarding is already closed.");
                repo.cancel(id);
            }
        }
        audit.log(u.id(), "onboarding.status", "onboarding", id.toString(), Map.of("status", from), Map.of("status", to), ip);
        return switch (to) {
            case "CHANGES_REQUESTED" -> "Sent back to the candidate with a new link";
            case "VERIFIED" -> "Onboarding verified — you can add the employee now";
            default -> "Onboarding cancelled — the link no longer works";
        };
    }

    /** The job details HR confirms when adding the employee (pre-filled from the plan). */
    public record AddInput(String workEmail, String designation, String departmentId, String managerId, String roleId, String joiningDate, String location,
                           String shift, String joiningOffice) {}

    /**
     * Creates the employee from a verified onboarding: identity, contact, bank details and documents come from the
     * candidate; portal access stays off and no invite is sent until HR turns access on.
     */
    @Transactional
    public PeopleService.Saved addEmployee(CurrentUser u, String idRaw, AddInput in, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> r = row(id);
        if (!"VERIFIED".equals(r.get("status"))) throw new UserException("Verify the onboarding before adding the employee.");
        if (in == null) throw new UserException(HttpStatus.BAD_REQUEST, "Employee details are missing.");
        Form f = readForm(r);
        String location = in.location() == null || in.location().isBlank() ? (String) r.get("location") : in.location();
        if (location == null || location.isBlank()) location = "IN_IST";
        boolean india = "IN_IST".equals(location);
        EmployeeInput e = new EmployeeInput(f.firstName(), f.middleName(), f.lastName(), f.fatherName(), in.workEmail() == null ? "" : in.workEmail(),
                f.phoneCountry(), f.phone(), location, in.joiningOffice() == null ? "" : in.joiningOffice(),
                india ? f.pan() : "", india ? f.aadhaar() : "", india ? f.uan() : "", f.currentAddress(), in.designation() == null ? "" : in.designation(),
                in.departmentId() == null ? "" : in.departmentId(), in.managerId() == null ? "" : in.managerId(), in.joiningDate() == null ? "" : in.joiningDate(),
                in.shift() == null || in.shift().isBlank() ? (india ? "IN_NIGHT" : "US_DAY") : in.shift(), in.roleId() == null ? "" : in.roleId());
        PeopleService.Saved saved = people.addWithoutAccess(u, e, ip);
        UUID emp = UUID.fromString(saved.id());
        if (!f.bankAccount().isEmpty()) {
            repo.updateEmployeeBank(emp, pii.encrypt(f.bankAccount()), PiiCrypto.last4(f.bankAccount()), f.ifsc().isEmpty() ? null : f.ifsc(),
                    f.bankName().isEmpty() ? null : f.bankName());
        }
        // personal details the employee record keeps from the candidate's form
        repo.updateEmployeePersonal(emp,
                f.dateOfBirth().isEmpty() ? null : Date.valueOf(f.dateOfBirth()), f.permanentAddress().isEmpty() ? null : pii.encrypt(f.permanentAddress()),
                f.emergencyName().isEmpty() ? null : f.emergencyName(), f.emergencyRelation().isEmpty() ? null : f.emergencyRelation(),
                f.emergencyPhone().isEmpty() ? null : f.emergencyPhoneCountry() + " " + f.emergencyPhone());
        repo.attachVerifiedDocuments(id, emp);
        repo.markAdded(id, emp);
        audit.log(u.id(), "onboarding.employee_added", "onboarding", id.toString(), null, Map.of("employee", saved.id(), "code", saved.code()), ip);
        return saved;
    }

    public record FileOut(String name, String mime, byte[] bytes) {}

    FileOut read(Map<String, Object> d) {
        Path abs = projectDir.resolve((String) d.get("file_path")).normalize();
        if (!abs.startsWith(projectDir)) return null;
        try {
            return new FileOut((String) d.get("file_name"), (String) d.get("mime"), Files.readAllBytes(abs));
        } catch (IOException e) {
            return null;
        }
    }

    /** A document of an onboarding (HR). */
    public FileOut file(String idRaw, String docIdRaw) {
        List<Map<String, Object>> rows = repo.findDocumentFile(Check.uuid(docIdRaw), Check.uuid(idRaw));
        return rows.isEmpty() ? null : read(rows.get(0));
    }

    /** Verified onboarding documents on an employee's record (people.view). */
    public List<Map<String, Object>> employeeDocuments(String employeeIdRaw) {
        return repo.findEmployeeDocuments(Check.uuid(employeeIdRaw));
    }

    public FileOut employeeDocument(String employeeIdRaw, String docIdRaw) {
        List<Map<String, Object>> rows = repo.findEmployeeDocumentFile(Check.uuid(docIdRaw), Check.uuid(employeeIdRaw));
        return rows.isEmpty() ? null : read(rows.get(0));
    }

    // ───────────────────────── candidate (one-time link) ─────────────────────────

    /** The onboarding behind a link, inside its business. 404 for unknown / expired links, 410 once closed. */
    record Link(UUID id, UUID business, Map<String, Object> row) {}

    Link link(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) throw new UserException(HttpStatus.NOT_FOUND, "This link isn't valid. Ask HR for a new one.");
        List<Map<String, Object>> rows = TenantContext.call(null, () -> repo.findByTokenHash(Tokens.sha256(token)));
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "This link isn't valid any more. Ask HR for a new one.");
        Map<String, Object> r = rows.get(0);
        if (((Timestamp) r.get("token_expires_at")).toInstant().isBefore(Instant.now())) throw new UserException(HttpStatus.NOT_FOUND, "This link has expired. Ask HR for a new one.");
        UUID business = (UUID) r.get("business_id");
        Map<String, Object> b = TenantContext.call(null, () -> repo.findBusinessStatus(business));
        if (!"ACTIVE".equals(b.get("status")) || !TenantContext.call(null, () -> subscriptions.isActive(business))) {
            throw new UserException(HttpStatus.SERVICE_UNAVAILABLE, "Onboarding is not available right now. Please contact HR.");
        }
        return new Link((UUID) r.get("id"), business, r);
    }

    Link editable(String token) {
        Link l = link(token);
        if (!OPEN.contains(l.row().get("status"))) throw new UserException("You've already submitted your details. HR will contact you if anything is needed.");
        return l;
    }

    /** What the candidate sees: their answers in full, the documents and what is still missing. */
    public Map<String, Object> candidateView(String token) {
        Link l = link(token);
        return TenantContext.call(l.business(), () -> {
            Map<String, Object> r = row(l.id());
            List<Map<String, Object>> docs = documents(l.id());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("candidateName", r.get("candidate_name"));
            out.put("personalEmail", r.get("personal_email"));
            out.put("businessName", repo.findBusinessName(l.business()));
            out.put("designation", r.get("designation"));
            out.put("joiningDate", r.get("joining_date") == null ? null : r.get("joining_date").toString());
            out.put("india", "IN_IST".equals(r.get("location")));
            out.put("status", r.get("status"));
            out.put("editable", OPEN.contains(r.get("status")));
            out.put("hrComment", "CHANGES_REQUESTED".equals(r.get("status")) ? r.get("hr_comment") : null);
            out.put("linkExpiresAt", iso(r.get("token_expires_at")));
            out.put("form", readForm(r));
            out.put("documents", docs.stream().map(d -> {
                Map<String, Object> m = new LinkedHashMap<>(d);
                m.remove("verifiedBy");
                return m;
            }).toList());
            out.put("checklist", checklist(requiredOf(r), docs));
            out.put("kinds", KINDS);
            return out;
        });
    }

    /** Saves the candidate's answers (partial is fine until they submit). */
    public String candidateSave(String token, Form in) {
        Link l = editable(token);
        Form f = clean(in, (String) l.row().get("candidate_name"));
        TenantContext.run(l.business(), () -> repo.saveForm(l.id(), writeForm(f)));
        return "Saved";
    }

    static Form clean(Form in, String fallbackName) {
        if (in == null) throw new UserException(HttpStatus.BAD_REQUEST, "Your details are missing.");
        Form blank = Form.empty(fallbackName);
        String pan = trim(in.pan(), 10, "PAN").toUpperCase(Locale.ROOT);
        String ifsc = trim(in.ifsc(), 11, "IFSC").toUpperCase(Locale.ROOT);
        String code = trim(in.phoneCountry(), 5, "country code");
        String ecode = trim(in.emergencyPhoneCountry(), 5, "country code");
        return new Form(trim(in.firstName(), 60, "first name"), trim(in.middleName(), 60, "middle name"), trim(in.lastName(), 60, "last name"),
                trim(in.fatherName(), 120, "father's name"), trim(in.dateOfBirth(), 10, "date of birth"), trim(in.gender(), 20, "gender"),
                code.isEmpty() ? blank.phoneCountry() : code, PeopleRules.digits(trim(in.phone(), 30, "phone")), trim(in.currentAddress(), 500, "address"),
                trim(in.permanentAddress(), 500, "address"), pan, PeopleRules.digits(trim(in.aadhaar(), 20, "Aadhaar")), PeopleRules.digits(trim(in.uan(), 20, "UAN")),
                trim(in.bankName(), 80, "bank name"), PeopleRules.digits(trim(in.bankAccount(), 24, "account number")), ifsc,
                trim(in.previousEmployer(), 120, "previous employer"), trim(in.previousDesignation(), 120, "previous designation"),
                trim(in.emergencyName(), 120, "emergency contact"), trim(in.emergencyRelation(), 40, "relationship"),
                ecode.isEmpty() ? blank.emergencyPhoneCountry() : ecode, PeopleRules.digits(trim(in.emergencyPhone(), 30, "emergency phone")));
    }

    /** The first missing / invalid answer, or null when the form is complete. */
    static String problem(Form f, boolean india) {
        if (f.firstName().isEmpty()) return "Enter your first name.";
        if (f.lastName().isEmpty()) return "Enter your last name.";
        if (f.fatherName().length() < 2) return "Enter your father's name.";
        if (!f.dateOfBirth().matches("^\\d{4}-\\d{2}-\\d{2}$")) return "Enter your date of birth.";
        try {
            LocalDate dob = LocalDate.parse(f.dateOfBirth());
            if (dob.isAfter(LocalDate.now().minusYears(14)) || dob.isBefore(LocalDate.now().minusYears(100))) return "Check your date of birth.";
        } catch (java.time.DateTimeException e) {
            return "Check your date of birth.";
        }
        String phone = com.jurislpo.jurisone.engine.Phones.problem(f.phoneCountry(), f.phone());
        if (phone != null) return phone + ".";
        if (f.currentAddress().length() < 10) return "Enter your current address.";
        if (india) {
            if (!f.pan().isEmpty() && !PeopleRules.PAN_RE.matcher(f.pan()).matches()) return "Enter a valid PAN, e.g. ABCDE1234F (or leave it empty).";
            if (!f.aadhaar().isEmpty() && !PeopleRules.validAadhaar(f.aadhaar())) return "Your Aadhaar number has 12 digits (or leave it empty).";
            if (!f.uan().isEmpty() && !PeopleRules.validUan(f.uan())) return "Your UAN has 12 digits (leave it empty if you don't have one).";
            if (!IFSC.matcher(f.ifsc()).matches()) return "Enter your bank's IFSC, e.g. HDFC0001234.";
        }
        if (f.bankName().length() < 2) return "Enter your bank's name.";
        if (f.bankAccount().length() < 6 || f.bankAccount().length() > 18) return "Enter your bank account number.";
        if (f.emergencyName().length() < 2) return "Enter an emergency contact.";
        String ephone = com.jurislpo.jurisone.engine.Phones.problem(f.emergencyPhoneCountry(), f.emergencyPhone());
        if (ephone != null) return "Emergency contact: " + ephone + ".";
        return null;
    }

    public Map<String, String> candidateUpload(String token, String kind, String fileName, byte[] bytes) {
        Link l = editable(token);
        if (kind == null || !KINDS.containsKey(kind)) throw new UserException(HttpStatus.BAD_REQUEST, "Choose what this document is.");
        if (bytes == null || bytes.length == 0) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a file.");
        if (bytes.length > MAX_FILE_MB * 1024 * 1024) throw new UserException(HttpStatus.BAD_REQUEST, "Files up to " + MAX_FILE_MB + " MB.");
        Kind k = FILES.stream().filter(x -> bytes.length >= x.magic().length && Arrays.equals(Arrays.copyOf(bytes, x.magic().length), x.magic())).findFirst().orElse(null);
        if (k == null) throw new UserException(HttpStatus.BAD_REQUEST, "Upload a PDF, JPG or PNG.");
        return TenantContext.call(l.business(), () -> {
            int count = repo.countDocuments(l.id());
            if (count >= MAX_DOCS) throw new UserException("You can upload up to " + MAX_DOCS + " documents. Remove one first.");
            Path dir = projectDir.resolve(uploadDir).normalize().resolve("onboarding").resolve(l.business().toString()).resolve(l.id().toString());
            Path abs = dir.resolve(UUID.randomUUID() + "." + k.ext());
            try {
                Files.createDirectories(dir);
                Files.write(abs, bytes);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            String name = (fileName == null ? "" : fileName).replaceAll("[^\\w.\\- ()]", "_");
            name = name.substring(0, Math.min(120, name.length()));
            if (name.isBlank()) name = "document." + k.ext();
            UUID doc = UUID.randomUUID();
            repo.insertDocument(doc, l.id(), kind, projectDir.relativize(abs).toString().replace('\\', '/'), name, k.mime(), bytes.length,
                    com.jurislpo.jurisone.service.PolicyService.sha256(bytes));
            repo.markInProgress(l.id());
            return Map.of("id", doc.toString(), "name", name);
        });
    }

    public void candidateDelete(String token, String docIdRaw) {
        Link l = editable(token);
        UUID doc = Check.uuid(docIdRaw);
        TenantContext.run(l.business(), () -> {
            List<String> st = repo.findDocumentStatus(doc, l.id());
            if (st.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Document not found.");
            if ("VERIFIED".equals(st.get(0))) throw new UserException("HR has already verified this document.");
            repo.deleteDocument(doc);
        });
    }

    public FileOut candidateFile(String token, String docIdRaw) {
        Link l = link(token);
        return TenantContext.call(l.business(), () -> {
            List<Map<String, Object>> rows = repo.findDocumentFile(Check.uuid(docIdRaw), l.id());
            return rows.isEmpty() ? null : read(rows.get(0));
        });
    }

    /** Submits for HR review: the form must be complete and every required document uploaded. */
    public String candidateSubmit(String token, String ip) {
        Link l = editable(token);
        return TenantContext.call(l.business(), () -> {
            Map<String, Object> r = row(l.id());
            String p = problem(readForm(r), "IN_IST".equals(r.get("location")));
            if (p != null) throw new UserException(p);
            for (Map<String, Object> c : checklist(requiredOf(r), documents(l.id()))) {
                if (((Number) c.get("uploaded")).intValue() < (int) c.get("needed")) {
                    throw new UserException("Upload " + c.get("label") + ((int) c.get("needed") > 1 ? " (" + c.get("needed") + " files)" : "") + ".");
                }
            }
            repo.markSubmitted(l.id());
            audit.log(null, "onboarding.submit", "onboarding", l.id().toString(), null, Map.of("candidate", r.get("candidate_name")), ip);
            UUID hr = (UUID) r.get("created_by");
            if (hr != null) notifications.notify(hr, r.get("candidate_name") + " submitted their onboarding details — ready for review", "/onboarding/" + l.id());
            return "Thank you — your details were sent to HR";
        });
    }
}
