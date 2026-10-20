package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.service.AuditViewService.Option;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for {@code AuditViewService}. */
@Repository
public class AuditViewRepository {

    public record LogRow(String id, Instant at, String actor, String actorCode, String action, String entity, String entityId, String ip, String before,
                         String after) {}

    private final JdbcTemplate jdbc;

    public AuditViewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One page of audit entries; {@code fromWhere} is the FROM clause plus the filters, {@code args} ends with OFFSET and LIMIT. */
    public List<LogRow> findPage(String fromWhere, String order, Object[] args) {
        return jdbc.query("SELECT a.id::text, a.at, e.full_name, e.code, a.action, a.entity, a.entity_id, a.ip, a.before::text, a.after::text" + fromWhere
                + " ORDER BY " + order + " OFFSET ? LIMIT ?",
                (rs, i) -> new LogRow(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)), args);
    }

    public long count(String fromWhere, Object[] args) {
        return jdbc.queryForObject("SELECT count(*)" + fromWhere, Long.class, args);
    }

    /** Employees who appear as an actor in the log, for the filter. */
    public List<Option> actorOptions() {
        return jdbc.query("SELECT e.id::text, e.full_name, e.code FROM employees e WHERE EXISTS (SELECT 1 FROM audit_log a WHERE a.actor_id = e.id) ORDER BY e.full_name ASC",
                (rs, i) -> new Option(rs.getString(1), rs.getString(2) + " (" + rs.getString(3) + ")"));
    }

    /** "Name (code)" by employee id (text). */
    public Map<String, String> employeeLabels(String[] empIds) {
        Map<String, String> subjects = new LinkedHashMap<>();
        jdbc.query("SELECT id::text, full_name, code FROM employees WHERE id::text = ANY (?)", rs -> {
            subjects.put(rs.getString(1), rs.getString(2) + " (" + rs.getString(3) + ")");
        }, (Object) empIds);
        return subjects;
    }
}
