package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.repository.BusinessSetupRepository;
import com.jurislpo.jurisone.repository.RoleTemplateRepository;
import com.jurislpo.jurisone.tenancy.TenantContext;
import com.jurislpo.jurisone.exception.UserException;
import org.springframework.http.HttpStatus;
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

    public record NewBusiness(String name, String legalName, String code, String employeePrefix, String timeZone, String contactEmail,
                              String contactPhone, String address, String gstin) {}

    private final BusinessSetupRepository repo;
    private final TransactionTemplate tx;
    private final RoleTemplateService templates;

    public BusinessSetupService(BusinessSetupRepository repo, TransactionTemplate tx, RoleTemplateService templates) {
        this.repo = repo;
        this.tx = tx;
        this.templates = templates;
    }

    /** A URL-safe code from a name: "Juris Consultants" → "juris-consultants". */
    public static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "business" : s.substring(0, Math.min(40, s.length()));
    }

    /** A default employee ID prefix: initials of the name ("Juris Consultants" → "JC"), 2–5 letters. */
    public static String defaultPrefix(String name) {
        StringBuilder builder = new StringBuilder();
        for (String w : name.trim().split("\\s+")) if (!w.isEmpty() && Character.isLetter(w.charAt(0))) builder.append(Character.toUpperCase(w.charAt(0)));
        String p = builder.length() >= 2 ? builder.toString() : name.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        if (p.length() < 2) p = (p + "XX").substring(0, 2);
        return p.substring(0, Math.min(5, p.length()));
    }

    /** Creates the business with every active role template (the demo seed and the platform company). */
    public UUID create(NewBusiness newBusiness, UUID createdBy) {
        return createWith(newBusiness, createdBy, templates.allActive());
    }

    /** Creates the business with the mandatory role templates plus the chosen optional ones (unknown or switched-off keys → 422, before anything is created). */
    public UUID create(NewBusiness newBusiness, UUID createdBy, List<String> roleKeys) {
        return createWith(newBusiness, createdBy, templates.forNewBusiness(roleKeys));
    }

    private UUID createWith(NewBusiness newBusiness, UUID createdBy, List<RoleTemplateRepository.Template> roles) {
        UUID id = UUID.randomUUID();
        TenantContext.run(null, () -> repo.insertBusiness(
                id, newBusiness.name(), newBusiness.legalName(), newBusiness.code(), newBusiness.employeePrefix(), newBusiness.timeZone() == null ? "Asia/Kolkata" : newBusiness.timeZone(), newBusiness.contactEmail(),
                newBusiness.contactPhone(), newBusiness.address(), newBusiness.gstin(), createdBy));
        TenantContext.run(id, () -> initDefaults(createdBy, roles));
        return id;
    }

    /** Roles (every active template), policies and the HR / Finance departments of the current business (idempotent). */
    public Map<String, UUID> initDefaults(UUID createdBy) {
        return initDefaults(createdBy, templates.allActive());
    }

    private Map<String, UUID> initDefaults(UUID createdBy, List<RoleTemplateRepository.Template> roles) {
        requireBusiness();
        Map<String, UUID> ids = new HashMap<>();
        for (RoleTemplateRepository.Template template : roles) {
            List<UUID> existing = repo.findRoleIds(template.key());
            UUID id = existing.isEmpty() ? UUID.randomUUID() : existing.get(0);
            if (existing.isEmpty()) {
                repo.insertRole(id, template.key(), template.name(), template.locked());
            }
            ids.put(template.key(), id);
        }
        for (RoleTemplateRepository.Template template : roles) {
            UUID id = ids.get(template.key());
            // a role's permissions and assignable roles are written only when it has none yet (the owner role is locked and always complete)
            long has = repo.countPermissions(id);
            if (has == 0 || template.locked()) {
                repo.deletePermissions(id);
                for (String p : template.permissions()) repo.insertPermission(id, p);
            }
            long hasAssign = repo.countAssignableRoles(id);
            if (hasAssign == 0 || template.locked()) {
                repo.deleteAssignableRoles(id);
                for (String a : template.assignable()) {
                    UUID target = ids.containsKey(a) ? ids.get(a) : repo.findRoleIds(a).stream().findFirst().orElse(null);
                    if (target != null) repo.insertAssignableRole(id, target);
                }
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

    /**
     * Gives the current business a role made from {@code t}: a snapshot of its permissions and assignable roles. Roles the
     * business already has whose template lets them add this one get linked to it too.
     */
    public UUID addRole(RoleTemplateRepository.Template template) {
        requireBusiness();
        if (!repo.findRoleIds(template.key()).isEmpty()) throw new UserException(HttpStatus.CONFLICT, "This business already has the " + template.name() + " role.");
        UUID id = UUID.randomUUID();
        repo.insertRole(id, template.key(), template.name(), template.locked());
        copyTemplate(id, template);
        for (Map<String, Object> roleKey : repo.findRoleKeys()) {
            RoleTemplateRepository.Template other = templates.any((String) roleKey.get("key"));
            if (other != null && other.assignable().contains(template.key())) repo.addAssignableRole((UUID) roleKey.get("id"), id);
        }
        return id;
    }

    /** Replaces the role's permissions and assignable roles with the template's current ones. */
    public void syncRole(UUID roleId, RoleTemplateRepository.Template template) {
        requireBusiness();
        repo.deletePermissions(roleId);
        repo.deleteAssignableRoles(roleId);
        copyTemplate(roleId, template);
    }

    private void copyTemplate(UUID roleId, RoleTemplateRepository.Template template) {
        for (String p : template.permissions()) repo.insertPermission(roleId, p);
        for (String a : template.assignable()) repo.findRoleIds(a).stream().findFirst().ifPresent(target -> repo.addAssignableRole(roleId, target));
    }

    public record NewOwner(String name, String email, String designation) {}

    /**
     * Adds a sub admin (owner) to the current business: an employee with the owner role and portal access, and a
     * user row (they sign in with the emailed code). Returns the employee id.
     */
    public UUID addOwner(NewOwner newOwner, UUID createdBy) {
        requireBusiness();
        return tx.execute(s -> insertOwner(newOwner, createdBy)); // the code allocation lock holds until commit
    }

    private UUID insertOwner(NewOwner newOwner, UUID createdBy) {
        Map<String, Object> biz = repo.findCurrentPrefixAndZone();
        String prefix = (String) biz.get("employee_prefix");
        String timeZone = (String) biz.get("time_zone");
        UUID role = repo.findRoleId(OWNER);
        repo.lockCodeAllocation();
        Integer count = repo.findMaxCodeNumber(prefix);
        String code = prefix + "-" + String.format(Locale.ROOT, "%03d", (count == null ? 0 : count) + 1);
        UUID id = UUID.randomUUID();
        repo.insertOwnerEmployee(id, code, newOwner.name(), newOwner.email().trim().toLowerCase(Locale.ROOT), newOwner.designation() == null ? "Owner" : newOwner.designation(), role,
                LocalDate.now(ZoneId.of(timeZone)), timeZone, "Asia/Kolkata".equals(timeZone) ? "IN" : "US", createdBy);
        repo.insertUser(id);
        return id;
    }

    private static void requireBusiness() {
        if (TenantContext.current() == null) throw new IllegalStateException("Run inside a business (TenantContext).");
    }
}
