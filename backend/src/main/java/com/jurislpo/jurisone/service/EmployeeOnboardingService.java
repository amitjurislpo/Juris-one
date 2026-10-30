package com.jurislpo.jurisone.service;

import static com.jurislpo.jurisone.config.ApiPaths.PREFIX;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.OnboardingData;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.Slot;
import com.jurislpo.jurisone.engine.OnboardingForm.Address;
import com.jurislpo.jurisone.engine.OnboardingForm.Bank;
import com.jurislpo.jurisone.engine.OnboardingForm.Course;
import com.jurislpo.jurisone.engine.OnboardingForm.Data;
import com.jurislpo.jurisone.engine.OnboardingForm.Education;
import com.jurislpo.jurisone.engine.OnboardingForm.EmergencyContact;
import com.jurislpo.jurisone.engine.OnboardingForm.Employment;
import com.jurislpo.jurisone.engine.OnboardingForm.EmploymentStep;
import com.jurislpo.jurisone.engine.OnboardingForm.FamilyMember;
import com.jurislpo.jurisone.engine.OnboardingForm.FamilyStep;
import com.jurislpo.jurisone.engine.OnboardingForm.Guardian;
import com.jurislpo.jurisone.engine.OnboardingForm.NominationStep;
import com.jurislpo.jurisone.engine.OnboardingForm.Nominations;
import com.jurislpo.jurisone.engine.OnboardingForm.Nominee;
import com.jurislpo.jurisone.engine.OnboardingForm.NomineeShare;
import com.jurislpo.jurisone.engine.OnboardingForm.Personal;
import com.jurislpo.jurisone.engine.OnboardingForm.PersonalStep;
import com.jurislpo.jurisone.engine.OnboardingForm.PresentAddress;
import com.jurislpo.jurisone.engine.OnboardingForm.Statutory;
import com.jurislpo.jurisone.engine.OnboardingForm.StatutoryStep;
import com.jurislpo.jurisone.engine.OnboardingReview;
import com.jurislpo.jurisone.engine.OnboardingRules;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.exception.ApiException;
import com.jurislpo.jurisone.exception.FieldErrorsException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.EmployeeOnboardingRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The employee onboarding wizard (references/ONBOARDING_WIZARD_SPEC.md and _API.md): the signed-in employee reads and saves their own
 * six steps and submits; HR reads the result and approves it or sends it back. Bank account number, PAN, Aadhaar and UAN are stored encrypted and only
 * ever leave masked (the full values come from the secrets endpoint). The attachments (step 6) are handled by {@link OnboardingAttachmentService}.
 */
@Service
@Transactional
public class EmployeeOnboardingService {

    public static final String NOT_REQUIRED = "NOT_REQUIRED";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String CHANGES_REQUESTED = "CHANGES_REQUESTED";
    public static final String APPROVED = "APPROVED";
    /** The employee is locked out of the portal (the wizard or the waiting page only) in these statuses. */
    public static final List<String> LOCKED_STATUSES = List.of("NOT_STARTED", "IN_PROGRESS", SUBMITTED, CHANGES_REQUESTED);
    /** The employee may change the form in these statuses (SUBMITTED waits for HR, APPROVED is final). */
    public static final List<String> EDITABLE_STATUSES = List.of("NOT_STARTED", "IN_PROGRESS", CHANGES_REQUESTED);
    static final int LAST_STEP = 6;
    static final String ALREADY_SUBMITTED = "Onboarding is already submitted.";
    static final String ALREADY_APPROVED = "Onboarding is already approved.";

    /** What the app shell needs after sign-in: where the employee stands and whether the wizard must be shown. */
    public record Summary(String status, boolean isRequired) {}

    public record FileOut(String name, String mime, byte[] bytes) {}

    private final EmployeeOnboardingRepository repo;
    private final OnboardingAttachmentService attachments;
    private final PiiCrypto piiCrypto;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ObjectMapper json;
    private final Path storageDir;

    public EmployeeOnboardingService(EmployeeOnboardingRepository repo, OnboardingAttachmentService attachments, PiiCrypto piiCrypto, AuditService audit, NotificationService notifications, ObjectMapper json,
                                     JurisOneProperties properties) {
        this.repo = repo;
        this.attachments = attachments;
        this.piiCrypto = piiCrypto;
        this.audit = audit;
        this.notifications = notifications;
        this.json = json;
        this.storageDir = Path.of(properties.storageDir()).toAbsolutePath().normalize();
    }

    // ───────────────────────── session / shell ─────────────────────────

    @Transactional(readOnly = true)
    public Summary summary(UUID employeeId) {
        List<String> found = repo.findStatus(employeeId);
        String status = found.isEmpty() ? NOT_REQUIRED : found.get(0);
        return new Summary(status, LOCKED_STATUSES.contains(status));
    }

    // ───────────────────────── reading ─────────────────────────

    private Map<String, Object> requiredRow(UUID employeeId) {
        List<Map<String, Object>> rows = repo.findByEmployee(employeeId);
        if (rows.isEmpty() || NOT_REQUIRED.equals(rows.get(0).get("status"))) throw new UserException(HttpStatus.NOT_FOUND, "Onboarding is not required for this employee.");
        return rows.get(0);
    }

