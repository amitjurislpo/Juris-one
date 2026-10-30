package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.config.BusinessSlugs;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The super admin's management of businesses and their sub admins (owners). Runs in system mode. */
@Service
public class BusinessAdminService {

    static final java.util.regex.Pattern GSTIN = java.util.regex.Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    public static final List<String> TIME_ZONES = List.of("Asia/Kolkata", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles");

    private static final String RETIRED = "That short name was used before and has been retired. It can never be used again.";

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
        Map<String, Object> view = new LinkedHashMap<>(rows.get(0));
        view.put("created_at", Format.isoTimestamp(((java.sql.Timestamp) view.get("created_at")).toInstant()));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", view.get("id"));
        profile.put("name", view.get("name"));
        profile.put("legalName", view.get("legal_name"));
        profile.put("code", view.get("code"));
        profile.put("employeePrefix", view.get("employee_prefix"));
        profile.put("gstin", view.get("gstin"));
        profile.put("address", view.get("address"));
        profile.put("contactEmail", view.get("contact_email"));
        profile.put("contactPhone", view.get("contact_phone"));
        profile.put("timeZone", view.get("time_zone"));
        profile.put("status", view.get("status"));
        profile.put("createdAt", view.get("created_at"));
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

    record ValidatedProfile(String name, String legalName, String code, String prefix, String timeZone, String contactEmail, String contactPhone, String address,
                        String gstin) {}

    static String optionalText(String value, int max, String field) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return null;
        if (text.length() > max) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the " + field + " under " + max + " characters.");
        return text;
    }

    /** Optional contact phone as "+<country code> <national number>"; stored normalised, e.g. "+91 8888788888". */
    static String phone(String value) {
        String phone = optionalText(value, 30, "phone");
        if (phone == null) return null;
        int spaceIndex = phone.indexOf(' ');
        String code = spaceIndex < 0 ? phone : phone.substring(0, spaceIndex);
        String problem = spaceIndex < 0 ? "Enter the phone as the country code, a space and the number, e.g. +91 8888788888." : Phones.problem(code, phone.substring(spaceIndex + 1));
        if (problem != null) throw new UserException(HttpStatus.BAD_REQUEST, problem);
        return code + " " + phone.substring(spaceIndex + 1).replaceAll("\\D", "");
    }

    /** The short name (slug) of a new business: the one given (validated, 409 when taken) or one derived from the name (never taken). */
    private String newCode(String given, String name) {
        if (given == null || given.isBlank()) return BusinessSlugs.derive(name, candidate -> !repo.findIdsByCode(candidate, null).isEmpty());
        String code = given.trim().toLowerCase(Locale.ROOT);
        String problem = BusinessSlugs.problem(code);
        if (problem != null) throw new UserException(HttpStatus.BAD_REQUEST, problem);
        if (!repo.findIdsByCode(code, null).isEmpty()) throw new UserException(HttpStatus.CONFLICT, "Another business already uses the short name \"" + code + "\".");
        if (repo.isSlugRetired(code)) throw new UserException(HttpStatus.CONFLICT, RETIRED);
        return code;
    }

    /** The short name never changes after creation (only a super admin's separate, audited rename may): blank or the same is fine, a different one is refused. */
    private String currentCode(String given, UUID editing) {
        String current = repo.findCode(editing);
        if (given != null && !given.isBlank() && !given.trim().toLowerCase(Locale.ROOT).equals(current)) {
            throw new UserException("The short name is the business's web address and can't be changed.");
        }
        return current;
    }

    ValidatedProfile validate(ProfileInput input, UUID editing) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Business details are missing.");
        String name = Check.trimmed(input.name(), 2, "Enter the business name.", 120, "Keep the name under 120 characters.");
        String code = editing == null ? newCode(input.code(), name) : currentCode(input.code(), editing);
        String prefix = input.employeePrefix() == null || input.employeePrefix().isBlank() ? BusinessSetupService.defaultPrefix(name)
                : input.employeePrefix().trim().toUpperCase(Locale.ROOT);
        if (!prefix.matches("^[A-Z]{2,5}$")) throw new UserException(HttpStatus.BAD_REQUEST, "The employee ID prefix is 2 to 5 letters, e.g. AB.");
        String timeZone = input.timeZone() == null || input.timeZone().isBlank() ? "Asia/Kolkata" : input.timeZone();
        if (!TIME_ZONES.contains(timeZone)) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a supported time zone.");
        ZoneId.of(timeZone);
        String email = optionalText(input.contactEmail(), 200, "contact email");
        if (email != null && !PeopleRules.EMAIL_RE.matcher(email).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter a valid contact email.");
        String gstin = optionalText(input.gstin(), 15, "GSTIN");
        if (gstin != null) {
            gstin = gstin.toUpperCase(Locale.ROOT);
            if (!GSTIN.matcher(gstin).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "GSTIN looks like 22AAAAA0000A1Z5.");
        }
        if (editing != null) {
            String before = repo.findEmployeePrefix(editing);
            long people = repo.countEmployees(editing);
            if (!prefix.equals(before) && people > 0) throw new UserException("The employee ID prefix can't change once the business has people.");
        }
        return new ValidatedProfile(name, optionalText(input.legalName(), 160, "legal name"), code, prefix, timeZone, email == null ? null : email.toLowerCase(Locale.ROOT),
                phone(input.contactPhone()), optionalText(input.address(), 400, "address"), gstin);
    }

