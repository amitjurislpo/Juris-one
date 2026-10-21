package com.jurislpo.jurisone.repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The platform's catalogue of role templates (no business_id: not tenant data). */
@Repository
public class RoleTemplateRepository {

    public record Template(String key, String name, boolean locked, boolean mandatory, List<String> permissions, List<String> assignable, int sortOrder, boolean active) {}

    private static final String COLS = "key, name, locked, mandatory, permissions, assignable, sort_order, active";

    private final JdbcTemplate jdbc;

    public RoleTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    private static Template map(java.sql.ResultSet rs, int i) throws SQLException {
        return new Template(rs.getString("key"), rs.getString("name"), rs.getBoolean("locked"), rs.getBoolean("mandatory"), strings(rs.getArray("permissions")),
                strings(rs.getArray("assignable")), rs.getInt("sort_order"), rs.getBoolean("active"));
    }

    public List<Template> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM role_templates ORDER BY sort_order, name", RoleTemplateRepository::map);
    }

    public List<Template> find(String key) {
        return jdbc.query("SELECT " + COLS + " FROM role_templates WHERE key = ?", RoleTemplateRepository::map, key);
    }

    public boolean nameTaken(String name, String exceptKey) {
        return !jdbc.queryForList("SELECT 1 FROM role_templates WHERE lower(name) = lower(?) AND (?::text IS NULL OR key <> ?)", name, exceptKey, exceptKey).isEmpty();
    }

    public int nextSortOrder() {
        return jdbc.queryForObject("SELECT coalesce(max(sort_order), 0) + 1 FROM role_templates", Integer.class);
    }

    public void insert(String key, String name, List<String> permissions, List<String> assignable, int sortOrder) {
        jdbc.update("INSERT INTO role_templates (key, name, permissions, assignable, sort_order) VALUES (?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, key);
            ps.setString(2, name);
            ps.setArray(3, ps.getConnection().createArrayOf("text", permissions.toArray()));
            ps.setArray(4, ps.getConnection().createArrayOf("text", assignable.toArray()));
            ps.setInt(5, sortOrder);
        });
    }

    public void update(String key, String name, List<String> permissions, List<String> assignable, boolean active) {
        jdbc.update("UPDATE role_templates SET name = ?, permissions = ?, assignable = ?, active = ? WHERE key = ?", ps -> {
            ps.setString(1, name);
            ps.setArray(2, ps.getConnection().createArrayOf("text", permissions.toArray()));
            ps.setArray(3, ps.getConnection().createArrayOf("text", assignable.toArray()));
            ps.setBoolean(4, active);
            ps.setString(5, key);
        });
    }

    public void delete(String key) {
        jdbc.update("DELETE FROM role_templates WHERE key = ?", key);
        jdbc.update("UPDATE role_templates SET assignable = array_remove(assignable, ?)", key);
    }

    /** Businesses that have a role made from this template (system mode: every business). */
    public int businessesUsing(String key) {
        return jdbc.queryForObject("SELECT count(DISTINCT business_id) FROM roles WHERE key = ? AND business_id IS NOT NULL", Integer.class, key);
    }
}
