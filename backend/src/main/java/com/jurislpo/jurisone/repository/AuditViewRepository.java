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

    public record LogRow(String id, Instant occurredAt, String actor, String actorCode, String action, String entity, String entityId, String ipAddress, String before,
                         String after, String method, String path, Integer status, Integer durationMs, String business) {}

    private final JdbcTemplate jdbc;

    public AuditViewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One page of audit entries; {@code fromWhere} is the FROM clause plus the filters, {@code args} ends with OFFSET and LIMIT. */
    public List<LogRow> findPage(String fromWhere, String order, Object[] args) {
        // the business name is only joined (alias b) on the super admin's query
        String business = fromWhere.contains(" businesses b ") ? "b.name" : "NULL::text";
        return jdbc.query("SELECT a.id::text, a.at, e.full_name, e.code, a.action, a.entity, a.entity_id, a.ip, a.before::text, a.after::text, a.method, a.path, a.status, a.duration_ms, "
                + business + fromWhere + " ORDER BY " + order + ", a.id OFFSET ? LIMIT ?",
                (resultSet, i) -> new LogRow(resultSet.getString(1), resultSet.getTimestamp(2).toInstant(), resultSet.getString(3), resultSet.getString(4), resultSet.getString(5), resultSet.getString(6),
                        resultSet.getString(7), resultSet.getString(8), resultSet.getString(9), resultSet.getString(10), resultSet.getString(11), resultSet.getString(12),
                        (Integer) resultSet.getObject(13), (Integer) resultSet.getObject(14), resultSet.getString(15)), args);
    }

    public long count(String fromWhere, Object[] args) {
        return jdbc.queryForObject("SELECT count(*)" + fromWhere, Long.class, args);
    }

    /** Businesses, for the super admin's filter. */
    public List<Option> businessOptions() {
        return jdbc.query("SELECT id::text, name FROM businesses ORDER BY name ASC", (resultSet, i) -> new Option(resultSet.getString(1), resultSet.getString(2)));
    }

    /** Employees who appear as an actor in the log, for the filter. */
    public List<Option> actorOptions() {
        return jdbc.query("SELECT e.id::text, e.full_name, e.code FROM employees e WHERE EXISTS (SELECT 1 FROM audit_log a WHERE a.actor_id = e.id) ORDER BY e.full_name ASC LIMIT 1000",
                (resultSet, i) -> new Option(resultSet.getString(1), resultSet.getString(2) + " (" + resultSet.getString(3) + ")"));
    }

    /** "Name (code)" by employee id (text). */
    public Map<String, String> employeeLabels(String[] employeeIds) {
        Map<String, String> subjects = new LinkedHashMap<>();
        jdbc.query("SELECT id::text, full_name, code FROM employees WHERE id::text = ANY (?)", resultSet -> {
            subjects.put(resultSet.getString(1), resultSet.getString(2) + " (" + resultSet.getString(3) + ")");
        }, (Object) employeeIds);
        return subjects;
    }
}
