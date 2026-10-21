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

    record FileKind(String ext, String mime, byte[] magic) {}

    static final List<FileKind> FILES = List.of(new FileKind("pdf", "application/pdf", new byte[] {0x25, 0x50, 0x44, 0x46}),
            new FileKind("png", "image/png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47}), new FileKind("jpg", "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}));

    /** What the candidate fills in. Stored as encrypted JSON. */
    public record Form(String firstName, String middleName, String lastName, String fatherName, String dateOfBirth, String gender, String phoneCountry,
                       String phone, String currentAddress, String permanentAddress, String pan, String aadhaar, String uan, String bankName, String bankAccount,
                       String ifsc, String previousEmployer, String previousDesignation, String emergencyName, String emergencyRelation,
                       String emergencyPhoneCountry, String emergencyPhone) {

        /** A blank form with the name HR typed split into first / middle / last. */
        static Form empty(String name) {
            String[] parts = name.trim().split("\\s+");
            String first = parts[0], last = parts.length > 1 ? parts[parts.length - 1] : "", middle = parts.length > 2 ? String.join(" ", Arrays.copyOfRange(parts, 1, parts.length - 1)) : "";
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
    private final Path storageDir;
    private final String appUrl;

    public OnboardingService(OnboardingRepository repo, PiiCrypto pii, ObjectMapper json, MailService mail, NotificationService notifications, AuditService audit,
                             PeopleService people, SubscriptionService subscriptions, SettingsService settings, JurisOneProperties props, @Value("${jurisone.app-url:http://localhost:3005}") String appUrl) {
        this.repo = repo;
        this.pii = pii;
        this.json = json;
        this.mail = mail;
        this.notifications = notifications;
        this.audit = audit;
        this.people = people;
        this.subscriptions = subscriptions;
        this.settings = settings;
        this.storageDir = Path.of(props.storageDir()).toAbsolutePath().normalize();
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
    public String saveDefaults(CurrentUser user, List<String> docs, String ip) {
        List<String> before = defaultDocs();
        List<String> valid = validDocs(docs);
        settings.save("onboarding.policy", json.valueToTree(Map.of("requiredDocs", valid)));
        audit.log(user.id(), "onboarding.default_documents", "setting", "onboarding.policy", Map.of("requiredDocs", before), Map.of("requiredDocs", valid), ip);
        return valid.isEmpty() ? "New onboardings ask for no documents by default" : "Default documents saved (" + valid.size() + ")";
    }

    // ───────────────────────── helpers ─────────────────────────

    public static String iso(Object timestamp) {
        return timestamp == null ? null : Format.isoTimestamp(((Timestamp) timestamp).toInstant());
    }

    static String str(Object value) {
        return value == null ? null : value.toString();
    }

    static String trim(String value, int max, String field) {
        String t = value == null ? "" : value.trim();
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

    String writeForm(Form form) {
        try {
            return pii.encrypt(json.writeValueAsString(form));
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
        for (String key : required) {
            long has = docs.stream().filter(d -> key.equals(d.get("kind")) && !"REJECTED".equals(d.get("status"))).count();
            long ok = docs.stream().filter(d -> key.equals(d.get("kind")) && "VERIFIED".equals(d.get("status"))).count();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("kind", key);
            view.put("label", KINDS.get(key));
            view.put("needed", needed(key));
            view.put("uploaded", has);
            view.put("verified", ok);
            out.add(view);
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

    record ValidatedPlan(String name, String email, String designation, UUID departmentId, UUID roleId, UUID managerId, LocalDate joining, String location,
                     String[] required) {}

    ValidatedPlan validate(Plan plan, UUID editing) {
        if (plan == null) throw new UserException(HttpStatus.BAD_REQUEST, "Onboarding details are missing.");
        String name = Check.trimmed(plan.candidateName(), 2, "Enter the candidate's name.", 120, "Keep the name under 120 characters.");
        String email = plan.personalEmail() == null ? "" : plan.personalEmail().trim().toLowerCase(Locale.ROOT);
        if (!PeopleRules.EMAIL_RE.matcher(email).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the candidate's personal email.");
        List<String> open = repo.findOpenCandidateNames(email, editing);
        if (!open.isEmpty()) throw new UserException("An onboarding for " + email + " is already open (" + open.get(0) + ").");
        String designation = trim(plan.designation(), 120, "designation");
        UUID department = blankUuid(plan.departmentId());
        if (department != null && !repo.departmentIsActive(department)) throw new UserException("Pick a department.");
        UUID role = blankUuid(plan.roleId());
        if (role != null && !repo.roleIsAssignable(role)) throw new UserException("Pick a role.");
        UUID manager = blankUuid(plan.managerId());
        if (manager != null && !repo.employeeIsCurrent(manager)) {
            throw new UserException("Pick a current employee as reporting manager.");
        }
        LocalDate joining = plan.joiningDate() == null || plan.joiningDate().isBlank() ? null : LocalDate.parse(Check.isoDate(plan.joiningDate(), "Pick the joining date."));
        String location = plan.location() == null || plan.location().isBlank() ? "IN_IST" : Check.oneOf(plan.location(), PeopleRules.LOCATIONS.stream().map(PeopleRules.Location::value).toList());
        List<String> req = plan.requiredDocs() == null ? defaultDocs() : validDocs(plan.requiredDocs());
        return new ValidatedPlan(name, email, designation.isEmpty() ? null : designation, department, role, manager, joining, location,
                req.stream().distinct().toArray(String[]::new));
    }

    static UUID blankUuid(String value) {
        return value == null || value.isBlank() ? null : Check.uuid(value);
    }

    /** Starts an onboarding and emails the candidate their link. */
    @Transactional
    public String start(CurrentUser user, Plan plan, String ip) {
        ValidatedPlan validated = validate(plan, null);
        UUID id = UUID.randomUUID();
        repo.insert(id, validated.name(), validated.email(), validated.designation(), validated.departmentId(), validated.roleId(), validated.managerId(), validated.joining() == null ? null : Date.valueOf(validated.joining()),
                validated.location(), validated.required(), user.id());
        String token = newLink(id);
        emailLink(row(id), token, null);
        audit.log(user.id(), "onboarding.start", "onboarding", id.toString(), null, Map.of("candidate", validated.name(), "email", validated.email()), ip);
        return id.toString();
    }

    @Transactional
    public void update(CurrentUser user, String idRaw, Plan plan, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> onboarding = row(id);
        if (List.of("ADDED", "CANCELLED").contains(onboarding.get("status"))) throw new UserException("This onboarding is closed.");
        ValidatedPlan validated = validate(plan, id);
        if (!validated.email().equals(onboarding.get("personal_email")) && !OPEN.contains(onboarding.get("status"))) throw new UserException("The email can't change after the candidate has submitted.");
        repo.updatePlan(id, validated.name(), validated.email(), validated.designation(), validated.departmentId(), validated.roleId(), validated.managerId(), validated.joining() == null ? null : Date.valueOf(validated.joining()),
                validated.location(), validated.required());
        audit.log(user.id(), "onboarding.update", "onboarding", id.toString(), null, Map.of("candidate", validated.name()), ip);
    }

    /** A fresh link (the old one stops working), emailed again. */
    @Transactional
    public String resend(CurrentUser user, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> onboarding = row(id);
        if (!OPEN.contains(onboarding.get("status"))) throw new UserException("The candidate has already submitted — there's nothing to fill in.");
        emailLink(onboarding, newLink(id), null);
        audit.log(user.id(), "onboarding.resend", "onboarding", id.toString(), ip);
        return "A new link was sent to " + onboarding.get("personal_email");
    }

    /** Everything HR reviews: the plan, the candidate's answers (identity numbers masked), documents and the checklist. */
    public Map<String, Object> detail(String idRaw) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> onboarding = row(id);
        Form f = readForm(onboarding);
        List<Map<String, Object>> docs = documents(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("candidateName", onboarding.get("candidate_name"));
        out.put("personalEmail", onboarding.get("personal_email"));
        out.put("designation", onboarding.get("designation"));
        out.put("departmentId", str(onboarding.get("department_id")));
        out.put("roleId", str(onboarding.get("role_id")));
        out.put("managerId", str(onboarding.get("manager_id")));
        out.put("joiningDate", onboarding.get("joining_date") == null ? null : onboarding.get("joining_date").toString());
        out.put("location", onboarding.get("location"));
        out.put("requiredDocs", requiredOf(onboarding));
        out.put("status", onboarding.get("status"));
        out.put("hrComment", onboarding.get("hr_comment"));
        out.put("linkExpiresAt", iso(onboarding.get("token_expires_at")));
        out.put("submittedAt", iso(onboarding.get("submitted_at")));
        out.put("verifiedAt", iso(onboarding.get("verified_at")));
        out.put("employeeId", str(onboarding.get("employee_id")));
        out.put("employeeCode", onboarding.get("employee_id") == null ? null : repo.findEmployeeCode((UUID) onboarding.get("employee_id")));
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
        out.put("checklist", checklist(requiredOf(onboarding), docs));
        return out;
    }

    public record DocDecision(String status, String comment) {}

    /** Verify or reject one document (a rejection needs a comment the candidate will see). */
    @Transactional
    public String decideDocument(CurrentUser user, String idRaw, String docIdRaw, DocDecision docDecision, String ip) {
        UUID id = Check.uuid(idRaw);
        UUID doc = Check.uuid(docIdRaw);
        Map<String, Object> onboarding = row(id);
        if (!List.of("SUBMITTED", "IN_PROGRESS", "CHANGES_REQUESTED", "INVITED").contains(onboarding.get("status"))) throw new UserException("This onboarding can no longer change.");
        String status = Check.oneOf(docDecision == null ? null : docDecision.status(), List.of("VERIFIED", "REJECTED"));
        String comment = docDecision.comment() == null ? "" : docDecision.comment().trim();
        if ("REJECTED".equals(status) && comment.length() < 3) throw new UserException(HttpStatus.BAD_REQUEST, "Tell the candidate what's wrong with the document.");
        if (comment.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the comment under 300 characters.");
        int count = repo.decideDocument(doc, id, status, comment.isEmpty() ? null : comment, user.id());
        if (count == 0) throw new UserException(HttpStatus.NOT_FOUND, "Document not found.");
        repo.touch(id);
        audit.log(user.id(), "onboarding.document_" + status.toLowerCase(Locale.ROOT), "onboarding_document", doc.toString(), null, Map.of("onboarding", id.toString()), ip);
        return "VERIFIED".equals(status) ? "Document verified" : "Document rejected — the candidate will be asked for a new one";
    }

    public record StatusInput(String status, String comment) {}

    /** CHANGES_REQUESTED (with a comment; a new link is emailed), VERIFIED (everything required is verified) or CANCELLED. */
    @Transactional
    public String setStatus(CurrentUser user, String idRaw, StatusInput input, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> onboarding = row(id);
        String from = (String) onboarding.get("status");
        String to = Check.oneOf(input == null ? null : input.status(), List.of("CHANGES_REQUESTED", "VERIFIED", "CANCELLED"));
        String comment = input.comment() == null ? "" : input.comment().trim();
        switch (to) {
            case "CHANGES_REQUESTED" -> {
                if (!List.of("SUBMITTED", "VERIFIED").contains(from)) throw new UserException("Only a submitted onboarding can be sent back.");
                if (comment.length() < 3) throw new UserException(HttpStatus.BAD_REQUEST, "Tell the candidate what to change.");
                if (comment.length() > 1000) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the comment under 1000 characters.");
                repo.requestChanges(id, comment);
                emailLink(onboarding, newLink(id), comment);
            }
            case "VERIFIED" -> {
                if (!"SUBMITTED".equals(from)) throw new UserException("Only a submitted onboarding can be verified.");
                List<Map<String, Object>> docs = documents(id);
                if (docs.stream().anyMatch(d -> "PENDING".equals(d.get("status")))) throw new UserException("Verify or reject every document first.");
                for (Map<String, Object> row : checklist(requiredOf(onboarding), docs)) {
                    if (((Number) row.get("verified")).intValue() < (int) row.get("needed")) {
                        throw new UserException(row.get("label") + ": " + row.get("needed") + " verified file" + ((int) row.get("needed") == 1 ? "" : "s") + " needed — send it back to the candidate.");
                    }
                }
                repo.markVerified(id, user.id());
            }
            default -> {
                if ("ADDED".equals(from) || "CANCELLED".equals(from)) throw new UserException("This onboarding is already closed.");
                repo.cancel(id);
            }
        }
        audit.log(user.id(), "onboarding.status", "onboarding", id.toString(), Map.of("status", from), Map.of("status", to), ip);
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
    public PeopleService.Saved addEmployee(CurrentUser user, String idRaw, AddInput input, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> onboarding = row(id);
        if (!"VERIFIED".equals(onboarding.get("status"))) throw new UserException("Verify the onboarding before adding the employee.");
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Employee details are missing.");
        Form form = readForm(onboarding);
        String location = input.location() == null || input.location().isBlank() ? (String) onboarding.get("location") : input.location();
        if (location == null || location.isBlank()) location = "IN_IST";
        boolean india = "IN_IST".equals(location);
        EmployeeInput e = new EmployeeInput(form.firstName(), form.middleName(), form.lastName(), form.fatherName(), input.workEmail() == null ? "" : input.workEmail(),
                form.phoneCountry(), form.phone(), location, input.joiningOffice() == null ? "" : input.joiningOffice(),
                india ? form.pan() : "", india ? form.aadhaar() : "", india ? form.uan() : "", form.currentAddress(), input.designation() == null ? "" : input.designation(),
                input.departmentId() == null ? "" : input.departmentId(), input.managerId() == null ? "" : input.managerId(), input.joiningDate() == null ? "" : input.joiningDate(),
                input.shift() == null || input.shift().isBlank() ? (india ? "IN_NIGHT" : "US_DAY") : input.shift(), input.roleId() == null ? "" : input.roleId());
        PeopleService.Saved saved = people.addWithoutAccess(user, e, ip);
        UUID employee = UUID.fromString(saved.id());
        if (!form.bankAccount().isEmpty()) {
            repo.updateEmployeeBank(employee, pii.encrypt(form.bankAccount()), PiiCrypto.last4(form.bankAccount()), form.ifsc().isEmpty() ? null : form.ifsc(),
                    form.bankName().isEmpty() ? null : form.bankName());
        }
        // personal details the employee record keeps from the candidate's form
        repo.updateEmployeePersonal(employee,
                form.dateOfBirth().isEmpty() ? null : Date.valueOf(form.dateOfBirth()), form.permanentAddress().isEmpty() ? null : pii.encrypt(form.permanentAddress()),
                form.emergencyName().isEmpty() ? null : form.emergencyName(), form.emergencyRelation().isEmpty() ? null : form.emergencyRelation(),
                form.emergencyPhone().isEmpty() ? null : form.emergencyPhoneCountry() + " " + form.emergencyPhone());
        repo.attachVerifiedDocuments(id, employee);
        repo.markAdded(id, employee);
        audit.log(user.id(), "onboarding.employee_added", "onboarding", id.toString(), null, Map.of("employee", saved.id(), "code", saved.code()), ip);
        return saved;
    }

    public record FileOut(String name, String mime, byte[] bytes) {}

    FileOut read(Map<String, Object> row) {
        Path absolutePath = storageDir.resolve((String) row.get("file_path")).normalize();
        if (!absolutePath.startsWith(storageDir)) return null;
        try {
            return new FileOut((String) row.get("file_name"), (String) row.get("mime"), Files.readAllBytes(absolutePath));
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
        Map<String, Object> row = TenantContext.call(null, () -> repo.findBusinessStatus(business));
        if (!"ACTIVE".equals(row.get("status")) || !TenantContext.call(null, () -> subscriptions.isActive(business))) {
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
            Map<String, Object> onboarding = row(l.id());
            List<Map<String, Object>> docs = documents(l.id());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("candidateName", onboarding.get("candidate_name"));
            out.put("personalEmail", onboarding.get("personal_email"));
            out.put("businessName", repo.findBusinessName(l.business()));
            out.put("designation", onboarding.get("designation"));
            out.put("joiningDate", onboarding.get("joining_date") == null ? null : onboarding.get("joining_date").toString());
            out.put("india", "IN_IST".equals(onboarding.get("location")));
            out.put("status", onboarding.get("status"));
            out.put("editable", OPEN.contains(onboarding.get("status")));
            out.put("hrComment", "CHANGES_REQUESTED".equals(onboarding.get("status")) ? onboarding.get("hr_comment") : null);
            out.put("linkExpiresAt", iso(onboarding.get("token_expires_at")));
            out.put("form", readForm(onboarding));
            out.put("documents", docs.stream().map(d -> {
                Map<String, Object> view = new LinkedHashMap<>(d);
                view.remove("verifiedBy");
                return view;
            }).toList());
            out.put("checklist", checklist(requiredOf(onboarding), docs));
            out.put("kinds", KINDS);
            return out;
        });
    }

    /** Saves the candidate's answers (partial is fine until they submit). */
    public String candidateSave(String token, Form in) {
        Link link = editable(token);
        Form form = clean(in, (String) link.row().get("candidate_name"));
        TenantContext.run(link.business(), () -> repo.saveForm(link.id(), writeForm(form)));
        return "Saved";
    }

    static Form clean(Form form, String fallbackName) {
        if (form == null) throw new UserException(HttpStatus.BAD_REQUEST, "Your details are missing.");
        Form blank = Form.empty(fallbackName);
        String pan = trim(form.pan(), 10, "PAN").toUpperCase(Locale.ROOT);
        String ifsc = trim(form.ifsc(), 11, "IFSC").toUpperCase(Locale.ROOT);
        String code = trim(form.phoneCountry(), 5, "country code");
        String ecode = trim(form.emergencyPhoneCountry(), 5, "country code");
        return new Form(trim(form.firstName(), 60, "first name"), trim(form.middleName(), 60, "middle name"), trim(form.lastName(), 60, "last name"),
                trim(form.fatherName(), 120, "father's name"), trim(form.dateOfBirth(), 10, "date of birth"), trim(form.gender(), 20, "gender"),
                code.isEmpty() ? blank.phoneCountry() : code, PeopleRules.digits(trim(form.phone(), 30, "phone")), trim(form.currentAddress(), 500, "address"),
                trim(form.permanentAddress(), 500, "address"), pan, PeopleRules.digits(trim(form.aadhaar(), 20, "Aadhaar")), PeopleRules.digits(trim(form.uan(), 20, "UAN")),
                trim(form.bankName(), 80, "bank name"), PeopleRules.digits(trim(form.bankAccount(), 24, "account number")), ifsc,
                trim(form.previousEmployer(), 120, "previous employer"), trim(form.previousDesignation(), 120, "previous designation"),
                trim(form.emergencyName(), 120, "emergency contact"), trim(form.emergencyRelation(), 40, "relationship"),
                ecode.isEmpty() ? blank.emergencyPhoneCountry() : ecode, PeopleRules.digits(trim(form.emergencyPhone(), 30, "emergency phone")));
    }

    /** The first missing / invalid answer, or null when the form is complete. */
    static String problem(Form form, boolean india) {
        if (form.firstName().isEmpty()) return "Enter your first name.";
        if (form.lastName().isEmpty()) return "Enter your last name.";
        if (form.fatherName().length() < 2) return "Enter your father's name.";
        if (!form.dateOfBirth().matches("^\\d{4}-\\d{2}-\\d{2}$")) return "Enter your date of birth.";
        try {
            LocalDate dob = LocalDate.parse(form.dateOfBirth());
            if (dob.isAfter(LocalDate.now().minusYears(14)) || dob.isBefore(LocalDate.now().minusYears(100))) return "Date of birth isn't valid: you must be at least 14 years old.";
        } catch (java.time.DateTimeException e) {
            return "Check your date of birth.";
        }
        String phone = com.jurislpo.jurisone.engine.Phones.problem(form.phoneCountry(), form.phone());
        if (phone != null) return phone + ".";
        if (form.currentAddress().length() < 10) return "Enter your current address.";
        if (india) {
            if (!form.pan().isEmpty() && !PeopleRules.PAN_RE.matcher(form.pan()).matches()) return "Enter a valid PAN, e.g. ABCDE1234F (or leave it empty).";
            if (!form.aadhaar().isEmpty() && !PeopleRules.validAadhaar(form.aadhaar())) return "Your Aadhaar number has 12 digits (or leave it empty).";
            if (!form.uan().isEmpty() && !PeopleRules.validUan(form.uan())) return "Your UAN has 12 digits (leave it empty if you don't have one).";
            if (!IFSC.matcher(form.ifsc()).matches()) return "Enter your bank's IFSC, e.g. HDFC0001234.";
        }
        if (form.bankName().length() < 2) return "Enter your bank's name.";
        if (form.bankAccount().length() < 6 || form.bankAccount().length() > 18) return "Enter your bank account number.";
        if (form.emergencyName().length() < 2) return "Enter an emergency contact.";
        String ephone = com.jurislpo.jurisone.engine.Phones.problem(form.emergencyPhoneCountry(), form.emergencyPhone());
        if (ephone != null) return "Emergency contact: " + ephone + ".";
        return null;
    }

    public Map<String, String> candidateUpload(String token, String kind, String fileName, byte[] bytes) {
        Link link = editable(token);
        if (kind == null || !KINDS.containsKey(kind)) throw new UserException(HttpStatus.BAD_REQUEST, "Choose what this document is.");
        if (bytes == null || bytes.length == 0) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a file.");
        if (bytes.length > MAX_FILE_MB * 1024 * 1024) throw new UserException(HttpStatus.BAD_REQUEST, "Files up to " + MAX_FILE_MB + " MB.");
        FileKind fileKind = FILES.stream().filter(x -> bytes.length >= x.magic().length && Arrays.equals(Arrays.copyOf(bytes, x.magic().length), x.magic())).findFirst().orElse(null);
        if (fileKind == null) throw new UserException(HttpStatus.BAD_REQUEST, "Upload a PDF, JPG or PNG.");
        return TenantContext.call(link.business(), () -> {
            int count = repo.countDocuments(link.id());
            if (count >= MAX_DOCS) throw new UserException("You can upload up to " + MAX_DOCS + " documents. Remove one first.");
            Path directory = storageDir.resolve("onboarding").resolve(link.business().toString()).resolve(link.id().toString());
            Path absolutePath = directory.resolve(UUID.randomUUID() + "." + fileKind.ext());
            try {
                Files.createDirectories(directory);
                Files.write(absolutePath, bytes);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            String name = (fileName == null ? "" : fileName).replaceAll("[^\\w.\\- ()]", "_");
            name = name.substring(0, Math.min(120, name.length()));
            if (name.isBlank()) name = "document." + fileKind.ext();
            UUID doc = UUID.randomUUID();
            repo.insertDocument(doc, link.id(), kind, storageDir.relativize(absolutePath).toString().replace('\\', '/'), name, fileKind.mime(), bytes.length,
                    com.jurislpo.jurisone.service.PolicyService.sha256(bytes));
            repo.markInProgress(link.id());
            return Map.of("id", doc.toString(), "name", name);
        });
    }

    public void candidateDelete(String token, String docIdRaw) {
        Link link = editable(token);
        UUID doc = Check.uuid(docIdRaw);
        TenantContext.run(link.business(), () -> {
            List<String> st = repo.findDocumentStatus(doc, link.id());
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
        Link link = editable(token);
        return TenantContext.call(link.business(), () -> {
            Map<String, Object> onboarding = row(link.id());
            String p = problem(readForm(onboarding), "IN_IST".equals(onboarding.get("location")));
            if (p != null) throw new UserException(p);
            for (Map<String, Object> row : checklist(requiredOf(onboarding), documents(link.id()))) {
                if (((Number) row.get("uploaded")).intValue() < (int) row.get("needed")) {
                    throw new UserException("Upload " + row.get("label") + ((int) row.get("needed") > 1 ? " (" + row.get("needed") + " files)" : "") + ".");
                }
            }
            repo.markSubmitted(link.id());
            audit.log(null, "onboarding.submit", "onboarding", link.id().toString(), null, Map.of("candidate", onboarding.get("candidate_name")), ip);
            UUID hr = (UUID) onboarding.get("created_by");
            if (hr != null) notifications.notify(hr, onboarding.get("candidate_name") + " submitted their onboarding details — ready for review", "/onboarding/" + link.id());
            return "Thank you — your details were sent to HR";
        });
    }
}
