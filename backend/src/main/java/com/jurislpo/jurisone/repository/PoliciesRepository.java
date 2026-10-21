package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.service.PoliciesService.HolidayView;
import com.jurislpo.jurisone.service.PoliciesService.LeaveTypeView;
import com.jurislpo.jurisone.service.PoliciesService.RolePerms;
import com.jurislpo.jurisone.service.PoliciesService.ShiftView;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads behind the Policies page and the saved attendance policy. */
@Repository
public class PoliciesRepository {

    private final JdbcTemplate jdbc;

    public PoliciesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every role with its permission keys, by creation. */
    public List<RolePerms> findRolesWithPermissions() {
        return jdbc.query("SELECT id, key, name FROM roles ORDER BY created_at ASC", (rs, i) -> {
            String id = rs.getString(1);
            return new RolePerms(id, rs.getString(2), rs.getString(3),
                    jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?::uuid", String.class, id));
        });
    }

    /** Active leave types in display order. */
    public List<LeaveTypeView> findActiveLeaveTypes() {
        return jdbc.query("SELECT * FROM leave_types WHERE active ORDER BY sort_order ASC", (rs, i) -> new LeaveTypeView(
                rs.getString("code"), rs.getString("name"), rs.getBigDecimal("annual_quota").doubleValue(), rs.getBigDecimal("accrual_per_month").doubleValue(),
                rs.getBigDecimal("carry_forward_cap").doubleValue(), dbl(rs.getBigDecimal("max_stretch")), rs.getBigDecimal("encash_per_year").doubleValue(),
                rs.getInt("encash_after_months"), (Integer) rs.getObject("expiry_days")));
    }

    private static Double dbl(BigDecimal b) {
        return b == null ? null : b.doubleValue();
    }

    /** Holidays with from &lt;= date &lt; until, by date. */
    public List<HolidayView> findHolidaysBetween(Date from, Date until) {
        return jdbc.query("SELECT id, date, name, region::text AS region FROM holidays WHERE date >= ? AND date < ? ORDER BY date ASC",
                (rs, i) -> new HolidayView(rs.getString("id"), rs.getDate("date").toLocalDate().toString(), rs.getString("name"), rs.getString("region")),
                from, until);
    }

    /** Shift templates by code, each shown with the given grace period. */
    public List<ShiftView> findShifts(int grace) {
        return jdbc.query("SELECT id, code, name, start_local, end_local, region::text AS region FROM shifts ORDER BY code ASC",
                (rs, i) -> new ShiftView(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("start_local"), rs.getString("end_local"),
                        grace, rs.getString("region")));
    }

    /** How many attendance-lock job runs there have been. */
    public Integer countLockRuns() {
        return jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'attendance.lock'", Integer.class);
    }

    /** Saves the business's attendance policy JSON. */
    public void upsertAttendancePolicy(String valueJson) {
        jdbc.update("INSERT INTO settings (key, value, updated_at) VALUES ('attendance.policy', ?::jsonb, now()) "
                + "ON CONFLICT (business_id, key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()", valueJson);
    }
}
