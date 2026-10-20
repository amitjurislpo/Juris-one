package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.crypto.Passwords;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.repository.BusinessSetupRepository;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
                    "dept.manage", "holidays.manage", "policies.manage", "assets.manage", "announcements.manage", "exit.manage", "letters.manage"), List.of("hre", "employee")),
            new RoleTemplate("hre", "HR Executive", false, List.of("self", "dir", "att.all", "appr.leave", "appr.reg", "people.view"), List.of()),
            new RoleTemplate("fm", "Finance Manager", false, List.of("self", "dir", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage",
                    "pay.view", "pay.close", "struct.manage", "tax.view", "proofs.verify"), List.of("fe")),
            new RoleTemplate("fe", "Finance Executive", false, List.of("self", "dir", "appr.reg", "pay.view", "tax.view", "proofs.verify"), List.of()),
            new RoleTemplate("employee", "Employee", false, List.of("self", "dir"), List.of()));

    public record NewBusiness(String name, String legalName, String code, String employeePrefix, String timeZone, String contactEmail,
                              String contactPhone, String address, String gstin) {}

    private final BusinessSetupRepository repo;
    private final Passwords passwords;
    private final TransactionTemplate tx;

    public BusinessSetupService(BusinessSetupRepository repo, Passwords passwords, TransactionTemplate tx) {
        this.repo = repo;
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
        TenantContext.run(null, () -> repo.insertBusiness(
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
            List<UUID> existing = repo.findRoleIds(r.key());
            UUID id = existing.isEmpty() ? UUID.randomUUID() : existing.get(0);
            if (existing.isEmpty()) {
                repo.insertRole(id, r.key(), r.name(), r.locked());
            }
            ids.put(r.key(), id);
        }
        for (RoleTemplate r : ROLES) {
            UUID id = ids.get(r.key());
            // defaults are written only to a role without permissions yet; the owner role is locked and always complete
            long has = repo.countPermissions(id);
            if (has == 0 || r.locked()) {
                repo.deletePermissions(id);
                for (String p : r.perms()) repo.insertPermission(id, p);
            }
            long hasAssign = repo.countAssignableRoles(id);
            if (hasAssign == 0 || r.locked()) {
                repo.deleteAssignableRoles(id);
                for (String a : r.assign()) repo.insertAssignableRole(id, ids.get(a));
            }
        }
        for (Map.Entry<String, String> e : SettingsService.DEFAULTS.entrySet()) {
            repo.insertSettingIfMissing(e.getKey(), e.getValue());
        }
        for (String[] d : new String[][] {{"Human Resources", "People operations, hiring and policies"}, {"Finance", "Payroll, tax and accounts"}}) {
            repo.insertDepartmentIfMissing(UUID.randomUUID(), d[0], d[1], createdBy);
        }
        repo.seedLetterTemplates(TenantContext.current());
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
        Map<String, Object> biz = repo.findCurrentPrefixAndZone();
        String prefix = (String) biz.get("employee_prefix");
        String tz = (String) biz.get("time_zone");
        UUID role = repo.findRoleId(OWNER);
        repo.lockCodeAllocation();
        Integer n = repo.findMaxCodeNumber(prefix);
        String code = prefix + "-" + String.format(Locale.ROOT, "%03d", (n == null ? 0 : n) + 1);
        UUID id = UUID.randomUUID();
        repo.insertOwnerEmployee(id, code, o.name(), o.email().trim().toLowerCase(Locale.ROOT), o.designation() == null ? "Owner" : o.designation(), role,
                LocalDate.now(ZoneId.of(tz)), tz, "Asia/Kolkata".equals(tz) ? "IN" : "US", createdBy);
        repo.insertUser(id, o.password() == null ? null : passwords.hash(o.password()), o.password() == null ? null : Instant.now());
        return id;
    }

    private static void requireBusiness() {
        if (TenantContext.current() == null) throw new IllegalStateException("Run inside a business (TenantContext).");
    }
}
