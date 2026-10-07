package com.jurislpo.jurisone;

import com.jurislpo.jurisone.crypto.Passwords;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Inserts the rows API tests need, with plain SQL (independent of the code under test). */
public final class TestData {

    private final JdbcTemplate jdbc;
    private static final Passwords PASSWORDS = new Passwords();

    public TestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID role(String key, String... perms) {
        UUID existing = jdbc.query("SELECT id FROM roles WHERE key = ?", rs -> rs.next() ? (UUID) rs.getObject(1) : null, key);
        if (existing != null) return existing;
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO roles (id, key, name) VALUES (?, ?, ?)", id, key, key.substring(0, 1).toUpperCase() + key.substring(1));
        for (String p : perms) jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, ?)", id, p);
        return id;
    }

    /** An India employee with a password (null = invited, not activated). */
    public UUID employee(String code, String email, UUID roleId, String password) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO employees (id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, portal_access, updated_at)
            VALUES (?, ?, ?, ?, 'Associate', ?, ?, 'ACTIVE', 'Asia/Kolkata', 'IN', true, now())""",
                id, code, "Person " + code, email, roleId, Date.valueOf(LocalDate.of(2025, 1, 1)));
        jdbc.update("INSERT INTO users (employee_id, password_hash) VALUES (?, ?)", id, password == null ? null : PASSWORDS.hash(password));
        return id;
    }
}
