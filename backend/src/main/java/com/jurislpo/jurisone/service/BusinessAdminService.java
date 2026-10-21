package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.engine.Phones;
import com.jurislpo.jurisone.repository.BusinessAdminRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** The super admin's management of businesses and their sub admins (owners). Runs in system mode. */
@Service
public class BusinessAdminService {

    static final java.util.regex.Pattern GSTIN = java.util.regex.Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    public static final List<String> TIME_ZONES = List.of("Asia/Kolkata", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles");

    private final BusinessAdminRepository repo;
    private final BusinessSetupService setup;
    private final RoleTemplateService roleTemplates;
    private final SubscriptionService subscriptions;
    private final ModuleGrantService grants;
    private final SessionService sessions;
    private final AuditService audit;

    public BusinessAdminService(BusinessAdminRepository repo, BusinessSetupService setup, RoleTemplateService roleTemplates, SubscriptionService subscriptions,
                                ModuleGrantService grants, SessionService sessions, AuditService audit) {
        this.roleTemplates = roleTemplates;
        this.grants = grants;
        this.repo = repo;
        this.setup = setup;
        this.subscriptions = subscriptions;
        this.sessions = sessions;
        this.audit = audit;
    }

    // ───────────────────────── list / detail ─────────────────────────

    public record BusinessRow(String id, String name, String code, String employeePrefix, String status, long employees, long owners, String createdAt,
                              SubscriptionService.Current subscription) {}

    public List<BusinessRow> list() {
        return repo.findAll(subscriptions::current);
    }

    public record Owner(String id, String code, String name, String email, boolean access, String status) {}

    public Map<String, Object> detail(String idRaw) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = repo.findProfile(id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        Map<String, Object> b = new LinkedHashMap<>(rows.get(0));
        b.put("created_at", Format.jsIso(((java.sql.Timestamp) b.get("created_at")).toInstant()));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", b.get("id"));
        profile.put("name", b.get("name"));
        profile.put("legalName", b.get("legal_name"));
        profile.put("code", b.get("code"));
        profile.put("employeePrefix", b.get("employee_prefix"));
        profile.put("gstin", b.get("gstin"));
        profile.put("address", b.get("address"));
        profile.put("contactEmail", b.get("contact_email"));
        profile.put("contactPhone", b.get("contact_phone"));
        profile.put("timeZone", b.get("time_zone"));
        profile.put("status", b.get("status"));
        profile.put("createdAt", b.get("created_at"));
        out.put("business", profile);
        out.put("owners", repo.findOwners(id));
        out.put("roles", roles(id));
        out.put("subscription", subscriptions.current(id));
        out.put("subscriptions", subscriptions.history(id));
        out.put("modules", grants.list(id));
        return out;
    }

    // ───────────────────────── create / edit ─────────────────────────

    public record ProfileInput(String name, String legalName, String code, String employeePrefix, String timeZone, String contactEmail, String contactPhone,
                               String address, String gstin) {}

    public record OwnerInput(String name, String email) {}

    public record CreateInput(ProfileInput business, SubscriptionService.SubscriptionInput subscription, OwnerInput owner, List<String> roles) {}

    record ValidProfile(String name, String legalName, String code, String prefix, String timeZone, String contactEmail, String contactPhone, String address,
                        String gstin) {}

    static String opt(String v, int max, String field) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the " + field + " under " + max + " characters.");
        return t;
    }

    /** Optional contact phone as "+<country code> <national number>"; stored normalised, e.g. "+91 8888788888". */
    static String phone(String v) {
        String t = opt(v, 30, "phone");
        if (t == null) return null;
        int sp = t.indexOf(' ');
        String code = sp < 0 ? t : t.substring(0, sp);
        String problem = sp < 0 ? "Enter the phone as the country code, a space and the number, e.g. +91 8888788888." : Phones.problem(code, t.substring(sp + 1));
        if (problem != null) throw new UserException(HttpStatus.BAD_REQUEST, problem);
        return code + " " + t.substring(sp + 1).replaceAll("\\D", "");
    }