    private Map<String, Object> editableRow(UUID employeeId) {
        Map<String, Object> row = requiredRow(employeeId);
        String status = (String) row.get("status");
        if (APPROVED.equals(status)) throw new ApiException(HttpStatus.CONFLICT, ALREADY_APPROVED);
        if (!EDITABLE_STATUSES.contains(status)) throw new ApiException(HttpStatus.CONFLICT, ALREADY_SUBMITTED);
        return row;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> mine(CurrentUser user) {
        return payload(user.id(), requiredRow(user.id()), false);
    }

    /** HR's read-only view of an employee's onboarding (numbers masked). */
    @Transactional(readOnly = true)
    public Map<String, Object> forEmployee(String employeeIdRaw) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        return payload(employeeId, requiredRow(employeeId), true);
    }

    private Map<String, Object> payload(UUID employeeId, Map<String, Object> row, boolean isViewedByHr) {
        UUID onboardingId = (UUID) row.get("id");
        String status = (String) row.get("status");
        Map<String, Object> employee = repo.findEmployeeHeader(employeeId).get(0);
        Data data = load(onboardingId, false);
        Personal personal = data.personal() != null ? data.personal() : new Personal(null, null, (String) employee.get("father_name"), null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        List<Slot> slots = slotsFor(onboardingId);
        List<Map<String, Object>> files = attachments.filesOf(onboardingId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("currentStep", row.get("current_step"));
        out.put("stepStatus", stepStatus(row, onboardingId, slots));
        out.put("isReadOnly", isViewedByHr || !EDITABLE_STATUSES.contains(status));
        out.put("submittedAt", row.get("submitted_at") == null ? null : Format.isoTimestamp(((Timestamp) row.get("submitted_at")).toInstant()));
        out.put("reviewComment", row.get("review_comment"));
        out.put("reviewFlags", repo.findReviewFlags(onboardingId).stream().map(flag -> Map.of("section", flag.get("section"), "comment", flag.get("comment"))).toList());
        out.put("reviewedAt", instant(row.get("reviewed_at")));
        out.put("approvedAt", instant(row.get("approved_at")));
        out.put("header", header(employeeId, employee));
        out.put("personal", personal);
        out.put("permanentAddress", data.permanentAddress() != null ? data.permanentAddress() : new Address(null, null, null, null, null, null, null, null, null));
        out.put("presentAddress", data.presentAddress() != null ? data.presentAddress() : new PresentAddress(null, null, null, null, null, null, null, null, null, false));
        out.put("emergencyContact", data.emergencyContact() != null ? data.emergencyContact() : new EmergencyContact(null, null, null));
        out.put("education", data.education());
        out.put("courses", data.courses());
        out.put("employments", data.employments());
        out.put("bank", data.bank() != null ? data.bank() : new Bank(null, null, false, null, null, null, null, null, null));
        out.put("statutory", data.statutory() != null ? data.statutory() : new Statutory(null, null, false, null, false, null, null, false, null, null, null, null));
        out.put("familyMembers", data.familyMembers());
        out.put("nominations", data.nominations());
        out.put("attachmentSlots", slots.stream().map(slot -> slotView(slot, files)).toList());
        return out;
    }

    private static String instant(Object timestamp) {
        return timestamp == null ? null : Format.isoTimestamp(((Timestamp) timestamp).toInstant());
    }

    private Map<String, Object> header(UUID employeeId, Map<String, Object> employee) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("name", employee.get("full_name"));
        header.put("employeeCode", employee.get("code"));
        Long photoVersion = PhotoService.version(employee.get("photo_updated_at"));
        header.put("photoUrl", photoVersion == null ? null : PREFIX + "/people/" + employeeId + "/photo?v=" + photoVersion);
        header.put("department", employee.get("department_name"));
        header.put("designation", employee.get("designation"));
        header.put("location", PeopleRules.locationOfTimeZone((String) employee.get("time_zone")).label());
        Map<String, Object> pointOfContact = null;
        if (employee.get("manager_name") != null) {
            pointOfContact = new LinkedHashMap<>();
            pointOfContact.put("name", employee.get("manager_name"));
            pointOfContact.put("email", employee.get("manager_email"));
            pointOfContact.put("phone", employee.get("manager_phone_enc") == null ? null : piiCrypto.decrypt((String) employee.get("manager_phone_enc")));
        }
        header.put("pointOfContact", pointOfContact);
        return header;
    }

    private Map<String, Object> slotView(Slot slot, List<Map<String, Object>> files) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("slotKey", slot.slotKey());
        view.put("group", slot.group());
        view.put("title", slot.title());
        view.put("tooltip", slot.tooltip());
        view.put("isMandatory", slot.isMandatory());
        view.put("files", files.stream().filter(file -> slot.slotKey().equals(file.get("slotKey"))).map(file -> {
            Map<String, Object> copy = new LinkedHashMap<>(file);
            copy.remove("slotKey");
            return copy;
        }).toList());
        return view;
    }

