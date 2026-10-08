package com.jurislpo.jurisone;

import com.jurislpo.jurisone.crypto.Passwords;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts the rows API tests need, with plain SQL (independent of the code under test). Everything belongs to one test
 * business unless a test asks for another one with {@link #inBusiness(String)}.
 */
public final class TestData {

    public static final String DEFAULT_BUSINESS = "test-business";

    private final JdbcTemplate jdbc;
    private final UUID business;
    private static final Passwords PASSWORDS = new Passwords();

    public TestData(JdbcTemplate jdbc) {
        this(jdbc, DEFAULT_BUSINESS);
    }

    private TestData(JdbcTemplate jdbc, String businessCode) {
        this.jdbc = jdbc;
        this.business = ensureBusiness(jdbc, businessCode);
    }

    /** The same helpers, for another business (created on first use). */
    public TestData inBusiness(String businessCode) {
        return new TestData(jdbc, businessCode);
    }

    public UUID business() {
        return business;
    }

    static synchronized UUID ensureBusiness(JdbcTemplate jdbc, String code) {
        List<UUID> found = jdbc.queryForList("SELECT id FROM businesses WHERE code = ?", UUID.class, code);
        if (!found.isEmpty()) return found.get(0);
        UUID id = UUID.randomUUID();
        String prefix = code.replaceAll("[^a-z]", "").toUpperCase().substring(0, 2);
        jdbc.update("INSERT INTO businesses (id, name, code, employee_prefix) VALUES (?, ?, ?, ?)", id, "Business " + code, code, prefix);
        return id;
    }

    public UUID role(String key, String... perms) {
        UUID existing = jdbc.query("SELECT id FROM roles WHERE key = ? AND business_id = ?", rs -> rs.next() ? (UUID) rs.getObject(1) : null, key, business);
        if (existing != null) return existing;
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO roles (id, key, name, business_id) VALUES (?, ?, ?, ?)", id, key, key.substring(0, 1).toUpperCase() + key.substring(1), business);
        for (String p : perms) jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, ?)", id, p);
        return id;
    }

    /** An India employee with a password (null = invited, not activated). */
    public UUID employee(String code, String email, UUID roleId, String password) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO employees (id, business_id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, portal_access, updated_at)
            VALUES (?, ?, ?, ?, ?, 'Associate', ?, ?, 'ACTIVE', 'Asia/Kolkata', 'IN', true, now())""",
                id, business, code, "Person " + code, email, roleId, Date.valueOf(LocalDate.of(2025, 1, 1)));
        jdbc.update("INSERT INTO users (employee_id, password_hash) VALUES (?, ?)", id, password == null ? null : PASSWORDS.hash(password));
        return id;
    }
}
