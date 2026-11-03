package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.EmployeeOnboardingRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Initiate onboarding" for several employees at once. The employee fills the wizard (an email with the link goes out) or HR fills it on the
 * employee's behalf (no email). Employees that already have a submitted or approved onboarding, past employees and people of another business are
 * refused one by one; the others go through. Every success is audited and the mails are sent after the change is committed.
 */
@Service
public class OnboardingInvitationService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingInvitationService.class);
    public static final int MAX_EMPLOYEES = 100;
    public static final List<String> FILLED_BY = List.of("EMPLOYEE", "HR");
    /** Statuses from which onboarding can be initiated (IN_PROGRESS is a resend of the invitation, employee-filled only). */
    private static final List<String> INITIATABLE = List.of("NOT_REQUIRED", "NOT_STARTED", "IN_PROGRESS");

    public record InvitationRequest(List<String> employeeIds, String filledBy) {}

    public record Outcome(String employeeId, boolean isInitiated, String reason) {}

    public record Result(String filledBy, int initiated, int refused, List<Outcome> outcomes) {}

    private final EmployeeOnboardingRepository repo;
    private final TransactionTemplate transactionTemplate;
    private final AuditService audit;
    private final MailService mail;
    private final BusinessUrls businessUrls;

    public OnboardingInvitationService(EmployeeOnboardingRepository repo, TransactionTemplate transactionTemplate, AuditService audit, MailService mail, BusinessUrls businessUrls) {
        this.repo = repo;
        this.transactionTemplate = transactionTemplate;
        this.audit = audit;
        this.mail = mail;
        this.businessUrls = businessUrls;
    }

    public Result initiate(CurrentUser actor, InvitationRequest request, String ipAddress) {
        if (request == null || request.employeeIds() == null || request.employeeIds().isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Select at least one employee.");
        if (request.employeeIds().size() > MAX_EMPLOYEES) throw new UserException(HttpStatus.BAD_REQUEST, "Select at most " + MAX_EMPLOYEES + " employees at a time.");
        String filledBy = request.filledBy() == null || request.filledBy().isBlank() ? "EMPLOYEE" : request.filledBy().trim();
        if (!FILLED_BY.contains(filledBy)) throw new UserException(HttpStatus.BAD_REQUEST, "Choose who fills the onboarding: the employee or HR.");
        Set<UUID> ids = new LinkedHashSet<>();
        for (String raw : request.employeeIds()) ids.add(Check.uuid(raw));

        Map<UUID, Map<String, Object>> found = new LinkedHashMap<>();
        for (Map<String, Object> row : repo.findForInvitation(new ArrayList<>(ids))) found.put((UUID) row.get("employee_id"), row);

        List<Outcome> outcomes = new ArrayList<>();
        List<Runnable> mails = new ArrayList<>();
        String loginLink = businessUrls.forBusiness(actor.businessId(), "/login");
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            for (UUID id : ids) {
                Map<String, Object> row = found.get(id);
                String refusal = row == null ? "Employee not found." : refusal(row, filledBy);
                if (refusal != null) {
                    outcomes.add(new Outcome(id.toString(), false, refusal));
                    audit.log(actor.id(), "onboarding.initiation_refused", "employee", id.toString(), null, Map.of("reason", refusal), ipAddress);
                    continue;
                }
                UUID onboardingId = (UUID) row.get("onboarding_id");
                repo.markInitiated(onboardingId, filledBy);
                audit.log(actor.id(), "onboarding.initiated", "employee_onboarding", onboardingId.toString(), Map.of("status", row.get("status")),
                        Map.of("employeeId", id.toString(), "filledBy", filledBy), ipAddress);
                outcomes.add(new Outcome(id.toString(), true, null));
                if ("EMPLOYEE".equals(filledBy)) {
                    String email = (String) row.get("email"), name = (String) row.get("full_name");
                    mails.add(() -> sendInvitation(name, email, loginLink));
                }
            }
        });
        mails.forEach(Runnable::run);
        int initiated = (int) outcomes.stream().filter(Outcome::isInitiated).count();
        return new Result(filledBy, initiated, outcomes.size() - initiated, outcomes);
    }

    /** Why this employee cannot be initiated, or null when they can. */
    private static String refusal(Map<String, Object> row, String filledBy) {
        if ("EXITED".equals(row.get("employee_status"))) return "A past employee can't be onboarded.";
        String status = (String) row.get("status");
        if (!INITIATABLE.contains(status)) {
            return "APPROVED".equals(status) ? "Onboarding is already approved." : "Onboarding is already with HR or being corrected.";
        }
        if ("IN_PROGRESS".equals(status) && "HR".equals(filledBy)) return "The employee has already started filling the onboarding.";
        if ("EMPLOYEE".equals(filledBy) && !Boolean.TRUE.equals(row.get("portal_access"))) return "Portal access is off, so the employee can't sign in to fill it.";
        return null;
    }

    private void sendInvitation(String name, String email, String link) {
        try {
            mail.send(email, MailTemplates.onboardingInvitation(name, email, link));
        } catch (RuntimeException e) {
            log.warn("The onboarding invitation to {} could not be sent: {}", email, e.getMessage());
        }
    }
}
