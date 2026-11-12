package com.jurislpo.jurisone.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shifts (platform defaults plus the business's own) and the shift assignments of the roster. Row-level security keeps every query inside the business. */
@Repository
public class ShiftRosterRepository {

    /** {@code isCustom}: a shift of the business (editable); the platform defaults are not. */
    public record ShiftRow(UUID id, String code, String name, String startLocal, String endLocal, int graceMinutes, String region, boolean crossesMidnight, boolean isCustom) {}

    public record AssignmentRow(UUID id, UUID employeeId, UUID shiftId, LocalDate fromDate, LocalDate toDate) {}

    /** An assignment with the name of its employee, for the audit entry and the conflict message. */
    public record AssignmentDetail(UUID id, UUID employeeId, String employeeCode, String employeeName, UUID shiftId, LocalDate fromDate, LocalDate toDate) {}

    private static final String SHIFT_COLUMNS = "id, code, name, start_local, end_local, grace_minutes, region::text, crosses_midnight, business_id IS NOT NULL";

    private final JdbcTemplate jdbc;

    public ShiftRosterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ShiftRow shift(java.sql.ResultSet resultSet, int i) throws java.sql.SQLException {
        return new ShiftRow((UUID) resultSet.getObject(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet.getString(5), resultSet.getInt(6),
                resultSet.getString(7), resultSet.getBoolean(8), resultSet.getBoolean(9));
    }

    /** The platform defaults first, then the business's own, each by start time. */
    public List<ShiftRow> shifts() {
        return jdbc.query("SELECT " + SHIFT_COLUMNS + " FROM shifts ORDER BY (business_id IS NOT NULL), start_local, name", ShiftRosterRepository::shift);
    }

    public ShiftRow findShift(UUID id) {
        return jdbc.query("SELECT " + SHIFT_COLUMNS + " FROM shifts WHERE id = ?", ShiftRosterRepository::shift, id).stream().findFirst().orElse(null);
    }

    public void insertShift(UUID id, String code, String name, String startLocal, String endLocal, String region, boolean crossesMidnight) {
        jdbc.update("""
            INSERT INTO shifts (id, code, name, start_local, end_local, region, crosses_midnight) VALUES (?, ?, ?, ?, ?, ?::"Region", ?)""",
                id, code, name, startLocal, endLocal, region, crossesMidnight);
    }

    /** Only the business's own rows can change: row-level security refuses the platform defaults and other businesses' shifts. */
    public int updateShift(UUID id, String name, String startLocal, String endLocal, boolean crossesMidnight) {
        return jdbc.update("UPDATE shifts SET name = ?, start_local = ?, end_local = ?, crosses_midnight = ? WHERE id = ? AND business_id IS NOT NULL",
                name, startLocal, endLocal, crossesMidnight, id);
    }

    /** Locks the tracked employees among {@code employeeIds} (so two assignments for one person cannot race) and returns the ones found. */
    public List<UUID> lockTrackedEmployees(UUID[] employeeIds) {
        return jdbc.query("SELECT e.id FROM employees e WHERE e.id = ANY (?) AND " + AttendanceRepository.TRACKED + " ORDER BY e.id FOR UPDATE OF e",
                (resultSet, i) -> (UUID) resultSet.getObject(1), (Object) employeeIds);
    }

    /** Assignments of these employees that overlap [from, to]. */
    public List<AssignmentRow> assignments(UUID[] employeeIds, LocalDate from, LocalDate to) {
        return jdbc.query("""
            SELECT id, employee_id, shift_id, from_date, to_date FROM shift_assignments
            WHERE employee_id = ANY (?) AND from_date <= ? AND (to_date IS NULL OR to_date >= ?) ORDER BY from_date""",
                (resultSet, i) -> new AssignmentRow((UUID) resultSet.getObject(1), (UUID) resultSet.getObject(2), (UUID) resultSet.getObject(3), resultSet.getDate(4).toLocalDate(),
                        resultSet.getDate(5) == null ? null : resultSet.getDate(5).toLocalDate()),
                employeeIds, Date.valueOf(to), Date.valueOf(from));
    }

    /** Names of the employees (among {@code employeeIds}) who already have an assignment overlapping [from, to]; to = null is open ended. */
    public List<String> employeesWithOverlap(UUID[] employeeIds, LocalDate from, LocalDate to) {
        return jdbc.query("""
            SELECT DISTINCT e.full_name FROM shift_assignments a JOIN employees e ON e.id = a.employee_id
            WHERE a.employee_id = ANY (?) AND a.from_date <= COALESCE(?::date, DATE '9999-12-31') AND (a.to_date IS NULL OR a.to_date >= ?) ORDER BY e.full_name""",
                (resultSet, i) -> resultSet.getString(1), employeeIds, to == null ? null : Date.valueOf(to), Date.valueOf(from));
    }

    public void insertAssignment(UUID id, UUID employeeId, UUID shiftId, LocalDate from, LocalDate to, UUID createdBy) {
        jdbc.update("INSERT INTO shift_assignments (id, employee_id, shift_id, from_date, to_date, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                id, employeeId, shiftId, Date.valueOf(from), to == null ? null : Date.valueOf(to), createdBy);
    }

    public AssignmentDetail findAssignment(UUID id) {
        return jdbc.query("""
            SELECT a.id, a.employee_id, e.code, e.full_name, a.shift_id, a.from_date, a.to_date FROM shift_assignments a JOIN employees e ON e.id = a.employee_id WHERE a.id = ?""",
                (resultSet, i) -> new AssignmentDetail((UUID) resultSet.getObject(1), (UUID) resultSet.getObject(2), resultSet.getString(3), resultSet.getString(4),
                        (UUID) resultSet.getObject(5), resultSet.getDate(6).toLocalDate(), resultSet.getDate(7) == null ? null : resultSet.getDate(7).toLocalDate()), id)
                .stream().findFirst().orElse(null);
    }

    public int deleteAssignment(UUID id) {
        return jdbc.update("DELETE FROM shift_assignments WHERE id = ?", id);
    }
}
