package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.PeopleRules.Actor;
import com.jurislpo.jurisone.engine.PeopleRules.Target;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.repository.ActorRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Everything the people rules need about the signed-in user and a target record. */
@Service
public class ActorService {

    private final ActorRepository repo;

    public ActorService(ActorRepository repo) {
        this.repo = repo;
    }

    public Actor actor(CurrentUser user) {
        List<String> assign = repo.findAssignableRoleIds(user.roleId());
        String department = repo.findDepartmentId(user.id());
        List<String> employee = repo.findEmployeeRoleIds();
        return new Actor(user.id().toString(), user.roleKey(), user.perms(), new HashSet<>(assign), department, employee.isEmpty() ? "" : employee.get(0));
    }

    public Target target(UUID id) {
        List<Map<String, Object>> rs = repo.findTarget(id);
        if (rs.isEmpty()) return null;
        Map<String, Object> row = rs.get(0);
        return new Target((String) row.get("id"), (String) row.get("role_id"), (String) row.get("role_key"), EmployeeStatus.valueOf((String) row.get("status")),
                Boolean.TRUE.equals(row.get("is_super_admin")));
    }
}