    ValidProfile validate(ProfileInput in, UUID editing) {
        if (in == null) throw new UserException(HttpStatus.BAD_REQUEST, "Business details are missing.");
        String name = Check.trimmed(in.name(), 2, "Enter the business name.", 120, "Keep the name under 120 characters.");
        String code = in.code() == null || in.code().isBlank() ? BusinessSetupService.slug(name) : in.code().trim().toLowerCase(Locale.ROOT);
        if (!code.matches("^[a-z0-9]+(-[a-z0-9]+)*$") || code.length() > 40) {
            throw new UserException(HttpStatus.BAD_REQUEST, "The short name uses lowercase letters, numbers and dashes (up to 40).");
        }
        String prefix = in.employeePrefix() == null || in.employeePrefix().isBlank() ? BusinessSetupService.defaultPrefix(name)
                : in.employeePrefix().trim().toUpperCase(Locale.ROOT);
        if (!prefix.matches("^[A-Z]{2,5}$")) throw new UserException(HttpStatus.BAD_REQUEST, "The employee ID prefix is 2 to 5 letters, e.g. AB.");
        String tz = in.timeZone() == null || in.timeZone().isBlank() ? "Asia/Kolkata" : in.timeZone();
        if (!TIME_ZONES.contains(tz)) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a supported time zone.");
        ZoneId.of(tz);
        String email = opt(in.contactEmail(), 200, "contact email");
        if (email != null && !PeopleRules.EMAIL_RE.matcher(email).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter a valid contact email.");
        String gstin = opt(in.gstin(), 15, "GSTIN");
        if (gstin != null) {
            gstin = gstin.toUpperCase(Locale.ROOT);
            if (!GSTIN.matcher(gstin).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "GSTIN looks like 22AAAAA0000A1Z5.");
        }
        List<UUID> clash = repo.findIdsByCode(code, editing);
        if (!clash.isEmpty()) throw new UserException(HttpStatus.CONFLICT, "Another business already uses the short name \"" + code + "\".");
        if (editing != null) {
            String before = repo.findEmployeePrefix(editing);
            long people = repo.countEmployees(editing);
            if (!prefix.equals(before) && people > 0) throw new UserException("The employee ID prefix can't change once the business has people.");
        }
        return new ValidProfile(name, opt(in.legalName(), 160, "legal name"), code, prefix, tz, email == null ? null : email.toLowerCase(Locale.ROOT),
                phone(in.contactPhone()), opt(in.address(), 400, "address"), gstin);
    }

    /** Creates a business with its defaults, optionally its first subscription and first sub admin. */
    public Map<String, String> create(CurrentUser u, CreateInput in, String ip) {
        ValidProfile p = validate(in.business(), null);
        if (in.owner() != null) validateOwner(in.owner());
        UUID id = setup.create(new BusinessSetupService.NewBusiness(p.name(), p.legalName(), p.code(), p.prefix(), p.timeZone(), p.contactEmail(), p.contactPhone(),
                p.address(), p.gstin()), u.id(), in.roles());
        if (in.subscription() != null && in.subscription().planId() != null && !in.subscription().planId().isBlank()) {
            subscriptions.addSubscription(id, in.subscription(), u.id());
        }
        String ownerId = in.owner() == null ? null : addOwner(u, id.toString(), in.owner(), ip).get("id");
        audit.log(u.id(), "business.create", "business", id.toString(), null, Map.of("name", p.name(), "code", p.code()), ip);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        if (ownerId != null) out.put("ownerId", ownerId);
        return out;
    }

    public void update(CurrentUser u, String idRaw, ProfileInput in, String ip) {
        UUID id = Check.uuid(idRaw);
        if (!repo.customerBusinessExists(id)) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        ValidProfile p = validate(in, id);
        repo.updateProfile(id, p.name(), p.legalName(), p.code(), p.prefix(), p.timeZone(), p.contactEmail(), p.contactPhone(), p.address(), p.gstin());
        audit.log(u.id(), "business.update", "business", id.toString(), null, Map.of("name", p.name(), "code", p.code()), ip);
    }

    /** Suspend (everyone in the business is signed out and can't sign in) or reactivate. */
    public String setStatus(CurrentUser u, String idRaw, String statusRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        String status = Check.oneOf(statusRaw, List.of("ACTIVE", "SUSPENDED"));
        int n = repo.updateStatus(id, status);
        if (n == 0) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        if ("SUSPENDED".equals(status)) {
            repo.revokeSessionsOfBusiness(id);
        }
        audit.log(u.id(), "business.status", "business", id.toString(), null, Map.of("status", status), ip);
        return "SUSPENDED".equals(status) ? "Business suspended — everyone in it has been signed out" : "Business reactivated";
    }

    // ───────────────────────── sub admins ─────────────────────────

    void validateOwner(OwnerInput o) {
        Check.trimmed(o.name(), 2, "Enter the sub admin's name.", 120, "Keep the name under 120 characters.");
        String email = o.email() == null ? "" : o.email().trim().toLowerCase(Locale.ROOT);
        if (!PeopleRules.EMAIL_RE.matcher(email).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter a valid email for the sub admin.");
        if (Boolean.TRUE.equals(repo.emailInUse(email))) {
            throw new UserException("That email is already in use.");
        }
    }

    /** Adds a sub admin; they sign in with the code emailed to them. */
    public Map<String, String> addOwner(CurrentUser u, String businessIdRaw, OwnerInput o, String ip) {
        UUID business = Check.uuid(businessIdRaw);
        if (!repo.customerBusinessExists(business)) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        validateOwner(o);
        UUID owner = TenantContext.call(business, () -> {
            subscriptions.checkRoomForOneMore();
            return setup.addOwner(new BusinessSetupService.NewOwner(o.name().trim(), o.email().trim(), null), u.id());
        });
        String code = repo.findEmployeeCode(owner);
        audit.log(u.id(), "business.owner_add", "employee", owner.toString(), null, Map.of("business", business.toString()), ip);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("id", owner.toString());
        out.put("code", code);
        return out;
    }

    // ───────────────────────── roles (from the super admin's role templates) ─────────────────────────

    public record RoleRow(String id, String key, String name, long employees, boolean mandatory) {}

    /** The business's roles; {@code mandatory}: the role's template is part of every business. */
    List<RoleRow> roles(UUID business) {
        return repo.findRoles(business).stream().map(r -> {
            var t = roleTemplates.any(r.key());
            return new RoleRow(r.id(), r.key(), r.name(), r.employees(), t != null && t.mandatory());
        }).toList();
    }

    private UUID customerBusiness(String idRaw) {
        UUID business = Check.uuid(idRaw);
        if (!repo.customerBusinessExists(business)) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        return business;
    }

    private BusinessAdminRepository.BusinessRole roleOf(UUID business, String roleIdRaw) {
        String id = Check.uuid(roleIdRaw).toString();
        return repo.findRoles(business).stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "That role isn't part of this business."));
    }

