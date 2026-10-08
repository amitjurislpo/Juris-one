package com.jurislpo.jurisone.roles;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.UserException;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.platform.AuditService;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Roles & permissions (REQUIREMENTS §3; src/app/(app)/roles). Admin only (roles.manage). */
@Service
public class RolesService {

    /** Built-in order from the prototype. */
    static final List<String> ORDER = List.of("owner", "hrm", "hre", "fm", "fe", "employee");

    public record Member(String id, String name) {}

    public record RoleView(String id, String key, String name, boolean builtin, boolean locked, List<String> perms, List<String> assign,
                           List<Member> members, int totalEver) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public RolesService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<RoleView> list() {
        List<Map<String, Object>> roles = jdbc.queryForList("SELECT id, key, name, is_builtin, is_locked FROM roles ORDER BY is_builtin DESC, created_at ASC");
        List<RoleView> out = new ArrayList<>();
        for (Map<String, Object> r : roles) {
            UUID id = (UUID) r.get("id");
            List<String> perms = jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ? ORDER BY permission_key", String.class, id);
            List<String> assign = jdbc.queryForList("SELECT assignable_role_id::text FROM role_assignable_roles WHERE role_id = ?", String.class, id);
            List<Member> members = jdbc.query("SELECT id, full_name FROM employees WHERE role_id = ? AND status <> 'EXITED' ORDER BY full_name ASC",
                    (rs, i) -> new Member(rs.getString(1), rs.getString(2)), id);
            Integer total = jdbc.queryForObject("SELECT count(*) FROM employees WHERE role_id = ?", Integer.class, id);
            out.add(new RoleView(id.toString(), (String) r.get("key"), (String) r.get("name"), (Boolean) r.get("is_builtin"), (Boolean) r.get("is_locked"),
                    perms, assign, members, total == null ? 0 : total));
        }
        out.sort((a, b) -> rank(a.key()) - rank(b.key())); // stable: keeps builtin/created order among equals
        return out;
    }

    private static int rank(String key) {
        int i = ORDER.indexOf(key);
        return i < 0 ? 99 : i + 1;
    }

    private Map<String, Object> editableRole(UUID roleId) {
        List<Map<String, Object>> rs = jdbc.queryForList("SELECT id, name, is_locked FROM roles WHERE id = ?", roleId);
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
        List<String> before = new ArrayList<>(new TreeSet<>(jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?", String.class, roleId)));
        if (on) jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, ?) ON CONFLICT DO NOTHING", roleId, perm);
        else jdbc.update("DELETE FROM role_permissions WHERE role_id = ? AND permission_key = ?", roleId, perm);
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
        List<String> target = jdbc.queryForList("SELECT name FROM roles WHERE id = ?", String.class, assignableId);
        if (target.isEmpty()) throw new UserException("That role no longer exists.");
        String tName = target.get(0);
        if (on) jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", roleId, assignableId);
        else jdbc.update("DELETE FROM role_assignable_roles WHERE role_id = ? AND assignable_role_id = ?", roleId, assignableId);
        audit.log(u.id(), "role.assignable_change", "role", roleId.toString(), Map.of(tName, !on), Map.of(tName, on), ip);
        return role.get("name") + " " + (on ? "can now" : "can no longer") + " add " + tName + "s";
    }

    public record Created(String id, String name) {}

    @Transactional
    public Created create(CurrentUser u, String nameRaw, String fromRaw) {
        String name = Check.trimmed(nameRaw, 2, "Role name is required.", 60, "Keep the name under 60 characters.");
        UUID fromId = Check.uuid(fromRaw);
        Integer dup = jdbc.queryForObject("SELECT count(*) FROM roles WHERE lower(name) = lower(?)", Integer.class, name);
        if (dup != null && dup > 0) throw new UserException("A role with that name already exists.");
        List<String> src = jdbc.queryForList("SELECT name FROM roles WHERE id = ?", String.class, fromId);
        if (src.isEmpty()) throw new UserException("Pick a role to start from.");
        // Copies the source's permissions, but never Administration — that must be granted deliberately.
        List<String> perms = jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?", String.class, fromId).stream()
                .filter(p -> !p.equals("roles.manage")).toList();
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        String key = "custom_" + slug.substring(0, Math.min(30, slug.length())) + "_" + Long.toString(System.currentTimeMillis(), 36);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked, created_at) VALUES (?, ?, ?, false, false, now())", id, key, name);
        for (String p : perms) jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, ?)", id, p);
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
        List<Map<String, Object>> rs = jdbc.queryForList("SELECT name, is_builtin FROM roles WHERE id = ?", roleId);
        if (rs.isEmpty()) throw new UserException("That role no longer exists.");
        if (Boolean.TRUE.equals(rs.get(0).get("is_builtin"))) throw new UserException("Built-in roles can't be deleted.");
        // Past employees keep their role on record, so they count too.
        Integer n = jdbc.queryForObject("SELECT count(*) FROM employees WHERE role_id = ?", Integer.class, roleId);
        if (n != null && n > 0) throw new UserException("Move everyone (including past employees) to another role first.");
        List<String> perms = jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?", String.class, roleId);
        jdbc.update("DELETE FROM roles WHERE id = ?", roleId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", rs.get(0).get("name"));
        before.put("permissions", perms);
        audit.log(u.id(), "role.delete", "role", roleId.toString(), before, null, null);
        return "Role “" + rs.get(0).get("name") + "” deleted";
    }
}
