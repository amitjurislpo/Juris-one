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

/** Everything the people rules need about the signed-in user and a target record (src/lib/actor.ts). */
@Service
public class ActorService {

    private final ActorRepository repo;

    public ActorService(ActorRepository repo) {
        this.repo = repo;
    }

    public Actor actor(CurrentUser u) {
        List<String> assign = repo.findAssignableRoleIds(u.roleId());
        String dept = repo.findDepartmentId(u.id());
        List<String> emp = repo.findEmployeeRoleIds();
        return new Actor(u.id().toString(), u.roleKey(), u.perms(), new HashSet<>(assign), dept, emp.isEmpty() ? "" : emp.get(0));
    }

    public Target target(UUID id) {
        List<Map<String, Object>> rs = repo.findTarget(id);
        if (rs.isEmpty()) return null;
        Map<String, Object> r = rs.get(0);
        return new Target((String) r.get("id"), (String) r.get("role_id"), (String) r.get("role_key"), EmployeeStatus.valueOf((String) r.get("status")),
                Boolean.TRUE.equals(r.get("is_super_admin")));
    }
}
