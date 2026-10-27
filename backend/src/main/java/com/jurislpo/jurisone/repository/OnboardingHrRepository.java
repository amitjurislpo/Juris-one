package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** HR's list of onboardings (one row per employee that goes through the wizard). */
@Repository
public class OnboardingHrRepository {

    private final JdbcTemplate jdbc;

    public OnboardingHrRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One page, newest first. {@code where} always starts with the NOT_REQUIRED exclusion and is built from fixed fragments only; values are bound. */
    public List<Map<String, Object>> findPage(String where, List<Object> args, int take, int skip) {
        List<Object> all = new java.util.ArrayList<>(args);
        all.add(take);
        all.add(skip);
        return jdbc.queryForList("""
            SELECT e.id AS employee_id, e.full_name, e.code, e.email, e.designation, d.name AS department_name,
                   o.status, o.submitted_at, o.current_step
            FROM employee_onboardings o JOIN employees e ON e.id = o.employee_id LEFT JOIN departments d ON d.id = e.department_id
            """ + where + " ORDER BY o.created_at DESC, e.full_name ASC LIMIT ? OFFSET ?", all.toArray());
    }

    public int count(String where, List<Object> args) {
        Integer total = jdbc.queryForObject("SELECT count(*) FROM employee_onboardings o JOIN employees e ON e.id = o.employee_id " + where, Integer.class, args.toArray());
        return total == null ? 0 : total;
    }
}