    /** Creates a business with its defaults, optionally its first subscription and first sub admin. */
    public Map<String, String> create(CurrentUser user, CreateInput input, String ipAddress) {
        ValidatedProfile validated = validate(input.business(), null);
        if (input.owner() != null) validateOwner(input.owner());
        UUID id = setup.create(new BusinessSetupService.NewBusiness(validated.name(), validated.legalName(), validated.code(), validated.prefix(), validated.timeZone(), validated.contactEmail(), validated.contactPhone(),
                validated.address(), validated.gstin()), user.id(), input.roles());
        if (input.subscription() != null && input.subscription().planId() != null && !input.subscription().planId().isBlank()) {
            subscriptions.addSubscription(id, input.subscription(), user.id());
        }
        String ownerId = input.owner() == null ? null : addOwner(user, id.toString(), input.owner(), ipAddress).get("id");
        audit.log(user.id(), "business.create", "business", id.toString(), null, Map.of("name", validated.name(), "code", validated.code()), ipAddress);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        if (ownerId != null) out.put("ownerId", ownerId);
        return out;
    }

    public void update(CurrentUser user, String idRaw, ProfileInput input, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        if (!repo.customerBusinessExists(id)) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        ValidatedProfile validated = validate(input, id);
        repo.updateProfile(id, validated.name(), validated.legalName(), validated.prefix(), validated.timeZone(), validated.contactEmail(), validated.contactPhone(), validated.address(), validated.gstin());
        audit.log(user.id(), "business.update", "business", id.toString(), null, Map.of("name", validated.name(), "code", validated.code()), ipAddress);
    }

    /** Suspend (everyone in the business is signed out and can't sign in) or reactivate. */
    public String setStatus(CurrentUser user, String idRaw, String statusRaw, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        String status = Check.oneOf(statusRaw, List.of("ACTIVE", "SUSPENDED"));
        int count = repo.updateStatus(id, status);
        if (count == 0) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        if ("SUSPENDED".equals(status)) {
            repo.revokeSessionsOfBusiness(id, "business_suspended");
        }
        audit.log(user.id(), "business.status", "business", id.toString(), null, Map.of("status", status), ipAddress);
        return "SUSPENDED".equals(status) ? "Business suspended — everyone in it has been signed out" : "Business reactivated";
    }

    // ───────────────────────── web address (short name) change, super admin only ─────────────────────────

    public record SlugChange(String slug, String releasedAt, String releasedBy) {}

    /**
     * Changes a business's short name (its web address). The old one is retired for good, everybody of the business is signed out
     * and the change is audited, all in one transaction. 400 malformed or reserved, 404 unknown business, 409 taken by another
     * business or retired, 422 the same as now.
     */
    @Transactional
    public void changeSlug(CurrentUser user, String businessIdRaw, String slugRaw, String ipAddress) {
        UUID id = Check.uuid(businessIdRaw);
        String slug = slugRaw == null ? "" : slugRaw.trim().toLowerCase(Locale.ROOT);
        String problem = BusinessSlugs.problem(slug);
        if (problem != null) throw new UserException(HttpStatus.BAD_REQUEST, problem);
        String old = repo.lockCode(id);
        if (old == null) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        if (old.equals(slug)) throw new UserException("That is already this business's short name.");
        if (!repo.findIdsByCode(slug, id).isEmpty()) throw new UserException(HttpStatus.CONFLICT, "Another business already uses the short name \"" + slug + "\".");
        if (repo.isSlugRetired(slug)) throw new UserException(HttpStatus.CONFLICT, RETIRED);
        try {
            repo.retireSlug(old, id, user.id());
            repo.updateCode(id, slug);
        } catch (DuplicateKeyException e) {
            throw new UserException(HttpStatus.CONFLICT, "That short name was taken a moment ago. Choose another.");
        }
        repo.revokeSessionsOfBusiness(id, "web_address_changed");
        audit.log(user.id(), "business.slug.change", "business", id.toString(), Map.of("slug", old), Map.of("slug", slug, "retiredSlug", old), ipAddress);
    }

    /** The retired short names of a business, newest first. */
    public List<SlugChange> slugHistory(String businessIdRaw) {
        UUID id = customerBusiness(businessIdRaw);
        return repo.findSlugHistory(id).stream().map(row -> new SlugChange((String) row.get("slug"),
                Format.isoTimestamp(((java.sql.Timestamp) row.get("released_at")).toInstant()), row.get("released_by") == null ? null : row.get("released_by").toString())).toList();
    }

