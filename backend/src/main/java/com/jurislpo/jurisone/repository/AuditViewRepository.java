package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.crypto.EmployeeNameReader;
import com.jurislpo.jurisone.service.AuditViewService.Option;
import java.time.Instant;
import java.util.Comparator;
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
    private final EmployeeNameReader nameReader;

    public AuditViewRepository(JdbcTemplate jdbc, EmployeeNameReader nameReader) {
        this.nameReader = nameReader;
        this.jdbc = jdbc;
    }

    /** One page of audit entries; {@code fromWhere} is the FROM clause plus the filters, {@code args} ends with OFFSET and LIMIT. */
    public List<LogRow> findPage(String fromWhere, String order, Object[] args) {
        // the business name is only joined (alias b) on the super admin's query
        String business = fromWhere.contains(" businesses b ") ? "b.name" : "NULL::text";
        return jdbc.query("SELECT a.id::text AS id, a.at, e.full_name, e.full_name_enc, e.code, a.action, a.entity, a.entity_id, a.ip, a.before::text AS before, a.after::text AS after, a.method, a.path, a.status, a.duration_ms, "
                + business + " AS business_name" + fromWhere + " ORDER BY " + order + ", a.id OFFSET ? LIMIT ?",
                (resultSet, i) -> new LogRow(resultSet.getString("id"), resultSet.getTimestamp("at").toInstant(), nameReader.read(resultSet, "full_name"), resultSet.getString("code"), resultSet.getString("action"),
                        resultSet.getString("entity"), resultSet.getString("entity_id"), resultSet.getString("ip"), resultSet.getString("before"), resultSet.getString("after"),
                        resultSet.getString("method"), resultSet.getString("path"), (Integer) resultSet.getObject("status"), (Integer) resultSet.getObject("duration_ms"),
                        resultSet.getString("business_name")), args);
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
        return jdbc.query("SELECT e.id::text AS id, e.full_name, e.full_name_enc, e.code FROM employees e WHERE EXISTS (SELECT 1 FROM audit_log a WHERE a.actor_id = e.id) LIMIT 1000",
                (resultSet, i) -> new Option(resultSet.getString("id"), nameReader.read(resultSet, "full_name") + " (" + resultSet.getString("code") + ")")).stream()
                .sorted(Comparator.comparing(Option::label, EmployeeNameReader.NAME_ORDER)).toList();
    }

    /** "Name (code)" by employee id (text). */
    public Map<String, String> employeeLabels(String[] employeeIds) {
        Map<String, String> subjects = new LinkedHashMap<>();
        jdbc.query("SELECT id::text AS id, full_name, full_name_enc, code FROM employees WHERE id::text = ANY (?)", resultSet -> {
            subjects.put(resultSet.getString("id"), nameReader.read(resultSet, "full_name") + " (" + resultSet.getString("code") + ")");
        }, (Object) employeeIds);
        return subjects;
    }
}
