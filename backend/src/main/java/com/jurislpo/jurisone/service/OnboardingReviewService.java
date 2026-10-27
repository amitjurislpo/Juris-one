package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.engine.OnboardingReview;
import com.jurislpo.jurisone.exception.ApiException;
import com.jurislpo.jurisone.exception.FieldErrorsException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.EmployeeOnboardingRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HR's decision on a submitted onboarding: approve it (the employee's portal opens) or send it back with a comment and a flag per section
 * that needs work (the employee corrects it and submits again). Both tell the employee in the app and by email, and are audited.
 */
@Service
@Transactional
public class OnboardingReviewService {

    public record FlagInput(String section, String comment) {}

    public record RejectionInput(String comment, List<FlagInput> flags) {}

    private static final int MAX_COMMENT = 1000;

    private final EmployeeOnboardingRepository repo;
    private final AuditService audit;
    private final NotificationService notifications;
    private final MailService mail;

    public OnboardingReviewService(EmployeeOnboardingRepository repo, AuditService audit, NotificationService notifications, MailService mail) {
        this.repo = repo;
        this.audit = audit;
        this.notifications = notifications;
        this.mail = mail;
    }

    /** The onboarding row of an employee that is waiting for HR; 404 when there is none (or it belongs to another business), 409 in any other status. */
    private Map<String, Object> submittedRow(UUID employeeId) {
        List<Map<String, Object>> rows = repo.findByEmployee(employeeId);
        if (rows.isEmpty() || EmployeeOnboardingService.NOT_REQUIRED.equals(rows.get(0).get("status"))) throw new UserException(HttpStatus.NOT_FOUND, "Onboarding not found.");
        if (!EmployeeOnboardingService.SUBMITTED.equals(rows.get(0).get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a submitted onboarding can be approved or sent back (this one is " + rows.get(0).get("status") + ").");
        }
        return rows.get(0);
    }

    public void approve(CurrentUser reviewer, String employeeIdRaw, String ipAddress) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        UUID onboardingId = (UUID) submittedRow(employeeId).get("id");
        repo.markApproved(onboardingId, reviewer.id());
        audit.log(reviewer.id(), "onboarding.approved", "employee_onboarding", onboardingId.toString(), Map.of("status", EmployeeOnboardingService.SUBMITTED),
                Map.of("status", EmployeeOnboardingService.APPROVED, "employeeId", employeeId.toString()), ipAddress);
        Map<String, Object> employee = repo.findEmployeeContact(employeeId).get(0);
        notifications.notify(employeeId, "Your onboarding was approved by " + reviewer.name(), "/home");
        mail.send((String) employee.get("email"), MailTemplates.onboardingApproved((String) employee.get("full_name"), reviewer.name()));
    }

    public void reject(CurrentUser reviewer, String employeeIdRaw, RejectionInput input, String ipAddress) {
        UUID employeeId = Check.uuid(employeeIdRaw);
        UUID onboardingId = (UUID) submittedRow(employeeId).get("id");
        Map<String, String> errors = new LinkedHashMap<>();
        String comment = input == null || input.comment() == null ? "" : input.comment().trim();
        if (comment.isEmpty()) errors.put("comment", "Write a comment for the employee");
        else if (comment.length() > MAX_COMMENT) errors.put("comment", "Keep the comment under " + MAX_COMMENT + " characters");
        List<FlagInput> flags = input == null || input.flags() == null ? List.of() : input.flags();
        if (flags.isEmpty()) errors.put("flags", "Flag at least one section");
        Set<String> seen = new HashSet<>();
        List<FlagInput> cleaned = new ArrayList<>();
        for (int i = 0; i < flags.size(); i++) {
            FlagInput flag = flags.get(i);
            String section = flag == null || flag.section() == null ? "" : flag.section().trim();
            String flagComment = flag == null || flag.comment() == null ? "" : flag.comment().trim();
            if (!OnboardingReview.SECTIONS.containsKey(section)) errors.put("flags[" + i + "].section", "Unknown section");
            else if (!seen.add(section)) errors.put("flags[" + i + "].section", "Each section can be flagged once");
            if (flagComment.isEmpty()) errors.put("flags[" + i + "].comment", "Write what is wrong in this section");
            else if (flagComment.length() > MAX_COMMENT) errors.put("flags[" + i + "].comment", "Keep the comment under " + MAX_COMMENT + " characters");
            cleaned.add(new FlagInput(section, flagComment));
        }
        if (!errors.isEmpty()) throw new FieldErrorsException(errors);

        repo.markChangesRequested(onboardingId, reviewer.id(), comment);
        for (FlagInput flag : cleaned) repo.insertReviewFlag(onboardingId, flag.section(), flag.comment());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", EmployeeOnboardingService.CHANGES_REQUESTED);
        after.put("employeeId", employeeId.toString());
        after.put("sections", cleaned.stream().map(FlagInput::section).toList());
        audit.log(reviewer.id(), "onboarding.changes_requested", "employee_onboarding", onboardingId.toString(), Map.of("status", EmployeeOnboardingService.SUBMITTED), after, ipAddress);
        Map<String, Object> employee = repo.findEmployeeContact(employeeId).get(0);
        notifications.notify(employeeId, "Your onboarding needs changes: " + comment, "/onboarding");
        List<String[]> sections = cleaned.stream().map(flag -> new String[] {OnboardingReview.SECTIONS.get(flag.section()), flag.comment()}).toList();
        mail.send((String) employee.get("email"), MailTemplates.onboardingChangesRequested((String) employee.get("full_name"), reviewer.name(), comment, sections));
    }
}
