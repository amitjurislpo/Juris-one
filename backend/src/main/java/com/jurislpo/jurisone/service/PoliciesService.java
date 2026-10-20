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
        Integer n = repo.countLockRuns();
        return n == null ? 0 : n;
    }

    // ── attendance policy ──

    private static void range(Number v, double min, double max) {
        if (v == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected number, received undefined");
        if (v.doubleValue() < min) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected number to be >=" + fmt(min));
        if (v.doubleValue() > max) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=" + fmt(max));
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    static void validate(AttendancePolicyInput f) {
        range(f.fullDayHours(), 1, 14);
        range(f.halfDayHours(), 0.5, 12);
        range(f.graceMinutes(), 0, 120);
        range(f.lateMarksPerHalfDayLop(), 1, 31);
        if (f.weeklyOffs() == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected array, received undefined");
        for (Integer d : f.weeklyOffs()) range(d, 0, 6);
        if (f.weeklyOffs().size() > 6) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected array to have <=6 items");
        range(f.absenceJobBufferMinutes(), 0, 720);
        range(f.lockDayOfNextMonth(), 1, 28);
        if (f.lockTime() == null || !f.lockTime().matches("^\\d{2}:\\d{2}$")) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid string: must match pattern /^\\d{2}:\\d{2}$/");
        if (f.trackingStartDate() == null || !f.trackingStartDate().matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Invalid string: must match pattern /^\\d{4}-\\d{2}-\\d{2}$/");
        }
        if (!(f.halfDayHours() < f.fullDayHours())) throw new UserException(HttpStatus.BAD_REQUEST, "Half-day hours must be less than full-day hours.");
    }

    @Transactional
    public void updateAttendancePolicy(CurrentUser u, AttendancePolicyInput f, String ip) {
        validate(f);
        ObjectNode before = settings.get("attendance.policy");
        if (!f.trackingStartDate().equals(before.path("trackingStartDate").asText()) && lockedRuns() > 0) {
            throw new UserException("The go-live date can't change once a month has been locked.");
        }
        ObjectNode value = before.deepCopy();
        value.setAll((ObjectNode) json.valueToTree(f));
        repo.upsertAttendancePolicy(value.toString());
        // grace minutes live in the business's policy (shifts are shared templates and are not changed)
        audit.log(u.id(), "policy.attendance_change", "setting", "attendance.policy", before, value, ip);
    }

    public Map<String, Object> attendance() {
        return Map.of("policy", settings.get("attendance.policy"), "shifts", shifts(), "locked", lockedRuns() > 0);
    }
}
