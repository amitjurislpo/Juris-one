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
        return jdbc.query("SELECT id, key, name FROM roles ORDER BY created_at ASC", (resultSet, i) -> {
            String id = resultSet.getString(1);
            return new RolePerms(id, resultSet.getString(2), resultSet.getString(3),
                    jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?::uuid", String.class, id));
        });
    }

    /** Active leave types in display order. */
    public List<LeaveTypeView> findActiveLeaveTypes() {
        return jdbc.query("SELECT * FROM leave_types WHERE active ORDER BY sort_order ASC", (resultSet, i) -> new LeaveTypeView(
                resultSet.getString("code"), resultSet.getString("name"), resultSet.getBigDecimal("annual_quota").doubleValue(), resultSet.getBigDecimal("accrual_per_month").doubleValue(),
                resultSet.getBigDecimal("carry_forward_cap").doubleValue(), toDouble(resultSet.getBigDecimal("max_stretch")), resultSet.getBigDecimal("encash_per_year").doubleValue(),
                resultSet.getInt("encash_after_months"), (Integer) resultSet.getObject("expiry_days"), null));
    }

    /** Code, name and own carry-forward cap of a leave type that has a year-end choice. */
    public record YearEndType(String code, String name, double carryForwardCap) {}

    /** Active, balance-tracked leave types other than comp-off (comp-off expires on its own), in display order. */
    public List<YearEndType> findYearEndTypes() {
        return jdbc.query("SELECT code, name, carry_forward_cap FROM leave_types WHERE active AND needs_balance AND code <> 'CO' ORDER BY sort_order ASC",
                (resultSet, i) -> new YearEndType(resultSet.getString(1), resultSet.getString(2), resultSet.getBigDecimal(3).doubleValue()));
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /** Holidays with from &lt;= date &lt; until, by date. */
    public List<HolidayView> findHolidaysBetween(Date from, Date until) {
        return jdbc.query("SELECT id, date, name, region::text AS region FROM holidays WHERE date >= ? AND date < ? ORDER BY date ASC",
                (resultSet, i) -> new HolidayView(resultSet.getString("id"), resultSet.getDate("date").toLocalDate().toString(), resultSet.getString("name"), resultSet.getString("region")),
                from, until);
    }

    /** Shift templates by code, each shown with the given grace period. */
    public List<ShiftView> findShifts(int grace) {
        return jdbc.query("SELECT id, code, name, start_local, end_local, region::text AS region FROM shifts ORDER BY code ASC",
                (resultSet, i) -> new ShiftView(resultSet.getString("id"), resultSet.getString("code"), resultSet.getString("name"), resultSet.getString("start_local"), resultSet.getString("end_local"),
                        grace, resultSet.getString("region")));
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
