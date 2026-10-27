package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.Education;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.Employment;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.OnboardingData;
import com.jurislpo.jurisone.repository.EmployeeOnboardingRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** What the attachment and secrets endpoints need about the signed-in employee's own onboarding (kept apart from the wizard service so the two do not depend on each other). */
@Service
public class OwnOnboardingLookup implements OwnOnboardingSource {

    private static final List<String> REQUIRED_STATUSES = List.of("NOT_STARTED", "IN_PROGRESS", "SUBMITTED", "CHANGES_REQUESTED", "APPROVED");

    private final EmployeeOnboardingRepository repo;

    public OwnOnboardingLookup(EmployeeOnboardingRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<OwnOnboarding> findOwn(UUID employeeId) {
        List<Map<String, Object>> rows = repo.findByEmployee(employeeId);
        if (rows.isEmpty() || !REQUIRED_STATUSES.contains((String) rows.get(0).get("status"))) return Optional.empty();
        UUID onboardingId = (UUID) rows.get(0).get("id");
        List<Education> educations = repo.findEducation(onboardingId).stream().map(row -> new Education((UUID) row.get("id"), (String) row.get("qualification"))).toList();
        List<Employment> employments = repo.findEmployments(onboardingId).stream().map(row -> new Employment((UUID) row.get("id"), (String) row.get("company_name"))).toList();
        List<Map<String, Object>> statutory = repo.findStatutory(onboardingId);
        String passportNumber = statutory.isEmpty() ? null : (String) statutory.get(0).get("passport_number");
        List<Map<String, Object>> bank = repo.findBank(onboardingId);
        return Optional.of(new OwnOnboarding(onboardingId, repo.findEmployeeHeader(employeeId).get(0).get("code").toString(), !EmployeeOnboardingService.EDITABLE_STATUSES.contains((String) rows.get(0).get("status")),
                new OnboardingData(educations, employments, passportNumber != null && !passportNumber.isBlank()),
                bank.isEmpty() ? null : (String) bank.get(0).get("account_number_enc"), statutory.isEmpty() ? null : (String) statutory.get(0).get("pan_enc"),
                statutory.isEmpty() ? null : (String) statutory.get(0).get("aadhaar_enc")));
    }
}