    /** The slots of an onboarding, from its saved education, employment and passport entries. */
    private List<Slot> slotsFor(UUID onboardingId) {
        List<OnboardingAttachmentSlots.Education> educations = repo.findEducation(onboardingId).stream()
                .map(row -> new OnboardingAttachmentSlots.Education((UUID) row.get("id"), (String) row.get("qualification"))).toList();
        List<OnboardingAttachmentSlots.Employment> employments = repo.findEmployments(onboardingId).stream()
                .map(row -> new OnboardingAttachmentSlots.Employment((UUID) row.get("id"), (String) row.get("company_name"))).toList();
        List<Map<String, Object>> statutory = repo.findStatutory(onboardingId);
        boolean hasPassport = !statutory.isEmpty() && statutory.get(0).get("passport_number") != null;
        return OnboardingAttachmentSlots.slotsFor(new OnboardingData(educations, employments, hasPassport));
    }

    private Map<String, String> storedStepStatus(Map<String, Object> row) {
        Map<String, String> stored = new LinkedHashMap<>();
        try {
            JsonNode node = json.readTree((String) row.get("step_status"));
            for (int step = 1; step <= LAST_STEP; step++) stored.put(String.valueOf(step), node.path(String.valueOf(step)).asText("UNVISITED"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return stored;
    }

    /** The saved step marks; the attachments step only stays complete while every mandatory slot still has a file. */
    private Map<String, String> stepStatus(Map<String, Object> row, UUID onboardingId, List<Slot> slots) {
        Map<String, String> status = storedStepStatus(row);
        if ("COMPLETE".equals(status.get("6")) && !OnboardingAttachmentSlots.missingMandatorySlots(slots, attachments.uploadedSlotKeys(onboardingId)).isEmpty()) status.put("6", "INCOMPLETE");
        return status;
    }

    // ───────────────────────── loading the saved data ─────────────────────────

    static String text(Object value) {
        return value == null ? null : value.toString();
    }

    static Boolean flag(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    static Integer number(Object value) {
        return value instanceof Number number ? Integer.valueOf(number.intValue()) : null;
    }

    static String mask(String lastFour) {
        return lastFour == null ? null : "XXXXXXXX" + lastFour;
    }

    static BigDecimal rupees(Object paise) {
        if (!(paise instanceof Number number)) return null;
        long value = number.longValue();
        return value % 100 == 0 ? BigDecimal.valueOf(value / 100) : BigDecimal.valueOf(value, 2);
    }

    /** The saved sections; {@code revealSecrets} puts the real numbers in, otherwise they are masked with a {@code hasX} flag. */
    private Data load(UUID onboardingId, boolean revealSecrets) {
        Personal personal = repo.findPersonal(onboardingId).stream().findFirst().map(row -> new Personal(text(row.get("blood_group")), text(row.get("personal_email")), text(row.get("father_name")),
                text(row.get("marital_status")), text(row.get("marriage_date")), text(row.get("spouse_name")), text(row.get("country_of_origin")), text(row.get("nationality")),
                flag(row.get("is_international_employee")), flag(row.get("is_physically_challenged")), text(row.get("disability_type")), text(row.get("gender")), text(row.get("date_of_birth")),
                (BigDecimal) row.get("height_cm"), (BigDecimal) row.get("weight_kg"), text(row.get("identification_mark")), text(row.get("religion")), text(row.get("caste")), text(row.get("hobby")))).orElse(null);
        Address permanent = null;
        PresentAddress present = null;
        for (Map<String, Object> row : repo.findAddresses(onboardingId)) {
            Address address = new Address(text(row.get("line1")), text(row.get("line2")), text(row.get("line3")), text(row.get("city")), text(row.get("district")), text(row.get("state")),
                    text(row.get("pin")), text(row.get("country")), text(row.get("phone")));
            if ("PERMANENT".equals(row.get("kind"))) permanent = address;
            else present = new PresentAddress(address.line1(), address.line2(), address.line3(), address.city(), address.district(), address.state(), address.pin(), address.country(),
                    address.phone(), flag(row.get("is_same_as_permanent")));
        }
        EmergencyContact emergency = repo.findEmergencyContact(onboardingId).stream().findFirst()
                .map(row -> new EmergencyContact(text(row.get("name")), text(row.get("mobile")), text(row.get("relationship")))).orElse(null);
        List<Education> education = repo.findEducation(onboardingId).stream().map(row -> new Education(text(row.get("id")), text(row.get("qualification")), number(row.get("from_year")),
                number(row.get("to_year")), text(row.get("institute")), text(row.get("grade_or_percentage")))).toList();
        List<Course> courses = repo.findCourses(onboardingId).stream().map(row -> new Course(text(row.get("id")), text(row.get("course_name")), text(row.get("institute")),
                number(row.get("from_year")), number(row.get("to_year")))).toList();
        List<Employment> employments = repo.findEmployments(onboardingId).stream().map(row -> new Employment(text(row.get("id")), text(row.get("company_name")), text(row.get("designation")),
                rupees(row.get("last_drawn_salary_paise")), text(row.get("from_date")), text(row.get("to_date")), text(row.get("reason_for_leaving")), text(row.get("company_address")))).toList();
        Bank bank = repo.findBank(onboardingId).stream().findFirst().map(row -> new Bank(text(row.get("country")),
                revealSecrets ? piiCrypto.decrypt((String) row.get("account_number_enc")) : mask(text(row.get("account_number_last4"))), row.get("account_number_enc") != null,
                text(row.get("ifsc")), text(row.get("bank_name")), text(row.get("branch")), text(row.get("branch_address")), text(row.get("name_as_per_bank")), text(row.get("account_type")))).orElse(null);
        Statutory statutory = repo.findStatutory(onboardingId).stream().findFirst().map(row -> new Statutory(text(row.get("previous_pf_number")),
                revealSecrets ? piiCrypto.decrypt((String) row.get("uan_enc")) : mask(text(row.get("uan_last4"))), row.get("uan_enc") != null,
                revealSecrets ? piiCrypto.decrypt((String) row.get("pan_enc")) : mask(text(row.get("pan_last4"))), row.get("pan_enc") != null, text(row.get("name_per_pan")),
                revealSecrets ? piiCrypto.decrypt((String) row.get("aadhaar_enc")) : mask(text(row.get("aadhaar_last4"))), row.get("aadhaar_enc") != null, text(row.get("name_per_aadhaar")),
                text(row.get("passport_number")), text(row.get("name_per_passport")), text(row.get("passport_expiry")))).orElse(null);
        List<FamilyMember> family = repo.findFamilyMembers(onboardingId).stream().map(row -> new FamilyMember(text(row.get("id")), text(row.get("name")), text(row.get("relationship")),
                text(row.get("date_of_birth")), text(row.get("gender")), text(row.get("blood_group")), text(row.get("nationality")), flag(row.get("is_minor")), flag(row.get("has_mental_illness")),
                Boolean.TRUE.equals(row.get("is_minor")) ? new Guardian(text(row.get("guardian_name")), text(row.get("guardian_relationship")), text(row.get("guardian_line1")),
                        text(row.get("guardian_line2")), text(row.get("guardian_line3")), text(row.get("guardian_city")), text(row.get("guardian_state")), text(row.get("guardian_country")),
                        text(row.get("guardian_pin")), text(row.get("guardian_mobile")), text(row.get("guardian_email"))) : null)).toList();
        List<NomineeShare> epf = new ArrayList<>(), gratuity = new ArrayList<>();
        Nominee eps = null, esi = null;
        for (Map<String, Object> row : repo.findNominations(onboardingId)) {
            String memberId = text(row.get("family_member_id"));
            switch ((String) row.get("scheme")) {
                case "EPF" -> epf.add(new NomineeShare(memberId, number(row.get("percentage"))));
                case "GRATUITY" -> gratuity.add(new NomineeShare(memberId, number(row.get("percentage"))));
                case "EPS" -> eps = new Nominee(memberId);
                default -> esi = new Nominee(memberId);
            }
        }
        return new Data(personal, permanent, present, emergency, education, courses, employments, bank, statutory, family, new Nominations(epf, eps, esi, gratuity));
    }

    // ───────────────────────── saving a step ─────────────────────────

    private <T> T body(JsonNode node, Class<T> type) {
        try {
            return json.treeToValue(node == null || node.isNull() ? json.createObjectNode() : node, type);
        } catch (JsonProcessingException e) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Some details are invalid.");
        }
    }

    /** Validates and saves one step; nothing is saved when it is invalid. Answers the whole payload with the next step as the current one. */
    public Map<String, Object> saveStep(CurrentUser user, int step, JsonNode requestBody, String ipAddress) {
        return saveStepOf(user.id(), user.timeZone(), user.id(), editableRow(user.id()), step, requestBody, ipAddress);
    }

    /** HR saves a step for the employee (same validation and status rules; the caller has checked that HR may edit this status). */
    public Map<String, Object> saveStepForEmployee(CurrentUser actor, String employeeIdRaw, int step, JsonNode requestBody, String ipAddress) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        return saveStepOf(employeeId, timeZoneOf(employeeId), actor.id(), requiredRow(employeeId), step, requestBody, ipAddress);
    }

    private String timeZoneOf(UUID employeeId) {
        return (String) repo.findEmployeeHeader(employeeId).get(0).get("time_zone");
    }

    private Map<String, Object> saveStepOf(UUID employeeId, String timeZone, UUID actorId, Map<String, Object> row, int step, JsonNode requestBody, String ipAddress) {
        if (step < 1 || step > LAST_STEP) throw new UserException(HttpStatus.BAD_REQUEST, "The step must be between 1 and " + LAST_STEP + ".");
        UUID onboardingId = (UUID) row.get("id");
        LocalDate today = Format.todayIn(timeZone);
        Map<String, String> stepStatus = storedStepStatus(row);
        switch (step) {
            case 1 -> savePersonal(onboardingId, body(requestBody, PersonalStep.class), today);
            case 2 -> saveEmployments(onboardingId, body(requestBody, EmploymentStep.class));
            case 3 -> saveStatutory(onboardingId, body(requestBody, StatutoryStep.class));
            case 4 -> {
                int nominationsBefore = repo.findNominations(onboardingId).size();
                saveFamily(onboardingId, body(requestBody, FamilyStep.class), today);
                if (repo.findNominations(onboardingId).size() < nominationsBefore && "COMPLETE".equals(stepStatus.get("5"))) stepStatus.put("5", "INCOMPLETE");
            }
            case 5 -> saveNominations(onboardingId, body(requestBody, NominationStep.class));
            default -> {
                Map<String, String> errors = missingAttachments(onboardingId);
                if (!errors.isEmpty()) throw new FieldErrorsException(errors);
            }
        }
        stepStatus.put(String.valueOf(step), "COMPLETE");
        int nextStep = Math.min(step + 1, LAST_STEP);
        String status = "NOT_STARTED".equals(row.get("status")) ? "IN_PROGRESS" : (String) row.get("status");
        repo.saveProgress(onboardingId, status, nextStep, stepStatusJson(stepStatus));
        repo.deleteReviewFlags(onboardingId, OnboardingReview.sectionsOfStep(step));
        audit.log(actorId, "onboarding.step_saved", "employee_onboarding", onboardingId.toString(), null,
                actorId.equals(employeeId) ? Map.of("step", step) : Map.of("step", step, "onBehalfOfEmployeeId", employeeId.toString()), ipAddress);
        return payload(employeeId, requiredRow(employeeId), false);
    }

    private String stepStatusJson(Map<String, String> stepStatus) {
        try {
            return json.writeValueAsString(stepStatus);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Remembers the step the employee is on (a stepper click) without saving data. */
    public Map<String, Object> saveCurrentStep(CurrentUser user, Integer step, String ipAddress) {
        return saveCurrentStepOf(user.id(), editableRow(user.id()), step);
    }

    /** HR remembers the step for the employee (the caller has checked that HR may edit this status). */
    public Map<String, Object> saveCurrentStepForEmployee(String employeeIdRaw, Integer step) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        return saveCurrentStepOf(employeeId, requiredRow(employeeId), step);
    }

    private Map<String, Object> saveCurrentStepOf(UUID employeeId, Map<String, Object> row, Integer step) {
        if (step == null || step < 1 || step > LAST_STEP) throw new UserException(HttpStatus.BAD_REQUEST, "The step must be between 1 and " + LAST_STEP + ".");
        repo.saveCurrentStep((UUID) row.get("id"), step);
        return payload(employeeId, requiredRow(employeeId), false);
    }

    /** The id a block keeps: the client's id when it is one of this onboarding's rows, else a new one. */
    private static UUID resolveId(String sent, Set<UUID> existing) {
        if (sent != null && PeopleRules.UUID_RE.matcher(sent).matches() && existing.contains(UUID.fromString(sent))) return UUID.fromString(sent);
        return UUID.randomUUID();
    }

    private static Set<UUID> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> (UUID) row.get("id")).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String c(String value) {
        return OnboardingRules.clean(value);
    }

    private static String[] lines(Address address) {
        return new String[] {c(address.line1()), c(address.line2()), c(address.line3()), c(address.city()), c(address.district()), c(address.state()), OnboardingRules.digits(address.pin()),
                c(address.country()), OnboardingRules.digits(address.phone())};
    }

    private void savePersonal(UUID onboardingId, PersonalStep step, LocalDate today) {
        PresentAddress presentIn = step.presentAddress();
        boolean isSame = presentIn != null && Boolean.TRUE.equals(presentIn.isSameAsPermanent());
        // "Same as above" copies every permanent value, district included
        PresentAddress present = isSame && step.permanentAddress() != null ? copyOf(step.permanentAddress()) : presentIn;
        Map<String, String> errors = OnboardingRules.personalStep(step.personal(), step.permanentAddress(), present, step.emergencyContact(), step.education(), step.courses(), today);
        if (!errors.isEmpty()) throw new FieldErrorsException(errors);

        Personal personal = step.personal();
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("bloodGroup", c(personal.bloodGroup()));
        columns.put("personalEmail", c(personal.personalEmail()).toLowerCase());
        columns.put("fatherName", c(personal.fatherName()));
        columns.put("maritalStatus", c(personal.maritalStatus()));
        boolean isMarried = "Married".equals(c(personal.maritalStatus()));
        columns.put("marriageDate", isMarried ? c(personal.marriageDate()) : null);
        columns.put("spouseName", isMarried ? c(personal.spouseName()) : null);
        columns.put("countryOfOrigin", c(personal.countryOfOrigin()));
        columns.put("nationality", c(personal.nationality()));
        columns.put("isInternationalEmployee", personal.isInternationalEmployee());
        columns.put("isPhysicallyChallenged", personal.isPhysicallyChallenged());
        columns.put("disabilityType", Boolean.TRUE.equals(personal.isPhysicallyChallenged()) ? c(personal.disabilityType()) : null);
        columns.put("gender", c(personal.gender()));
        columns.put("dateOfBirth", c(personal.dateOfBirth()));
        columns.put("heightCm", personal.heightCm());
        columns.put("weightKg", personal.weightKg());
        columns.put("identificationMark", c(personal.identificationMark()));
        columns.put("religion", c(personal.religion()));
        columns.put("caste", c(personal.caste()));
        columns.put("hobby", c(personal.hobby()));
        repo.savePersonal(onboardingId, columns);
        repo.saveAddress(onboardingId, "PERMANENT", lines(step.permanentAddress()), false);
        repo.saveAddress(onboardingId, "PRESENT", lines(present.asAddress()), isSame);
        EmergencyContact emergency = step.emergencyContact();
        repo.saveEmergencyContact(onboardingId, c(emergency.name()), OnboardingRules.digits(emergency.mobile()), c(emergency.relationship()));

        Set<UUID> existingEducation = ids(repo.findEducation(onboardingId));
        List<UUID> keptEducation = new ArrayList<>();
        int position = 0;
        for (Education education : step.education()) {
            UUID id = resolveId(education.id(), existingEducation);
            keptEducation.add(id);
            repo.upsertEducation(id, onboardingId, position++, c(education.qualification()), education.fromYear(), education.toYear(), c(education.institute()), c(education.gradeOrPercentage()));
        }
        removeBlocks("onboarding_education", onboardingId, existingEducation, keptEducation, "qualification:");

        List<Course> courses = step.courses() == null ? List.of() : step.courses();
        Set<UUID> existingCourses = ids(repo.findCourses(onboardingId));
        List<UUID> keptCourses = new ArrayList<>();
        position = 0;
        for (Course course : courses) {
            if (c(course.courseName()) == null && c(course.institute()) == null && course.fromYear() == null && course.toYear() == null) continue;
            UUID id = resolveId(course.id(), existingCourses);
            keptCourses.add(id);
            repo.upsertCourse(id, onboardingId, position++, c(course.courseName()), c(course.institute()), course.fromYear(), course.toYear());
        }
        repo.deleteOthers("onboarding_courses", onboardingId, keptCourses);
    }

    private static PresentAddress copyOf(Address address) {
        return new PresentAddress(address.line1(), address.line2(), address.line3(), address.city(), address.district(), address.state(), address.pin(), address.country(), address.phone(), true);
    }

    /** Deletes the rows that are gone, and with them their upload slot's files. */
    private void removeBlocks(String table, UUID onboardingId, Set<UUID> existing, List<UUID> kept, String slotPrefix) {
        repo.deleteOthers(table, onboardingId, kept);
        for (UUID id : existing) if (!kept.contains(id)) attachments.deleteAllFor(onboardingId, slotPrefix + id);
    }

    private void saveEmployments(UUID onboardingId, EmploymentStep step) {
        List<Employment> employments = step.employments() == null ? List.of() : step.employments();
        Map<String, String> errors = OnboardingRules.employmentStep(employments);
        if (!errors.isEmpty()) throw new FieldErrorsException(errors);
        Set<UUID> existing = ids(repo.findEmployments(onboardingId));
        List<UUID> kept = new ArrayList<>();
        int position = 0;
        for (Employment employment : employments) {
            if (!OnboardingRules.isEmploymentUsed(employment)) continue;
            UUID id = resolveId(employment.id(), existing);
            kept.add(id);
            Long paise = employment.lastDrawnSalary() == null ? null : employment.lastDrawnSalary().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            repo.upsertEmployment(id, onboardingId, position++, c(employment.companyName()), c(employment.designation()), paise, c(employment.fromDate()), c(employment.toDate()),
                    c(employment.reasonForLeaving()), c(employment.companyAddress()));
        }
        removeBlocks("onboarding_employments", onboardingId, existing, kept, "employment:");
    }

    /** What the client sent for a secret: {@code null} or the masked value keeps the stored one, anything else replaces it ({@code ""} clears it). */
    private static String resolveSecret(String sent, String stored, String storedLastFour) {
        if (sent == null) return stored;
        String trimmed = sent.trim();
        if (stored != null && storedLastFour != null && trimmed.equals(mask(storedLastFour))) return stored;
        return trimmed;
    }

    private void saveStatutory(UUID onboardingId, StatutoryStep step) {
        Data stored = load(onboardingId, true);
        List<Map<String, Object>> bankRows = repo.findBank(onboardingId), statutoryRows = repo.findStatutory(onboardingId);
        Bank bankIn = step.bank();
        Statutory statutoryIn = step.statutory();
        Bank bank = bankIn == null ? null : new Bank(bankIn.country(), resolveSecret(bankIn.bankAccountNumber(), stored.bank() == null ? null : stored.bank().bankAccountNumber(),
                bankRows.isEmpty() ? null : text(bankRows.get(0).get("account_number_last4"))), null, bankIn.ifsc(), bankIn.bankName(), bankIn.branch(), bankIn.branchAddress(),
                bankIn.nameAsPerBank(), bankIn.accountType());
        Statutory statutory = statutoryIn == null ? null : new Statutory(statutoryIn.previousPfNumber(),
                resolveSecret(statutoryIn.uan(), stored.statutory() == null ? null : stored.statutory().uan(), statutoryRows.isEmpty() ? null : text(statutoryRows.get(0).get("uan_last4"))), null,
                resolveSecret(statutoryIn.pan(), stored.statutory() == null ? null : stored.statutory().pan(), statutoryRows.isEmpty() ? null : text(statutoryRows.get(0).get("pan_last4"))), null,
                statutoryIn.namePerPan(), resolveSecret(statutoryIn.aadhaar(), stored.statutory() == null ? null : stored.statutory().aadhaar(),
                        statutoryRows.isEmpty() ? null : text(statutoryRows.get(0).get("aadhaar_last4"))), null, statutoryIn.namePerAadhaar(), statutoryIn.passportNumber(),
                statutoryIn.namePerPassport(), statutoryIn.passportExpiry());
        Map<String, String> errors = OnboardingRules.statutoryStep(bank, statutory);
        if (!errors.isEmpty()) throw new FieldErrorsException(errors);

        String account = OnboardingRules.digits(bank.bankAccountNumber());
        repo.saveBank(onboardingId, c(bank.country()), piiCrypto.encrypt(account), PiiCrypto.last4(account), c(bank.ifsc()).toUpperCase(), c(bank.bankName()), c(bank.branch()),
                c(bank.branchAddress()), c(bank.nameAsPerBank()), c(bank.accountType()));
        String uan = OnboardingRules.digits(statutory.uan()), pan = c(statutory.pan()).toUpperCase(), aadhaar = OnboardingRules.digits(statutory.aadhaar());
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("previousPfNumber", c(statutory.previousPfNumber()));
        columns.put("uanEnc", piiCrypto.encrypt(uan));
        columns.put("uanLast4", PiiCrypto.last4(uan));
        columns.put("panEnc", piiCrypto.encrypt(pan));
        columns.put("panLast4", PiiCrypto.last4(pan));
        columns.put("namePerPan", c(statutory.namePerPan()));
        columns.put("aadhaarEnc", piiCrypto.encrypt(aadhaar));
        columns.put("aadhaarLast4", PiiCrypto.last4(aadhaar));
        columns.put("namePerAadhaar", c(statutory.namePerAadhaar()));
        columns.put("passportNumber", c(statutory.passportNumber()));
        columns.put("namePerPassport", c(statutory.namePerPassport()));
        columns.put("passportExpiry", c(statutory.passportNumber()) == null ? null : c(statutory.passportExpiry()));
        repo.saveStatutory(onboardingId, columns);
        if (c(statutory.passportNumber()) == null) attachments.deleteAllFor(onboardingId, "passport");
    }

    private void saveFamily(UUID onboardingId, FamilyStep step, LocalDate today) {
        List<FamilyMember> members = step.familyMembers() == null ? List.of() : step.familyMembers();
        Map<String, String> errors = OnboardingRules.familyStep(members, today);
        if (!errors.isEmpty()) throw new FieldErrorsException(errors);
        Set<UUID> existing = ids(repo.findFamilyMembers(onboardingId));
        List<UUID> kept = new ArrayList<>();
        int position = 0;
        for (FamilyMember member : members) {
            UUID id = resolveId(member.id(), existing);
            kept.add(id);
            boolean isMinor = Boolean.TRUE.equals(member.isMinor());
            Guardian guardian = isMinor ? member.guardian() : null;
            Map<String, Object> columns = new LinkedHashMap<>();
            columns.put("name", c(member.name()));
            columns.put("relationship", c(member.relationship()));
            columns.put("dateOfBirth", c(member.dateOfBirth()));
            columns.put("gender", c(member.gender()));
            columns.put("bloodGroup", c(member.bloodGroup()));
            columns.put("nationality", c(member.nationality()));
            columns.put("isMinor", isMinor);
            columns.put("hasMentalIllness", Boolean.TRUE.equals(member.hasMentalIllness()));
            columns.put("guardianName", guardian == null ? null : c(guardian.name()));
            columns.put("guardianRelationship", guardian == null ? null : c(guardian.relationship()));
            columns.put("guardianLine1", guardian == null ? null : c(guardian.line1()));
            columns.put("guardianLine2", guardian == null ? null : c(guardian.line2()));
            columns.put("guardianLine3", guardian == null ? null : c(guardian.line3()));
            columns.put("guardianCity", guardian == null ? null : c(guardian.city()));
            columns.put("guardianState", guardian == null ? null : c(guardian.state()));
            columns.put("guardianCountry", guardian == null ? null : c(guardian.country()));
            columns.put("guardianPin", guardian == null ? null : OnboardingRules.digits(guardian.pin()));
            columns.put("guardianMobile", guardian == null ? null : OnboardingRules.digits(guardian.mobile()));
            columns.put("guardianEmail", guardian == null ? null : c(guardian.email()));
            repo.upsertFamilyMember(id, onboardingId, position++, columns);
        }
        repo.deleteOthers("onboarding_family_members", onboardingId, kept); // the nominations of a removed member go with it
    }

    private void saveNominations(UUID onboardingId, NominationStep step) {
        Set<String> memberIds = ids(repo.findFamilyMembers(onboardingId)).stream().map(UUID::toString).collect(Collectors.toSet());
        Map<String, String> errors = OnboardingRules.nominationStep(step.nominations(), memberIds);
        if (!errors.isEmpty()) throw new FieldErrorsException(errors);
        repo.deleteNominations(onboardingId);
        Nominations nominations = step.nominations();
        if (nominations == null) return;
        int position = 0;
        if (nominations.epf() != null) for (NomineeShare share : nominations.epf()) repo.insertNomination(onboardingId, "EPF", position++, UUID.fromString(share.familyMemberId()), share.percentage());
        position = 0;
        if (nominations.gratuity() != null) for (NomineeShare share : nominations.gratuity()) repo.insertNomination(onboardingId, "GRATUITY", position++, UUID.fromString(share.familyMemberId()), share.percentage());
        if (nominations.eps() != null) repo.insertNomination(onboardingId, "EPS", 0, UUID.fromString(nominations.eps().familyMemberId()), null);
        if (nominations.esi() != null) repo.insertNomination(onboardingId, "ESI", 0, UUID.fromString(nominations.esi().familyMemberId()), null);
    }

    private Map<String, String> missingAttachments(UUID onboardingId) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (Slot slot : OnboardingAttachmentSlots.missingMandatorySlots(slotsFor(onboardingId), attachments.uploadedSlotKeys(onboardingId))) {
            errors.put("attachments." + slot.slotKey(), slot.title() + " needs at least one file");
        }
        return errors;
    }

    // ───────────────────────── submission (HR decides in OnboardingReviewService) ─────────────────────────

    /** Validates every step and the mandatory attachments, then sends it to HR (SUBMITTED; a resubmission after CHANGES_REQUESTED clears HR's flags and comment). */
    public Map<String, Object> submit(CurrentUser user, String ipAddress) {
        return submitOf(user.id(), user.timeZone(), user.id(), editableRow(user.id()), ipAddress);
    }

    /** HR submits for the employee (the caller has checked that HR may edit this status). */
    public Map<String, Object> submitForEmployee(CurrentUser actor, String employeeIdRaw, String ipAddress) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        return submitOf(employeeId, timeZoneOf(employeeId), actor.id(), requiredRow(employeeId), ipAddress);
    }

