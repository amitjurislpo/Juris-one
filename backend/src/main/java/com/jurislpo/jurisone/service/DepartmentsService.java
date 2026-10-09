package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Departments (REQUIREMENTS §5.1; src/app/(app)/departments). dept.manage. */
@Service
public class DepartmentsService {

    static final List<String> SORTS = List.of("name", "head", "n", "by", "on");

    public record Row(String id, String name, String note, String head, int n, String by, String on) {}

    public record Person(String id, String name, String title) {}

    public record Page(List<Row> rows, List<Person> heads) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public DepartmentsService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Page page(CurrentUser u, Map<String, String> sp) {
        ListParams ls = ListParams.of(sp, SORTS, "name");
        List<Row> all = jdbc.query("""
            SELECT d.id, d.name, d.description, h.full_name AS head, c.full_name AS by, d.created_at,
                   (SELECT count(*) FROM employees e WHERE e.department_id = d.id AND e.status <> 'EXITED') AS n
            FROM departments d LEFT JOIN employees h ON h.id = d.head_employee_id LEFT JOIN employees c ON c.id = d.created_by
            WHERE d.archived_at IS NULL""",
                (rs, i) -> new Row(rs.getString("id"), rs.getString("name"), rs.getString("description") == null ? "" : rs.getString("description"),
                        rs.getString("head") == null ? "" : rs.getString("head"), rs.getInt("n"), rs.getString("by") == null ? "—" : rs.getString("by"),
                        Format.isoInZone(rs.getTimestamp("created_at").toInstant(), u.timeZone()).toString()));
        String q = ls.q().toLowerCase();
        List<Row> rows = new ArrayList<>(all.stream()
                .filter(r -> q.isEmpty() || (r.name() + " " + r.head() + " " + r.by() + " " + r.note()).toLowerCase().contains(q)).toList());
        Map<String, Function<Row, ? extends Comparable>> keys = Map.of("name", Row::name, "head", Row::head, "n", Row::n, "by", Row::by, "on", Row::on);
        rows.sort(ls.comparator(keys));
        List<Person> heads = jdbc.query("SELECT id, full_name, designation FROM employees WHERE status <> 'EXITED' ORDER BY full_name ASC",
                (rs, i) -> new Person(rs.getString(1), rs.getString(2), rs.getString(3)));
        return new Page(rows, heads);
    }

    @Transactional
    public String create(CurrentUser u, String nameRaw, String headIdRaw, String descRaw, String ip) {
        String name = Check.trimmed(nameRaw, 2, "Department name is required.", 80, "Keep the name under 80 characters.");
        UUID headId = headIdRaw == null || headIdRaw.isEmpty() ? null : Check.uuid(headIdRaw);
        String description = descRaw == null ? null : descRaw.trim();
        if (description != null && description.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the description under 300 characters.");

        List<Map<String, Object>> existing = jdbc.queryForList("SELECT id, archived_at FROM departments WHERE lower(name) = lower(?) LIMIT 1", name);
        if (!existing.isEmpty() && existing.get(0).get("archived_at") == null) throw new UserException("This department already exists.");
        if (headId != null) {
            List<String> st = jdbc.queryForList("SELECT status::text FROM employees WHERE id = ?", String.class, headId);
            if (st.isEmpty() || "EXITED".equals(st.get(0))) throw new UserException("Pick a current employee as head.");
        }
        String desc = description == null || description.isEmpty() ? null : description;
        UUID id;
        boolean restore = !existing.isEmpty();
        if (restore) {
            // A previously archived department with the same name is brought back rather than duplicated.
            id = (UUID) existing.get(0).get("id");
            jdbc.update("UPDATE departments SET name = ?, archived_at = NULL, head_employee_id = ?, description = ?, created_by = ?, created_at = ? WHERE id = ?",
                    name, headId, desc, u.id(), new Timestamp(System.currentTimeMillis()), id);
        } else {
            id = UUID.randomUUID();
            jdbc.update("INSERT INTO departments (id, name, head_employee_id, description, created_by, created_at) VALUES (?, ?, ?, ?, ?, now())",
                    id, name, headId, desc, u.id());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("headEmployeeId", headId == null ? null : headId.toString());
        if (description != null) after.put("description", description);
        audit.log(u.id(), restore ? "department.restore" : "department.create", "department", id.toString(), null, after, ip);
        return "Department “" + name + "” created";
    }

    @Transactional
    public String delete(CurrentUser u, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> ds = jdbc.queryForList("SELECT name, description, archived_at FROM departments WHERE id = ?", id);
        if (ds.isEmpty() || ds.get(0).get("archived_at") != null) throw new UserException("That department no longer exists.");
        Map<String, Object> d = ds.get(0);
        Integer total = jdbc.queryForObject("SELECT count(*) FROM employees WHERE department_id = ?", Integer.class, id);
        Integer current = jdbc.queryForObject("SELECT count(*) FROM employees WHERE department_id = ? AND status <> 'EXITED'", Integer.class, id);
        if (current != null && current > 0) {
            throw new UserException(d.get("name") + " still has " + current + " current employee" + (current == 1 ? "" : "s") + ". Move them first.");
        }
        // Past employees still point at it, so it is archived to keep their records intact.
        boolean archive = total != null && total > 0;
        if (archive) jdbc.update("UPDATE departments SET archived_at = now(), head_employee_id = NULL WHERE id = ?", id);
        else jdbc.update("DELETE FROM departments WHERE id = ?", id);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", d.get("name"));
        before.put("description", d.get("description"));
        audit.log(u.id(), "department.delete", "department", id.toString(), before, archive ? Map.of("archived", true) : null, ip);
        return "Department “" + d.get("name") + "” removed";
    }
}
