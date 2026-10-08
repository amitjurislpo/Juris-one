package com.jurislpo.jurisone.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jurislpo.jurisone.common.UserException;
import com.jurislpo.jurisone.security.CurrentUser;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Policies page (src/app/(app)/settings): read-only summaries + the editable attendance policy. Admin only. */
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

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final AuditService audit;
    private final ObjectMapper json;

    public PoliciesService(JdbcTemplate jdbc, SettingsService settings, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.audit = audit;
        this.json = json;
    }

    public List<RolePerms> accessMatrix() {
        List<RolePerms> roles = new ArrayList<>(jdbc.query("SELECT id, key, name FROM roles ORDER BY created_at ASC", (rs, i) -> {
            String id = rs.getString(1);
            return new RolePerms(id, rs.getString(2), rs.getString(3),
                    jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?::uuid", String.class, id));
        }));
        roles.sort((a, b) -> rank(a.key()) - rank(b.key()));
        return roles;
    }

    private static int rank(String key) {
        int i = ORDER.indexOf(key);
        return i < 0 ? 99 : i + 1;
    }

    public List<LeaveTypeView> leaveTypes() {
        return jdbc.query("SELECT * FROM leave_types WHERE active ORDER BY sort_order ASC", (rs, i) -> new LeaveTypeView(
                rs.getString("code"), rs.getString("name"), rs.getBigDecimal("annual_quota").doubleValue(), rs.getBigDecimal("accrual_per_month").doubleValue(),
                rs.getBigDecimal("carry_forward_cap").doubleValue(), dbl(rs.getBigDecimal("max_stretch")), rs.getBigDecimal("encash_per_year").doubleValue(),
                rs.getInt("encash_after_months"), (Integer) rs.getObject("expiry_days")));
    }

    private static Double dbl(BigDecimal b) {
        return b == null ? null : b.doubleValue();
    }

    public List<HolidayView> holidays(int year) {
        return jdbc.query("SELECT id, date, name, region::text AS region FROM holidays WHERE date >= ? AND date < ? ORDER BY date ASC",
                (rs, i) -> new HolidayView(rs.getString("id"), rs.getDate("date").toLocalDate().toString(), rs.getString("name"), rs.getString("region")),
                Date.valueOf(LocalDate.of(year, 1, 1)), Date.valueOf(LocalDate.of(year + 1, 1, 1)));
    }

    /** Shift templates, with this business's grace period. */
    public List<ShiftView> shifts() {
        int grace = settings.attendance().graceMinutes();
        return jdbc.query("SELECT id, code, name, start_local, end_local, region::text AS region FROM shifts ORDER BY code ASC",
                (rs, i) -> new ShiftView(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("start_local"), rs.getString("end_local"),
                        grace, rs.getString("region")));
    }

    public int lockedRuns() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'attendance.lock'", Integer.class);
        return n == null ? 0 : n;
    }

    // ── attendance policy (zod schema of src/app/(app)/settings/actions.ts) ──

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
        jdbc.update("INSERT INTO settings (key, value, updated_at) VALUES ('attendance.policy', ?::jsonb, now()) "
                + "ON CONFLICT (business_id, key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()", value.toString());
        // grace minutes live in the business's policy (shifts are shared templates and are not changed)
        audit.log(u.id(), "policy.attendance_change", "setting", "attendance.policy", before, value, ip);
    }

    public Map<String, Object> attendance() {
        return Map.of("policy", settings.get("attendance.policy"), "shifts", shifts(), "locked", lockedRuns() > 0);
    }
}
