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
 * Policies: the DB row for a key is shallow-merged over the code default.
 */
@Service
public class SettingsService {

    /** Defaults. */
    public static final Map<String, String> DEFAULTS = Map.of(
            "attendance.policy", """
                {"fullDayHours":8,"halfDayHours":4,"graceMinutes":15,"lateMarksPerHalfDayLop":3,"weeklyOffs":[0,6],
                 "absenceJobBufferMinutes":120,"lockDayOfNextMonth":1,"lockTime":"10:00","shiftTargetHours":9,"trackingStartDate":"2026-04-01"}""",
            "attendance.regularization", """
                {"backDateLimitDays":30,"monthlyLimit":30,"attachmentMandatory":false,"autoApprove":false,"onDutyMonthlyLimit":31,"remoteWorkMonthlyLimit":31}""",
            "employee.policy", """
                {"probationMonths":6}""",
            "auth.policy", """
                {"maxFailedAttempts":5,"lockMinutes":15,"ipWindowMinutes":15,"ipMaxAttempts":30}""",
            "payroll.policy", """
                {"pfRate":0.12,"pfWageCeiling":15000,"professionalTax":{"state":"Default","monthly":200}}""",
            "tax.policy", """
                {"financialYear":"2026-27","regimeChangeUntil":"2027-01-31","proofWindowFrom":"2026-04-01","proofWindowUntil":"2027-01-31","maxProofMb":5}""",
            "leave.policy", """
                {"yearStartMonth":1,"yearStartDay":1,"yearEnd":{}}""",
            "company", """
                {"name":"Juris Consultants","address":"[Registered office address]"}""");

    public static final List<String> KEYS = List.of("attendance.policy", "attendance.regularization", "employee.policy", "auth.policy", "payroll.policy", "tax.policy", "leave.policy", "company");

    public record AuthPolicy(int maxFailedAttempts, int lockMinutes, int ipWindowMinutes, int ipMaxAttempts) {}

    public record AttendancePolicy(double fullDayHours, double halfDayHours, int graceMinutes, int lateMarksPerHalfDayLop, List<Integer> weeklyOffs,
                                   int absenceJobBufferMinutes, int lockDayOfNextMonth, String lockTime, double shiftTargetHours, String trackingStartDate) {}

    /**
     * Rules for attendance requests: how far back, how many regularizations a month, and whether a file must be attached. {@code autoApprove} (off by
     * default): a request that Admin / HR raise for an employee (regularization, On Duty or Remote Work) is approved at once. {@code onDutyMonthlyLimit} and
     * {@code remoteWorkMonthlyLimit}: the most On Duty days / remote work days (a half day is 0.5) one employee can have pending or approved in a calendar
     * month; 31 (the default) means no practical limit. Same settings row as the regularization rules; absent in an older saved value = the default.
     */
    public record RegularizationPolicy(int backDateLimitDays, int monthlyLimit, boolean attachmentMandatory, boolean autoApprove, Integer onDutyMonthlyLimit, Integer remoteWorkMonthlyLimit) {}

    public record EmployeePolicy(int probationMonths) {}

    public record ProfessionalTax(String state, double monthly) {}

    public record PayrollPolicy(double pfRate, double pfWageCeiling, ProfessionalTax professionalTax) {}

    public record TaxPolicy(String financialYear, String regimeChangeUntil, String proofWindowFrom, String proofWindowUntil, double maxProofMb) {}

    /**
     * The leave year starts every year on {@code yearStartMonth}/{@code yearStartDay} (default 1 January).
     * {@code yearStartChangedOn}: the day the start was last changed (null = never): the year-end job ignores year ends up to that day.
     * {@code yearEnd}: per leave type code, what the business chose for the unused balance; a type without an entry follows the type's own default.
     */
    public record LeavePolicy(int yearStartMonth, int yearStartDay, String yearStartChangedOn, Map<String, YearEndRule> yearEnd) {}

    /** {@code action}: COLLAPSE, CARRY_ALL or CARRY_CAPPED; {@code carryForwardCap}: days, only for CARRY_CAPPED. */
    public record YearEndRule(String action, Double carryForwardCap) {}

    /** {@code tan} / {@code pan}: the deductor's tax numbers for Form 16 (optional; absent until Finance sets them). */
    public record Company(String name, String address, String tan, String pan) {}

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
        String defaults = DEFAULTS.get(key);
        if (defaults == null) throw new IllegalArgumentException("Unknown setting " + key);
        try {
            ObjectNode base = (ObjectNode) json.readTree(defaults);
            if (com.jurislpo.jurisone.tenancy.TenantContext.current() == null) return base;
            Setting row = repo.find(key); // row-level security: only this business's row is visible
            if (row != null && row.getValue() instanceof ObjectNode node) base.setAll(node);
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
    public RegularizationPolicy regularization() { return get("attendance.regularization", RegularizationPolicy.class); }
    public EmployeePolicy employee() { return get("employee.policy", EmployeePolicy.class); }
    public PayrollPolicy payroll() { return get("payroll.policy", PayrollPolicy.class); }
    public TaxPolicy tax() { return get("tax.policy", TaxPolicy.class); }
    public LeavePolicy leave() { return get("leave.policy", LeavePolicy.class); }
    public Company company() { return get("company", Company.class); }

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
