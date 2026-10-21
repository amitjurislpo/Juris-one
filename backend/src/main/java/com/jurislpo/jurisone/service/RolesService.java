package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.repository.RolesRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Roles & permissions (REQUIREMENTS §3). Admin only (roles.manage). */
@Service
public class RolesService {

    /** Built-in order from the prototype. */
    static final List<String> ORDER = List.of("owner", "hrm", "hre", "fm", "fe", "employee");

    public record Member(String id, String name) {}

    public record RoleView(String id, String key, String name, boolean builtin, boolean locked, List<String> perms, List<String> assign,
                           List<Member> members, int totalEver, boolean canEdit) {}

    private final RolesRepository repo;
    private final AuditService audit;

    public RolesService(RolesRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** {@code canEdit}: whether the caller may change roles (only the platform super admin; business owners see them read-only). */
    public List<RoleView> list(boolean canEdit) {
        List<Map<String, Object>> roles = repo.findAll();
        List<RoleView> out = new ArrayList<>();
        for (Map<String, Object> r : roles) {
            UUID id = (UUID) r.get("id");
            List<String> perms = repo.findPermissionsSorted(id);
            List<String> assign = repo.findAssignableRoleIds(id);
            List<Member> members = repo.findCurrentMembers(id);
            Integer total = repo.countEmployees(id);
            out.add(new RoleView(id.toString(), (String) r.get("key"), (String) r.get("name"), (Boolean) r.get("is_builtin"), (Boolean) r.get("is_locked"),
                    perms, assign, members, total == null ? 0 : total, canEdit));
        }
        out.sort((a, b) -> rank(a.key()) - rank(b.key())); // stable: keeps builtin/created order among equals
        return out;
    }

    private static int rank(String key) {
        int i = ORDER.indexOf(key);
        return i < 0 ? 99 : i + 1;
    }

    private Map<String, Object> editableRole(UUID roleId) {
        List<Map<String, Object>> rs = repo.findForEdit(roleId);
        if (rs.isEmpty()) throw new UserException("That role no longer exists.");
        Map<String, Object> role = rs.get(0);
        if (Boolean.TRUE.equals(role.get("is_locked"))) throw new UserException(role.get("name") + " has full access and can't be changed.");
        return role;
    }

    @Transactional
    public String setPermission(CurrentUser u, String roleIdRaw, String perm, boolean on, String ip) {
        UUID roleId = Check.uuid(roleIdRaw);
        if (perm == null || !Permissions.isPermission(perm)) throw new UserException("Unknown permission.");
        Map<String, Object> role = editableRole(roleId);
        List<String> before = new ArrayList<>(new TreeSet<>(repo.findPermissions(roleId)));
        if (on) repo.grantPermission(roleId, perm);
        else repo.revokePermission(roleId, perm);
        List<String> after;
        if (on) {
            TreeSet<String> s = new TreeSet<>(before);
            s.add(perm);
            after = new ArrayList<>(s);
        } else {
            after = before.stream().filter(p -> !p.equals(perm)).toList();
        }
        audit.log(u.id(), "role.permission_change", "role", roleId.toString(), Map.of("permissions", before), Map.of("permissions", after), ip);
        return role.get("name") + ": " + (on ? "granted" : "removed") + " “" + Permissions.LABELS.get(perm) + "”";
    }

    @Transactional
    public String setAssignable(CurrentUser u, String roleIdRaw, String assignableRaw, boolean on, String ip) {
        UUID roleId = Check.uuid(roleIdRaw);
        UUID assignableId = Check.uuid(assignableRaw);
        Map<String, Object> role = editableRole(roleId);
        List<String> target = repo.findName(assignableId);
        if (target.isEmpty()) throw new UserException("That role no longer exists.");
        String tName = target.get(0);
        if (on) repo.addAssignable(roleId, assignableId);
        else repo.removeAssignable(roleId, assignableId);
        audit.log(u.id(), "role.assignable_change", "role", roleId.toString(), Map.of(tName, !on), Map.of(tName, on), ip);
        return role.get("name") + " " + (on ? "can now" : "can no longer") + " add " + tName + "s";
    }

    public record Created(String id, String name) {}

    @Transactional
    public Created create(CurrentUser u, String nameRaw, String fromRaw) {
        String name = Check.trimmed(nameRaw, 2, "Role name is required.", 60, "Keep the name under 60 characters.");
        UUID fromId = Check.uuid(fromRaw);
        Integer dup = repo.countByNameIgnoreCase(name);
        if (dup != null && dup > 0) throw new UserException("A role with that name already exists.");
        List<String> src = repo.findName(fromId);
        if (src.isEmpty()) throw new UserException("Pick a role to start from.");
        // Copies the source's permissions, but never Administration — that must be granted deliberately.
        List<String> perms = repo.findPermissions(fromId).stream()
                .filter(p -> !p.equals("roles.manage")).toList();
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        String key = "custom_" + slug.substring(0, Math.min(30, slug.length())) + "_" + Long.toString(System.currentTimeMillis(), 36);
        UUID id = UUID.randomUUID();
        repo.insertCustom(id, key, name);
        for (String p : perms) repo.insertPermission(id, p);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("copiedFrom", src.get(0));
        after.put("permissions", perms);
        audit.log(u.id(), "role.create", "role", id.toString(), null, after, null);
        return new Created(id.toString(), name);
    }

    @Transactional
    public String delete(CurrentUser u, String roleIdRaw, String ip) {
        UUID roleId = Check.uuid(roleIdRaw);
        List<Map<String, Object>> rs = repo.findForDelete(roleId);
        if (rs.isEmpty()) throw new UserException("That role no longer exists.");
        if (Boolean.TRUE.equals(rs.get(0).get("is_builtin"))) throw new UserException("Built-in roles can't be deleted.");
        // Past employees keep their role on record, so they count too.
        Integer n = repo.countEmployees(roleId);
        if (n != null && n > 0) throw new UserException("Move everyone (including past employees) to another role first.");
        List<String> perms = repo.findPermissions(roleId);
        repo.delete(roleId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", rs.get(0).get("name"));
        before.put("permissions", perms);
        audit.log(u.id(), "role.delete", "role", roleId.toString(), before, null, null);
        return "Role “" + rs.get(0).get("name") + "” deleted";
    }
}
