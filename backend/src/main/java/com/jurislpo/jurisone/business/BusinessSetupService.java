package com.jurislpo.jurisone.business;

import com.jurislpo.jurisone.crypto.Passwords;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.platform.SettingsService;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a business with everything it needs to start: its own roles (the owner — the sub admin — plus HR, Finance and
 * Employee), default policies and the HR and Finance departments; and adds sub admins (owners) to it.
 */
@Service
public class BusinessSetupService {

    public static final String OWNER = "owner";

    /** A role every new business starts with. Owner: everything a business admin needs, but no attendance of their own. */
    public record RoleTemplate(String key, String name, boolean locked, List<String> perms, List<String> assign) {}

    public static final List<RoleTemplate> ROLES = List.of(
            new RoleTemplate(OWNER, "Owner", true, java.util.stream.Stream.concat(Permissions.KEYS.stream().filter(p -> !Permissions.SELF.equals(p)),
                    java.util.stream.Stream.of(Permissions.BUSINESS_MANAGE)).toList(),
                    List.of("hrm", "hre", "fm", "fe", "employee")),
            new RoleTemplate("hrm", "HR Manager", false, List.of("self", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "people.add", "access.toggle",
                    "dept.manage", "holidays.manage"), List.of("hre", "employee")),
            new RoleTemplate("hre", "HR Executive", false, List.of("self", "dir", "att.all", "appr.leave", "appr.reg", "people.view"), List.of()),
            new RoleTemplate("fm", "Finance Manager", false, List.of("self", "dir", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage",
                    "pay.view", "pay.close", "struct.manage", "tax.view", "proofs.verify"), List.of("fe")),
            new RoleTemplate("fe", "Finance Executive", false, List.of("self", "dir", "appr.reg", "pay.view", "tax.view", "proofs.verify"), List.of()),
            new RoleTemplate("employee", "Employee", false, List.of("self", "dir"), List.of()));

    public record NewBusiness(String name, String legalName, String code, String employeePrefix, String timeZone, String contactEmail,
                              String contactPhone, String address, String gstin) {}

    private final JdbcTemplate jdbc;
    private final Passwords passwords;
    private final TransactionTemplate tx;

    public BusinessSetupService(JdbcTemplate jdbc, Passwords passwords, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tx = tx;
    }

