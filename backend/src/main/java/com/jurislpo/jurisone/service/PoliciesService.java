package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.PoliciesRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Policies page: read-only summaries + the editable attendance policy. Admin only. */
@Service
public class PoliciesService {

    static final List<String> ORDER = List.of("owner", "hrm", "hre", "fm", "fe", "employee");

    public record RolePerms(String id, String key, String name, List<String> perms) {}

    public record LeaveTypeView(String code, String name, double annualQuota, double accrualPerMonth, double carryForwardCap, Double maxStretch,
                                double encashPerYear, int encashAfterMonths, Integer expiryDays) {}

    public record HolidayView(String id, String date, String name, String region) {}

    public record ShiftView(String id, String code, String name, String startLocal, String endLocal, int graceMinutes, String region) {}

    public record AttendancePolicyInput(Double fullDayHours, Double halfDayHours, Integer graceMinutes, Integer lateMarksPerHalfDayLop,
                                        List<Integer> weeklyOffs, Integer absenceJobBufferMinutes, Integer lockDayOfNextMonth, String lockTime,
                                        String trackingStartDate) {}

    private final PoliciesRepository repo;
    private final SettingsService settings;
    private final AuditService audit;
    private final ObjectMapper json;

    public PoliciesService(PoliciesRepository repo, SettingsService settings, AuditService audit, ObjectMapper json) {
        this.repo = repo;
        this.settings = settings;
        this.audit = audit;
        this.json = json;
    }

    public List<RolePerms> accessMatrix() {
        List<RolePerms> roles = new ArrayList<>(repo.findRolesWithPermissions());
        roles.sort((a, b) -> rank(a.key()) - rank(b.key()));
        return roles;
    }

    private static int rank(String key) {
        int i = ORDER.indexOf(key);
        return i < 0 ? 99 : i + 1;
    }

    public List<LeaveTypeView> leaveTypes() {
        return repo.findActiveLeaveTypes();
    }

    public List<HolidayView> holidays(int year) {
        return repo.findHolidaysBetween(Date.valueOf(LocalDate.of(year, 1, 1)), Date.valueOf(LocalDate.of(year + 1, 1, 1)));
    }

    /** Shift templates, with this business's grace period. */
    public List<ShiftView> shifts() {
        int grace = settings.attendance().graceMinutes();
        return repo.findShifts(grace);
    }

    public int lockedRuns() {
        Integer lockRuns = repo.countLockRuns();
        return lockRuns == null ? 0 : lockRuns;
    }

    // ── attendance policy ──

    private static void range(Number number, double min, double max) {
        if (number == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected number, received undefined");
        if (number.doubleValue() < min) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected number to be >=" + formatNumber(min));
        if (number.doubleValue() > max) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=" + formatNumber(max));
    }

    private static String formatNumber(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    static void validate(AttendancePolicyInput input) {
        range(input.fullDayHours(), 1, 14);
        range(input.halfDayHours(), 0.5, 12);
        range(input.graceMinutes(), 0, 120);
        range(input.lateMarksPerHalfDayLop(), 1, 31);
        if (input.weeklyOffs() == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected array, received undefined");
        for (Integer weeklyOff : input.weeklyOffs()) range(weeklyOff, 0, 6);
        if (input.weeklyOffs().size() > 6) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected array to have <=6 items");
        range(input.absenceJobBufferMinutes(), 0, 720);
        range(input.lockDayOfNextMonth(), 1, 28);
        if (input.lockTime() == null || !input.lockTime().matches("^\\d{2}:\\d{2}$")) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid string: must match pattern /^\\d{2}:\\d{2}$/");
        if (input.trackingStartDate() == null || !input.trackingStartDate().matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Invalid string: must match pattern /^\\d{4}-\\d{2}-\\d{2}$/");
        }
        if (!(input.halfDayHours() < input.fullDayHours())) throw new UserException(HttpStatus.BAD_REQUEST, "Half-day hours must be less than full-day hours.");
    }

    @Transactional
    public void updateAttendancePolicy(CurrentUser user, AttendancePolicyInput input, String ipAddress) {
        validate(input);
        ObjectNode before = settings.get("attendance.policy");
        if (!input.trackingStartDate().equals(before.path("trackingStartDate").asText()) && lockedRuns() > 0) {
            throw new UserException("The go-live date can't change once a month has been locked.");
        }
        ObjectNode value = before.deepCopy();
        value.setAll((ObjectNode) json.valueToTree(input));
        repo.upsertAttendancePolicy(value.toString());
        // grace minutes live in the business's policy (shifts are shared templates and are not changed)
        audit.log(user.id(), "policy.attendance_change", "setting", "attendance.policy", before, value, ipAddress);
    }

    public Map<String, Object> attendance() {
        return Map.of("policy", settings.get("attendance.policy"), "shifts", shifts(), "locked", lockedRuns() > 0);
    }
}
