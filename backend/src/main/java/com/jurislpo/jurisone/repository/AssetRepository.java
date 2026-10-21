package com.jurislpo.jurisone.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The asset register and its assignment history (tenant-scoped by row-level security). */
@Repository
public class AssetRepository {

    private static final String ASSET = """
        SELECT a.id::text AS "id", a.tag AS "tag", a.type AS "type", a.make AS "make", a.model AS "model", a.serial AS "serial",
               a.purchase_date::text AS "purchaseDate", a.status AS "status", a.notes AS "notes",
               cur.employee_id::text AS "holderId", e.code AS "holderCode", e.full_name AS "holderName", cur.assigned_on::text AS "heldSince"
        FROM assets a
        LEFT JOIN asset_assignments cur ON cur.asset_id = a.id AND cur.returned_on IS NULL
        LEFT JOIN employees e ON e.id = cur.employee_id""";

    private final JdbcTemplate jdbc;

    public AssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findAll() {
        return jdbc.queryForList(ASSET + " ORDER BY a.tag");
    }

    public List<Map<String, Object>> findById(UUID id) {
        return jdbc.queryForList(ASSET + " WHERE a.id = ?", id);
    }

    public boolean tagTaken(String tag, UUID exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM assets WHERE lower(tag) = lower(?) AND (?::uuid IS NULL OR id <> ?::uuid))",
                Boolean.class, tag, exceptId, exceptId));
    }

    public void insert(UUID id, String tag, String type, String make, String model, String serial, LocalDate purchaseDate, String status, String notes, UUID by) {
        jdbc.update("INSERT INTO assets (id, tag, type, make, model, serial, purchase_date, status, notes, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tag, type, make, model, serial, purchaseDate == null ? null : Date.valueOf(purchaseDate), status, notes, by);
    }

    public void update(UUID id, String tag, String type, String make, String model, String serial, LocalDate purchaseDate, String status, String notes) {
        jdbc.update("UPDATE assets SET tag = ?, type = ?, make = ?, model = ?, serial = ?, purchase_date = ?, status = ?, notes = ?, updated_at = now() WHERE id = ?",
                tag, type, make, model, serial, purchaseDate == null ? null : Date.valueOf(purchaseDate), status, notes, id);
    }

    public void setStatus(UUID id, String status) {
        jdbc.update("UPDATE assets SET status = ?, updated_at = now() WHERE id = ?", status, id);
    }

    public boolean everAssigned(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM asset_assignments WHERE asset_id = ?)", Boolean.class, id));
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM assets WHERE id = ?", id);
    }

    /** Active employees (id, code, name) the manager can assign to. */
    public List<Map<String, Object>> findAssignees() {
        return jdbc.queryForList("SELECT id::text AS \"id\", code AS \"code\", full_name AS \"name\" FROM employees WHERE status <> 'EXITED' ORDER BY full_name");
    }

    public boolean assignable(UUID employeeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM employees WHERE id = ? AND status <> 'EXITED')", Boolean.class, employeeId));
    }

    public void insertAssignment(UUID id, UUID assetId, UUID employeeId, LocalDate assignedOn, UUID by, String notes) {
        jdbc.update("INSERT INTO asset_assignments (id, asset_id, employee_id, assigned_on, assigned_by, assign_notes) VALUES (?, ?, ?, ?, ?, ?)",
                id, assetId, employeeId, Date.valueOf(assignedOn), by, notes);
    }

    /** The open assignment of an asset: id, employeeId, assignedOn; empty when it is not held. */
    public List<Map<String, Object>> findOpenAssignment(UUID assetId) {
        return jdbc.queryForList("SELECT id::text AS \"id\", employee_id::text AS \"employeeId\", assigned_on::text AS \"assignedOn\" FROM asset_assignments "
                + "WHERE asset_id = ? AND returned_on IS NULL", assetId);
    }

    public void closeAssignment(UUID id, LocalDate returnedOn, UUID by, String condition, String notes) {
        jdbc.update("UPDATE asset_assignments SET returned_on = ?, returned_by = ?, return_condition = ?, return_notes = ? WHERE id = ?",
                Date.valueOf(returnedOn), by, condition, notes, id);
    }

    private static final String HISTORY = """
        SELECT s.id::text AS "id", s.asset_id::text AS "assetId", a.tag AS "tag", a.type AS "type", a.make AS "make", a.model AS "model", a.serial AS "serial",
               s.employee_id::text AS "employeeId", e.code AS "employeeCode", e.full_name AS "employeeName",
               s.assigned_on::text AS "assignedOn", s.assign_notes AS "assignNotes", ab.full_name AS "assignedBy",
               s.returned_on::text AS "returnedOn", s.return_condition AS "returnCondition", s.return_notes AS "returnNotes", rb.full_name AS "returnedBy"
        FROM asset_assignments s
        JOIN assets a ON a.id = s.asset_id
        JOIN employees e ON e.id = s.employee_id
        LEFT JOIN employees ab ON ab.id = s.assigned_by
        LEFT JOIN employees rb ON rb.id = s.returned_by""";

    /** Every assignment of one asset, newest first. */
    public List<Map<String, Object>> findHistory(UUID assetId) {
        return jdbc.queryForList(HISTORY + " WHERE s.asset_id = ? ORDER BY s.assigned_on DESC, s.created_at DESC", assetId);
    }

    /** Every assignment of one employee, newest first (open ones have no returnedOn). */
    public List<Map<String, Object>> findForEmployee(UUID employeeId) {
        return jdbc.queryForList(HISTORY + " WHERE s.employee_id = ? ORDER BY s.assigned_on DESC, s.created_at DESC", employeeId);
    }
}