    /** A URL-safe code from a name: "Juris Consultants" → "juris-consultants". */
    public static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "business" : s.substring(0, Math.min(40, s.length()));
    }

    /** A default employee ID prefix: initials of the name ("Juris Consultants" → "JC"), 2–5 letters. */
    public static String defaultPrefix(String name) {
        StringBuilder b = new StringBuilder();
        for (String w : name.trim().split("\\s+")) if (!w.isEmpty() && Character.isLetter(w.charAt(0))) b.append(Character.toUpperCase(w.charAt(0)));
        String p = b.length() >= 2 ? b.toString() : name.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        if (p.length() < 2) p = (p + "XX").substring(0, 2);
        return p.substring(0, Math.min(5, p.length()));
    }

    /** Creates the business (system mode) and its defaults (inside the business). Returns its id. */
    public UUID create(NewBusiness b, UUID createdBy) {
        UUID id = UUID.randomUUID();
        TenantContext.run(null, () -> jdbc.update("""
            INSERT INTO businesses (id, name, legal_name, code, employee_prefix, time_zone, contact_email, contact_phone, address, gstin, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, b.name(), b.legalName(), b.code(), b.employeePrefix(), b.timeZone() == null ? "Asia/Kolkata" : b.timeZone(), b.contactEmail(),
                b.contactPhone(), b.address(), b.gstin(), createdBy));
        TenantContext.run(id, () -> initDefaults(createdBy));
        return id;
    }

    /** Roles, policies and the HR / Finance departments of the current business (idempotent). */
    public Map<String, UUID> initDefaults(UUID createdBy) {
        requireBusiness();
        Map<String, UUID> ids = new HashMap<>();
        for (RoleTemplate r : ROLES) {
            List<UUID> existing = jdbc.queryForList("SELECT id FROM roles WHERE key = ?", UUID.class, r.key());
            UUID id = existing.isEmpty() ? UUID.randomUUID() : existing.get(0);
            if (existing.isEmpty()) {
                jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked) VALUES (?, ?, ?, true, ?)", id, r.key(), r.name(), r.locked());
            }
            ids.put(r.key(), id);
        }
        for (RoleTemplate r : ROLES) {
            UUID id = ids.get(r.key());
            // defaults are written only to a role without permissions yet; the owner role is locked and always complete
            long has = jdbc.queryForObject("SELECT count(*) FROM role_permissions WHERE role_id = ?", Long.class, id);
            if (has == 0 || r.locked()) {
                jdbc.update("DELETE FROM role_permissions WHERE role_id = ?", id);
                for (String p : r.perms()) jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, ?)", id, p);
            }
            long hasAssign = jdbc.queryForObject("SELECT count(*) FROM role_assignable_roles WHERE role_id = ?", Long.class, id);
            if (hasAssign == 0 || r.locked()) {
                jdbc.update("DELETE FROM role_assignable_roles WHERE role_id = ?", id);
                for (String a : r.assign()) jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?)", id, ids.get(a));
            }
        }
        for (Map.Entry<String, String> e : SettingsService.DEFAULTS.entrySet()) {
            jdbc.update("INSERT INTO settings (key, value, updated_at) VALUES (?, ?::jsonb, now()) ON CONFLICT (business_id, key) DO NOTHING", e.getKey(), e.getValue());
        }
        for (String[] d : new String[][] {{"Human Resources", "People operations, hiring and policies"}, {"Finance", "Payroll, tax and accounts"}}) {
            jdbc.update("INSERT INTO departments (id, name, description, created_by) VALUES (?, ?, ?, ?) ON CONFLICT (business_id, name) DO NOTHING",
                    UUID.randomUUID(), d[0], d[1], createdBy);
        }
        return ids;
    }

    public record NewOwner(String name, String email, String password, String designation) {}

    /**
     * Adds a sub admin (owner) to the current business: an employee with the owner role and portal access, and a
     * user with {@code password} (null = they set it from an invite link). Returns the employee id.
     */
    public UUID addOwner(NewOwner o, UUID createdBy) {
        requireBusiness();
        return tx.execute(s -> insertOwner(o, createdBy)); // the code allocation lock holds until commit
    }

    private UUID insertOwner(NewOwner o, UUID createdBy) {
        Map<String, Object> biz = jdbc.queryForMap("SELECT employee_prefix, time_zone FROM businesses WHERE id = jurisone_tenant_id()");
        String prefix = (String) biz.get("employee_prefix");
        String tz = (String) biz.get("time_zone");
        UUID role = jdbc.queryForObject("SELECT id FROM roles WHERE key = ?", UUID.class, OWNER);
        jdbc.execute("SELECT pg_advisory_xact_lock(74201, hashtext(jurisone_tenant_id()::text))");
        Integer n = jdbc.queryForObject("SELECT max(substring(code from ?)::int) FROM employees WHERE code ~ ?", Integer.class,
                prefix.length() + 2, "^" + prefix + "-[0-9]+$");
        String code = prefix + "-" + String.format(Locale.ROOT, "%03d", (n == null ? 0 : n) + 1);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO employees (id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, portal_access, created_by, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?::"Region", true, ?, now())""",
                id, code, o.name(), o.email().trim().toLowerCase(Locale.ROOT), o.designation() == null ? "Owner" : o.designation(), role,
                Date.valueOf(LocalDate.now(ZoneId.of(tz))), tz, "Asia/Kolkata".equals(tz) ? "IN" : "US", createdBy);
        jdbc.update("INSERT INTO users (employee_id, password_hash, password_set_at, is_super_admin) VALUES (?, ?, ?, false)",
                id, o.password() == null ? null : passwords.hash(o.password()), o.password() == null ? null : Timestamp.from(Instant.now()));
        return id;
    }

    private static void requireBusiness() {
        if (TenantContext.current() == null) throw new IllegalStateException("Run inside a business (TenantContext).");
    }
}
