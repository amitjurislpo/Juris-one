package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.service.RegisterService.Dept;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for {@code RegisterService}. */
@Repository
public class RegisterRepository {

    private final JdbcTemplate jdbc;

    public RegisterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Departments not archived, by name (the register's department filter). */
    public List<Dept> activeDepartments() {
        return jdbc.query("SELECT id::text, name FROM departments WHERE archived_at IS NULL ORDER BY name ASC", (rs, i) -> new Dept(rs.getString(1), rs.getString(2)));
    }
}
