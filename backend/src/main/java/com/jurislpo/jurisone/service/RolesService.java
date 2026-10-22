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
        for (Map<String, Object> role : roles) {
            UUID id = (UUID) role.get("id");
            List<String> perms = repo.findPermissionsSorted(id);
            List<String> assign = repo.findAssignableRoleIds(id);
            List<Member> members = repo.findCurrentMembers(id);
            Integer total = repo.countEmployees(id);
            out.add(new RoleView(id.toString(), (String) role.get("key"), (String) role.get("name"), (Boolean) role.get("is_builtin"), (Boolean) role.get("is_locked"),
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
        List<Map<String, Object>> rows = repo.findForEdit(roleId);
        if (rows.isEmpty()) throw new UserException("That role no longer exists.");
        Map<String, Object> role = rows.get(0);
        if (Boolean.TRUE.equals(role.get("is_locked"))) throw new UserException(role.get("name") + " has full access and can't be changed.");
        return role;
    }

    @Transactional
    public String setPermission(CurrentUser user, String roleIdRaw, String perm, boolean granted, String ipAddress) {
        UUID roleId = Check.uuid(roleIdRaw);
        if (perm == null || !Permissions.isPermission(perm)) throw new UserException("Unknown permission.");
        Map<String, Object> role = editableRole(roleId);
        List<String> before = new ArrayList<>(new TreeSet<>(repo.findPermissions(roleId)));
        if (granted) repo.grantPermission(roleId, perm);
        else repo.revokePermission(roleId, perm);
        List<String> after;
        if (granted) {
            TreeSet<String> permissions = new TreeSet<>(before);
            permissions.add(perm);
            after = new ArrayList<>(permissions);
        } else {
            after = before.stream().filter(p -> !p.equals(perm)).toList();
        }
        audit.log(user.id(), "role.permission_change", "role", roleId.toString(), Map.of("permissions", before), Map.of("permissions", after), ipAddress);
        return role.get("name") + ": " + (granted ? "granted" : "removed") + " “" + Permissions.LABELS.get(perm) + "”";
    }

    @Transactional
    public String setAssignable(CurrentUser user, String roleIdRaw, String assignableRaw, boolean assignable, String ipAddress) {
        UUID roleId = Check.uuid(roleIdRaw);
        UUID assignableId = Check.uuid(assignableRaw);
        Map<String, Object> role = editableRole(roleId);
        List<String> target = repo.findName(assignableId);
        if (target.isEmpty()) throw new UserException("That role no longer exists.");
        String tName = target.get(0);
        if (assignable) repo.addAssignable(roleId, assignableId);
        else repo.removeAssignable(roleId, assignableId);
        audit.log(user.id(), "role.assignable_change", "role", roleId.toString(), Map.of(tName, !assignable), Map.of(tName, assignable), ipAddress);
        return role.get("name") + " " + (assignable ? "can now" : "can no longer") + " add " + tName + "s";
    }

    public record Created(String id, String name) {}

    @Transactional
    public Created create(CurrentUser user, String nameRaw, String fromRaw) {
        String name = Check.trimmed(nameRaw, 2, "Role name is required.", 60, "Keep the name under 60 characters.");
        UUID fromId = Check.uuid(fromRaw);
        Integer duplicateCount = repo.countByNameIgnoreCase(name);
        if (duplicateCount != null && duplicateCount > 0) throw new UserException("A role with that name already exists.");
        List<String> sourceNames = repo.findName(fromId);
        if (sourceNames.isEmpty()) throw new UserException("Pick a role to start from.");
        // Copies the source's permissions, but never Administration — that must be granted deliberately.
        List<String> perms = repo.findPermissions(fromId).stream()
                .filter(p -> !p.equals("roles.manage")).toList();
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        String key = "custom_" + slug.substring(0, Math.min(30, slug.length())) + "_" + Long.toString(System.currentTimeMillis(), 36);
        UUID id = UUID.randomUUID();
        repo.insertCustom(id, key, name);
        for (String perm : perms) repo.insertPermission(id, perm);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("copiedFrom", sourceNames.get(0));
        after.put("permissions", perms);
        audit.log(user.id(), "role.create", "role", id.toString(), null, after, null);
        return new Created(id.toString(), name);
    }

    @Transactional
    public String delete(CurrentUser user, String roleIdRaw, String ipAddress) {
        UUID roleId = Check.uuid(roleIdRaw);
        List<Map<String, Object>> rows = repo.findForDelete(roleId);
        if (rows.isEmpty()) throw new UserException("That role no longer exists.");
        if (Boolean.TRUE.equals(rows.get(0).get("is_builtin"))) throw new UserException("Built-in roles can't be deleted.");
        // Past employees keep their role on record, so they count too.
        Integer count = repo.countEmployees(roleId);
        if (count != null && count > 0) throw new UserException("Move everyone (including past employees) to another role first.");
        List<String> perms = repo.findPermissions(roleId);
        repo.delete(roleId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", rows.get(0).get("name"));
        before.put("permissions", perms);
        audit.log(user.id(), "role.delete", "role", roleId.toString(), before, null, null);
        return "Role “" + rows.get(0).get("name") + "” deleted";
    }
}
