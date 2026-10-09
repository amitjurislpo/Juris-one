package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.PeopleRules.Actor;
import com.jurislpo.jurisone.engine.PeopleRules.Target;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Everything the people rules need about the signed-in user and a target record (src/lib/actor.ts). */
@Service
public class ActorService {

    private final JdbcTemplate jdbc;

    public ActorService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Actor actor(CurrentUser u) {
        List<String> assign = jdbc.queryForList("SELECT assignable_role_id::text FROM role_assignable_roles WHERE role_id = ?", String.class, u.roleId());
        String dept = jdbc.queryForObject("SELECT department_id::text FROM employees WHERE id = ?", String.class, u.id());
        List<String> emp = jdbc.queryForList("SELECT id::text FROM roles WHERE key = 'employee'", String.class);
        return new Actor(u.id().toString(), u.roleKey(), u.perms(), new HashSet<>(assign), dept, emp.isEmpty() ? "" : emp.get(0));
    }

    public Target target(UUID id) {
        List<Map<String, Object>> rs = jdbc.queryForList("""
            SELECT e.id::text AS id, e.role_id::text AS role_id, e.status::text AS status, r.key AS role_key, u.is_super_admin
            FROM employees e JOIN roles r ON r.id = e.role_id LEFT JOIN users u ON u.employee_id = e.id WHERE e.id = ?""", id);
        if (rs.isEmpty()) return null;
        Map<String, Object> r = rs.get(0);
        return new Target((String) r.get("id"), (String) r.get("role_id"), (String) r.get("role_key"), EmployeeStatus.valueOf((String) r.get("status")),
                Boolean.TRUE.equals(r.get("is_super_admin")));
    }
}