    /** Gives an existing business one more role from the catalogue. */
    public Map<String, String> addRole(CurrentUser u, String businessIdRaw, String templateKey, String ip) {
        UUID business = customerBusiness(businessIdRaw);
        var t = roleTemplates.any(templateKey == null ? "" : templateKey);
        if (t == null || !t.active()) throw new UserException("Unknown or switched-off role: " + templateKey + ".");
        UUID id = TenantContext.call(business, () -> setup.addRole(t));
        audit.log(u.id(), "business.role_add", "role", id.toString(), null, Map.of("business", business.toString(), "template", t.key()), ip);
        return Map.of("id", id.toString());
    }

    /** Takes a role away from a business: not a mandatory one, and only while nobody (past employees included) holds it. */
    public void removeRole(CurrentUser u, String businessIdRaw, String roleIdRaw, String ip) {
        UUID business = customerBusiness(businessIdRaw);
        var role = roleOf(business, roleIdRaw);
        var t = roleTemplates.any(role.key());
        if (t != null && t.mandatory()) throw new UserException(role.name() + " is part of every business and can't be removed.");
        if (role.employees() > 0) throw new UserException("Move everyone (including past employees) out of " + role.name() + " first.");
        repo.deleteRole(business, UUID.fromString(role.id()));
        audit.log(u.id(), "business.role_remove", "role", role.id(), Map.of("business", business.toString(), "role", role.key()), null, ip);
    }

    /** Copies the template's current permissions and assignable roles over the business's role. */
    public String syncRole(CurrentUser u, String businessIdRaw, String roleIdRaw, String ip) {
        UUID business = customerBusiness(businessIdRaw);
        var role = roleOf(business, roleIdRaw);
        var t = roleTemplates.any(role.key());
        if (t == null) throw new UserException(role.name() + " isn't made from a role template, so there is nothing to copy.");
        TenantContext.run(business, () -> setup.syncRole(UUID.fromString(role.id()), t));
        audit.log(u.id(), "business.role_sync", "role", role.id(), null, Map.of("business", business.toString(), "template", t.key(), "permissions", t.permissions()), ip);
        return role.name() + " now matches its template";
    }

    /** Turns a sub admin's portal access on or off (off ends their sessions). */
    public String setOwnerAccess(CurrentUser u, String businessIdRaw, String ownerIdRaw, Boolean on, String ip) {
        UUID business = Check.uuid(businessIdRaw);
        UUID owner = Check.uuid(ownerIdRaw);
        if (on == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean");
        int n = repo.updateOwnerAccess(owner, business, on);
        if (n == 0) throw new UserException(HttpStatus.NOT_FOUND, "Sub admin not found.");
        if (!on) sessions.revokeAll(owner, "access_disabled");
        audit.log(u.id(), "business.owner_access", "employee", owner.toString(), null, Map.of("portalAccess", on), ip);
        return on ? "Sub admin can sign in again" : "Sub admin's access turned off";
    }
}