    private Map<String, Object> submitOf(UUID employeeId, String timeZone, UUID actorId, Map<String, Object> row, String ipAddress) {
        UUID onboardingId = (UUID) row.get("id");
        Data data = load(onboardingId, true);
        LocalDate today = Format.todayIn(timeZone);
        Map<String, String> errors = new LinkedHashMap<>();
        errors.putAll(OnboardingRules.personalStep(data.personal(), data.permanentAddress(), data.presentAddress(), data.emergencyContact(), data.education(), data.courses(), today));
        errors.putAll(OnboardingRules.employmentStep(data.employments()));
        errors.putAll(OnboardingRules.statutoryStep(data.bank(), data.statutory()));
        errors.putAll(OnboardingRules.familyStep(data.familyMembers(), today));
        errors.putAll(OnboardingRules.nominationStep(data.nominations(), data.familyMembers().stream().map(FamilyMember::id).collect(Collectors.toSet())));
        errors.putAll(missingAttachments(onboardingId));
        if (!errors.isEmpty()) throw new FieldErrorsException(errors);
        Map<String, String> stepStatus = new LinkedHashMap<>();
        for (int step = 1; step <= LAST_STEP; step++) stepStatus.put(String.valueOf(step), "COMPLETE");
        repo.saveProgress(onboardingId, (String) row.get("status"), LAST_STEP, stepStatusJson(stepStatus));
        repo.markSubmitted(onboardingId);
        audit.log(actorId, "onboarding.submitted", "employee_onboarding", onboardingId.toString(), Map.of("status", row.get("status")),
                actorId.equals(employeeId) ? Map.of("status", SUBMITTED) : Map.of("status", SUBMITTED, "onBehalfOfEmployeeId", employeeId.toString()), ipAddress);
        if (actorId.equals(employeeId)) { // HR submitting for the employee does not notify HR
            String employeeName = (String) repo.findEmployeeContact(employeeId).get(0).get("full_name");
            for (UUID reviewerId : repo.findReviewers()) notifications.notify(reviewerId, employeeName + " submitted their onboarding for review", "/onboarding/hires/" + employeeId);
        }
        return payload(employeeId, requiredRow(employeeId), false);
    }

