package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.PeopleRules.EmployeeInput;
import com.jurislpo.jurisone.exception.ApiException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.EmployeeOnboardingRepository;
import com.jurislpo.jurisone.repository.OnboardingHrRepository;
import com.jurislpo.jurisone.repository.PeopleQuery;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HR's side of the onboarding wizard: starts an onboarding (creates the employee and sends the invitation), lists onboardings, and fills the
 * wizard on the employee's behalf with the very same payloads and validation as the employee ({@link EmployeeOnboardingService},
 * {@link OnboardingAttachmentService}). HR may edit in every status except APPROVED.
 */
@Service
public class OnboardingHrService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingHrService.class);
    private static final List<String> LISTED_STATUSES = List.of("NOT_STARTED", "IN_PROGRESS", "SUBMITTED", "CHANGES_REQUESTED", "APPROVED");
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    /** The master data HR gives when starting an onboarding (the first part of the add-employee form; the father's name is left to the employee). */
    public record OnboardingStart(String firstName, String middleName, String lastName, String workEmail, String phoneCountry, String phone, String location, String joiningOffice,
                                  String designation, String departmentId, String managerId, String dateOfJoining, String shift, String roleId) {}

    public record Row(String employeeId, String name, String employeeCode, String email, String designation, String department, String status, String submittedAt, Integer currentStep) {}

    public record Page(List<Row> rows, int total, int page, int take) {}

    private final PeopleService people;
    private final EmployeeOnboardingService onboarding;
    private final OnboardingAttachmentService attachments;
    private final EmployeeOnboardingRepository onboardingRepo;
    private final OnboardingHrRepository hrRepo;
    private final MailService mail;
    private final AuditService audit;
    private final BusinessUrls businessUrls;

    public OnboardingHrService(PeopleService people, EmployeeOnboardingService onboarding, OnboardingAttachmentService attachments, EmployeeOnboardingRepository onboardingRepo,
                               OnboardingHrRepository hrRepo, MailService mail, AuditService audit, BusinessUrls businessUrls) {
        this.people = people;
        this.onboarding = onboarding;
        this.attachments = attachments;
        this.onboardingRepo = onboardingRepo;
        this.hrRepo = hrRepo;
        this.mail = mail;
        this.audit = audit;
        this.businessUrls = businessUrls;
    }

    // ───────────────────────── starting and listing ─────────────────────────

    /**
     * Creates the employee (portal access on, onboarding NOT_STARTED) with the add-employee rules, then emails the invitation. The creation is
     * committed before the mail is sent, and a mail that fails is only logged: the employee is never created twice.
     */
    public UUID start(CurrentUser actor, OnboardingStart body, String ipAddress) {
        EmployeeInput input = new EmployeeInput(body.firstName(), body.middleName(), body.lastName(), "", body.workEmail(), body.phoneCountry(), body.phone(), body.location(),
                body.joiningOffice(), "", "", "", "", body.designation(), body.departmentId(), body.managerId(), body.dateOfJoining(), body.shift(), body.roleId(), true);
        PeopleService.Saved saved = people.createForOnboarding(actor, input, ipAddress);
        UUID employeeId = UUID.fromString(saved.id());
        UUID onboardingId = (UUID) onboardingRepo.findByEmployee(employeeId).get(0).get("id");
        String email = body.workEmail().trim().toLowerCase();
        audit.log(actor.id(), "onboarding.created", "employee_onboarding", onboardingId.toString(), null, Map.of("employeeId", saved.id(), "code", saved.code()), ipAddress);
        try {
            mail.send(email, MailTemplates.onboardingInvitation(body.firstName().trim(), email, businessUrls.forBusiness(actor.businessId(), "/login")));
        } catch (RuntimeException e) {
            log.warn("The onboarding invitation to {} could not be sent: {}", email, e.getMessage());
        }
        return employeeId;
    }

    @Transactional(readOnly = true)
    public Page list(Map<String, String> queryParams) {
        String status = ListParams.text(queryParams, "status", "");
        if (!status.isEmpty() && !LISTED_STATUSES.contains(status)) throw new UserException(HttpStatus.BAD_REQUEST, "Unknown status.");
        ListParams listParams = ListParams.of(queryParams, List.of("name"), "name", pageSize(queryParams), "desc");
        List<String> conditions = new ArrayList<>(List.of("o.status <> 'NOT_REQUIRED'"));
        List<Object> args = new ArrayList<>();
        if (!status.isEmpty()) {
            conditions.add("o.status = ?");
            args.add(status);
        }
        if (!listParams.search().isEmpty()) {
            conditions.add("(e.full_name ILIKE ? OR e.email ILIKE ? OR e.code ILIKE ?)");
            for (int i = 0; i < 3; i++) args.add(PeopleQuery.like(listParams.search()));
        }
        String where = " WHERE " + String.join(" AND ", conditions);
        List<Row> rows = hrRepo.findPage(where, args, listParams.take(), listParams.skip()).stream().map(row -> new Row(row.get("employee_id").toString(), (String) row.get("full_name"),
                (String) row.get("code"), (String) row.get("email"), (String) row.get("designation"), (String) row.get("department_name"), (String) row.get("status"),
                row.get("submitted_at") == null ? null : Format.isoTimestamp(((Timestamp) row.get("submitted_at")).toInstant()), ((Number) row.get("current_step")).intValue())).toList();
        return new Page(rows, hrRepo.count(where, args), listParams.page(), listParams.take());
    }

    private static int pageSize(Map<String, String> queryParams) {
        try {
            int requested = Integer.parseInt(ListParams.text(queryParams, "pageSize", "").trim());
            return requested < 1 ? DEFAULT_PAGE_SIZE : Math.min(requested, MAX_PAGE_SIZE);
        } catch (NumberFormatException e) {
            return DEFAULT_PAGE_SIZE;
        }
    }

    // ───────────────────────── on the employee's behalf ─────────────────────────

    /** The employee's onboarding exists (else 404) and is not yet approved (else 409). */
    private UUID editable(String employeeIdRaw) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        List<String> found = onboardingRepo.findStatus(employeeId);
        if (found.isEmpty() || EmployeeOnboardingService.NOT_REQUIRED.equals(found.get(0))) throw new UserException(HttpStatus.NOT_FOUND, "Onboarding is not required for this employee.");
        if ("APPROVED".equals(found.get(0))) throw new ApiException(HttpStatus.CONFLICT, "Onboarding is already approved.");
        return employeeId;
    }

    @Transactional
    public Map<String, Object> saveStep(CurrentUser actor, String employeeId, int step, JsonNode body, String ipAddress) {
        return onboarding.saveStepForEmployee(actor, editable(employeeId).toString(), step, body, ipAddress);
    }

    @Transactional
    public Map<String, Object> saveCurrentStep(String employeeId, Integer step) {
        return onboarding.saveCurrentStepForEmployee(editable(employeeId).toString(), step);
    }

    @Transactional
    public Map<String, Object> submit(CurrentUser actor, String employeeId, String ipAddress) {
        return onboarding.submitForEmployee(actor, editable(employeeId).toString(), ipAddress);
    }

    @Transactional
    public Map<String, Object> upload(CurrentUser actor, String employeeId, String slotKey, String fileName, byte[] bytes, String ipAddress) {
        return attachments.uploadForEmployee(actor, editable(employeeId), slotKey, fileName, bytes, ipAddress);
    }

    @Transactional
    public void deleteAttachment(CurrentUser actor, String employeeId, String fileId, String ipAddress) {
        attachments.deleteForEmployee(actor, editable(employeeId), fileId, ipAddress);
    }

    @Transactional
    public Map<String, String> secrets(CurrentUser actor, String employeeId, String ipAddress) {
        return attachments.secretsForEmployee(actor, editable(employeeId), ipAddress);
    }
}