    // ───────────────────────── sub admins ─────────────────────────

    void validateOwner(OwnerInput input) {
        Check.trimmed(input.name(), 2, "Enter the sub admin's name.", 120, "Keep the name under 120 characters.");
        String email = input.email() == null ? "" : input.email().trim().toLowerCase(Locale.ROOT);
        if (!PeopleRules.EMAIL_RE.matcher(email).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter a valid email for the sub admin.");
        if (Boolean.TRUE.equals(repo.emailInUse(email))) {
            throw new UserException("That email is already in use.");
        }
    }

    /** Adds a sub admin; they sign in with the code emailed to them. */
    public Map<String, String> addOwner(CurrentUser user, String businessIdRaw, OwnerInput input, String ipAddress) {
        UUID business = Check.uuid(businessIdRaw);
        if (!repo.customerBusinessExists(business)) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        validateOwner(input);
        UUID owner = TenantContext.call(business, () -> {
            subscriptions.checkRoomForOneMore();
            return setup.addOwner(new BusinessSetupService.NewOwner(input.name().trim(), input.email().trim(), null), user.id());
        });
        String code = repo.findEmployeeCode(owner);
        audit.log(user.id(), "business.owner_add", "employee", owner.toString(), null, Map.of("business", business.toString()), ipAddress);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("id", owner.toString());
        out.put("code", code);
        return out;
    }

    // ───────────────────────── roles (from the super admin's role templates) ─────────────────────────

    public record RoleRow(String id, String key, String name, long employees, boolean mandatory) {}

    /** The business's roles; {@code mandatory}: the role's template is part of every business. */
    List<RoleRow> roles(UUID business) {
        return repo.findRoles(business).stream().map(role -> {
            var template = roleTemplates.any(role.key());
            return new RoleRow(role.id(), role.key(), role.name(), role.employees(), template != null && template.mandatory());
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
    public Map<String, String> addRole(CurrentUser user, String businessIdRaw, String templateKey, String ipAddress) {
        UUID business = customerBusiness(businessIdRaw);
        var template = roleTemplates.any(templateKey == null ? "" : templateKey);
        if (template == null || !template.active()) throw new UserException("Unknown or switched-off role: " + templateKey + ".");
        UUID id = TenantContext.call(business, () -> setup.addRole(template));
        audit.log(user.id(), "business.role_add", "role", id.toString(), null, Map.of("business", business.toString(), "template", template.key()), ipAddress);
        return Map.of("id", id.toString());
    }

    /** Takes a role away from a business: not a mandatory one, and only while nobody (past employees included) holds it. */
    public void removeRole(CurrentUser user, String businessIdRaw, String roleIdRaw, String ipAddress) {
        UUID business = customerBusiness(businessIdRaw);
        var role = roleOf(business, roleIdRaw);
        var template = roleTemplates.any(role.key());
        if (template != null && template.mandatory()) throw new UserException(role.name() + " is part of every business and can't be removed.");
        if (role.employees() > 0) throw new UserException("Move everyone (including past employees) out of " + role.name() + " first.");
        repo.deleteRole(business, UUID.fromString(role.id()));
        audit.log(user.id(), "business.role_remove", "role", role.id(), Map.of("business", business.toString(), "role", role.key()), null, ipAddress);
    }

    /** Copies the template's current permissions and assignable roles over the business's role. */
    public String syncRole(CurrentUser user, String businessIdRaw, String roleIdRaw, String ipAddress) {
        UUID business = customerBusiness(businessIdRaw);
        var role = roleOf(business, roleIdRaw);
        var template = roleTemplates.any(role.key());
        if (template == null) throw new UserException(role.name() + " isn't made from a role template, so there is nothing to copy.");
        TenantContext.run(business, () -> setup.syncRole(UUID.fromString(role.id()), template));
        audit.log(user.id(), "business.role_sync", "role", role.id(), null, Map.of("business", business.toString(), "template", template.key(), "permissions", template.permissions()), ipAddress);
        return role.name() + " now matches its template";
    }

    /** Turns a sub admin's portal access on or off (off ends their sessions). */
    public String setOwnerAccess(CurrentUser user, String businessIdRaw, String ownerIdRaw, Boolean enabled, String ipAddress) {
        UUID business = Check.uuid(businessIdRaw);
        UUID owner = Check.uuid(ownerIdRaw);
        if (enabled == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean");
        int count = repo.updateOwnerAccess(owner, business, enabled);
        if (count == 0) throw new UserException(HttpStatus.NOT_FOUND, "Sub admin not found.");
        if (!enabled) sessions.revokeAll(owner, "access_disabled");
        audit.log(user.id(), "business.owner_access", "employee", owner.toString(), null, Map.of("portalAccess", enabled), ipAddress);
        return enabled ? "Sub admin can sign in again" : "Sub admin's access turned off";
    }
}