    // ───────────────────────── the employee's documents (profile) ─────────────────────────

    /** The files of an employee's onboarding, for the profile's document list. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> documents(String employeeIdRaw) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        List<Map<String, Object>> files = repo.findEmployeeAttachments(employeeId);
        if (files.isEmpty()) return List.of();
        List<Map<String, Object>> rows = repo.findByEmployee(employeeId);
        Map<String, String> titles = new LinkedHashMap<>();
        for (Slot slot : slotsFor((UUID) rows.get(0).get("id"))) titles.put(slot.slotKey(), slot.title());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> file : files) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", file.get("id").toString());
            view.put("kind", file.get("slot_key"));
            view.put("label", titles.getOrDefault((String) file.get("slot_key"), (String) file.get("slot_key")));
            view.put("fileName", file.get("file_name"));
            view.put("uploadedAt", Format.isoTimestamp(((Timestamp) file.get("uploaded_at")).toInstant()));
            out.add(view);
        }
        return out;
    }

    /** One file of an employee's onboarding, or null when there is none (or it is gone from the disk). */
    @Transactional(readOnly = true)
    public FileOut document(String employeeIdRaw, String documentIdRaw) {
        List<Map<String, Object>> rows = repo.findEmployeeAttachment(Check.uuid(employeeIdRaw), Check.uuid(documentIdRaw));
        if (rows.isEmpty()) return null;
        Path absolutePath = storageDir.resolve((String) rows.get(0).get("storage_path")).normalize();
        if (!absolutePath.startsWith(storageDir)) return null;
        try {
            return new FileOut((String) rows.get(0).get("file_name"), (String) rows.get(0).get("mime"), Files.readAllBytes(absolutePath));
        } catch (IOException e) {
            return null;
        }
    }
}
