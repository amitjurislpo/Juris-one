package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jurislpo.jurisone.entity.Setting;
import com.jurislpo.jurisone.repository.SettingsRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Policies (src/lib/settings.ts + settings-defaults.ts): the DB row for a key is shallow-merged over the code default.
 */
@Service
public class SettingsService {

    /** Defaults, verbatim from src/lib/settings-defaults.ts. */
    public static final Map<String, String> DEFAULTS = Map.of(
            "attendance.policy", """
                {"fullDayHours":8,"halfDayHours":4,"graceMinutes":15,"lateMarksPerHalfDayLop":3,"weeklyOffs":[0,6],
                 "absenceJobBufferMinutes":120,"lockDayOfNextMonth":1,"lockTime":"10:00","shiftTargetHours":9,"trackingStartDate":"2026-04-01"}""",
            "employee.policy", """
                {"probationMonths":6}""",
            "auth.policy", """
                {"maxFailedAttempts":5,"lockMinutes":15,"ipWindowMinutes":15,"ipMaxAttempts":30,"inviteTtlHours":72,"resetTtlMinutes":60}""",
            "payroll.policy", """
                {"pfRate":0.12,"pfWageCeiling":15000,"professionalTax":{"state":"Default","monthly":200}}""",
            "tax.policy", """
                {"fy":"2026-27","regimeChangeUntil":"2027-01-31","proofWindowFrom":"2026-04-01","proofWindowUntil":"2027-01-31","maxProofMb":5}""",
            "company", """
                {"name":"Juris Consultants","address":"[Registered office address]"}""",
            "onboarding.policy", """
                {"requiredDocs":["ID_PROOF","ADDRESS_PROOF","PAN","AADHAAR","SALARY_SLIP"]}""",
            "exit.policy", """
                {"noticeDays":30,"encashableLeaveTypes":["EL"],"gratuityEnabled":false,"gratuityMinYears":5,"gratuityCapRupees":2000000}""");

    public static final List<String> KEYS = List.of("attendance.policy", "employee.policy", "auth.policy", "payroll.policy", "tax.policy", "company",
            "onboarding.policy", "exit.policy");

    public record AuthPolicy(int maxFailedAttempts, int lockMinutes, int ipWindowMinutes, int ipMaxAttempts, int inviteTtlHours, int resetTtlMinutes) {}

    public record AttendancePolicy(double fullDayHours, double halfDayHours, int graceMinutes, int lateMarksPerHalfDayLop, List<Integer> weeklyOffs,
                                   int absenceJobBufferMinutes, int lockDayOfNextMonth, String lockTime, double shiftTargetHours, String trackingStartDate) {}

    public record EmployeePolicy(int probationMonths) {}

    public record ProfessionalTax(String state, double monthly) {}

    public record PayrollPolicy(double pfRate, double pfWageCeiling, ProfessionalTax professionalTax) {}

    public record TaxPolicy(String fy, String regimeChangeUntil, String proofWindowFrom, String proofWindowUntil, double maxProofMb) {}

    /** {@code tan} / {@code pan}: the deductor's tax numbers for Form 16 (optional; absent until Finance sets them). */
    public record Company(String name, String address, String tan, String pan) {}

    /** Documents a new onboarding asks for unless HR changes the list for that candidate. */
    public record OnboardingPolicy(List<String> requiredDocs) {}

    /**
     * Exits and full & final settlement: the notice every leaver owes (days), the leave types whose remaining balance is
     * paid out, and the gratuity switch (off by default), its minimum service in years and its ceiling in rupees.
     */
    public record ExitPolicy(int noticeDays, List<String> encashableLeaveTypes, boolean gratuityEnabled, int gratuityMinYears, double gratuityCapRupees) {}

    private final SettingsRepository repo;
    private final ObjectMapper json;

    public SettingsService(SettingsRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * The merged JSON value for a key in the current business (defaults + the business's saved value). Outside a
     * business (sign-in, the super admin) the platform defaults apply.
     */
    public ObjectNode get(String key) {
        String d = DEFAULTS.get(key);
        if (d == null) throw new IllegalArgumentException("Unknown setting " + key);
        try {
            ObjectNode base = (ObjectNode) json.readTree(d);
            if (com.jurislpo.jurisone.tenancy.TenantContext.current() == null) return base;
            Setting row = repo.find(key); // row-level security: only this business's row is visible
            if (row != null && row.getValue() instanceof ObjectNode o) base.setAll(o);
            return base;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public <T> T get(String key, Class<T> type) {
        return json.convertValue(get(key), type);
    }

    public AuthPolicy auth() { return get("auth.policy", AuthPolicy.class); }
    public AttendancePolicy attendance() { return get("attendance.policy", AttendancePolicy.class); }
    public EmployeePolicy employee() { return get("employee.policy", EmployeePolicy.class); }
    public PayrollPolicy payroll() { return get("payroll.policy", PayrollPolicy.class); }
    public TaxPolicy tax() { return get("tax.policy", TaxPolicy.class); }
    public Company company() { return get("company", Company.class); }
    public OnboardingPolicy onboarding() { return get("onboarding.policy", OnboardingPolicy.class); }
    public ExitPolicy exit() { return get("exit.policy", ExitPolicy.class); }

    /** Saves the current business's value for a key (replaces it). */
    public void save(String key, JsonNode value) {
        if (!DEFAULTS.containsKey(key)) throw new IllegalArgumentException("Unknown setting " + key);
        repo.upsert(key, value.toString());
    }

    public JsonNode defaults(String key) {
        try {
            return json.readTree(DEFAULTS.get(key));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
