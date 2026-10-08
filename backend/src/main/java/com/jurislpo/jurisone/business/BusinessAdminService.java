package com.jurislpo.jurisone.business;

import com.jurislpo.jurisone.auth.AuthTokenService;
import com.jurislpo.jurisone.auth.SessionService;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.UserException;
import com.jurislpo.jurisone.crypto.Passwords;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.platform.AuditService;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The super admin's management of businesses and their sub admins (owners). Runs in system mode. */
@Service
public class BusinessAdminService {

    static final java.util.regex.Pattern GSTIN = java.util.regex.Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    public static final List<String> TIME_ZONES = List.of("Asia/Kolkata", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles");

    private final JdbcTemplate jdbc;
    private final BusinessSetupService setup;
    private final SubscriptionService subscriptions;
    private final ModuleGrantService grants;
    private final AuthTokenService tokens;
    private final SessionService sessions;
    private final AuditService audit;

    public BusinessAdminService(JdbcTemplate jdbc, BusinessSetupService setup, SubscriptionService subscriptions, ModuleGrantService grants,
                                AuthTokenService tokens, SessionService sessions, AuditService audit) {
        this.grants = grants;
        this.jdbc = jdbc;
        this.setup = setup;
        this.subscriptions = subscriptions;
        this.tokens = tokens;
        this.sessions = sessions;
        this.audit = audit;
    }

    // ───────────────────────── list / detail ─────────────────────────

    public record BusinessRow(String id, String name, String code, String employeePrefix, String status, long employees, long owners, String createdAt,
                              SubscriptionService.Current subscription) {}

    public List<BusinessRow> list() {
        return jdbc.query("""
            SELECT b.id, b.name, b.code, b.employee_prefix, b.status, b.created_at,
                   (SELECT count(*) FROM employees e WHERE e.business_id = b.id AND e.status <> 'EXITED') AS employees,
                   (SELECT count(*) FROM employees e JOIN roles r ON r.id = e.role_id WHERE e.business_id = b.id AND r.key = 'owner') AS owners
            FROM businesses b ORDER BY b.name""",
                (rs, i) -> new BusinessRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(7), rs.getLong(8),
                        Format.jsIso(rs.getTimestamp(6).toInstant()), subscriptions.current((UUID) rs.getObject(1))));
    }

    public record Owner(String id, String code, String name, String email, boolean access, boolean activated, String status) {}

    public Map<String, Object> detail(String idRaw) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id::text, name, legal_name, code, employee_prefix, gstin, address, contact_email, contact_phone, time_zone, status, created_at
            FROM businesses WHERE id = ?""", id);
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
        out.put("owners", jdbc.query("""
            SELECT e.id::text, e.code, e.full_name, e.email, e.portal_access, u.password_hash IS NOT NULL AS activated, e.status::text
            FROM employees e JOIN roles r ON r.id = e.role_id LEFT JOIN users u ON u.employee_id = e.id
            WHERE e.business_id = ? AND r.key = 'owner' ORDER BY e.full_name""",
                (rs, i) -> new Owner(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6), rs.getString(7)), id));
        out.put("subscription", subscriptions.current(id));
        out.put("subscriptions", subscriptions.history(id));
        out.put("modules", grants.list(id));
        return out;
    }

    // ───────────────────────── create / edit ─────────────────────────

    public record ProfileInput(String name, String legalName, String code, String employeePrefix, String timeZone, String contactEmail, String contactPhone,
                               String address, String gstin) {}

    public record OwnerInput(String name, String email, String password) {}

    public record CreateInput(ProfileInput business, SubscriptionService.SubscriptionInput subscription, OwnerInput owner) {}

    record ValidProfile(String name, String legalName, String code, String prefix, String timeZone, String contactEmail, String contactPhone, String address,
                        String gstin) {}

    static String opt(String v, int max, String field) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the " + field + " under " + max + " characters.");
        return t;
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
        List<UUID> clash = jdbc.queryForList("SELECT id FROM businesses WHERE code = ? AND (?::uuid IS NULL OR id <> ?::uuid)", UUID.class, code, editing, editing);
        if (!clash.isEmpty()) throw new UserException(HttpStatus.CONFLICT, "Another business already uses the short name \"" + code + "\".");
        if (editing != null) {
            String before = jdbc.queryForObject("SELECT employee_prefix FROM businesses WHERE id = ?", String.class, editing);
            long people = jdbc.queryForObject("SELECT count(*) FROM employees WHERE business_id = ?", Long.class, editing);
            if (!prefix.equals(before) && people > 0) throw new UserException("The employee ID prefix can't change once the business has people.");
        }
        return new ValidProfile(name, opt(in.legalName(), 160, "legal name"), code, prefix, tz, email == null ? null : email.toLowerCase(Locale.ROOT),
                opt(in.contactPhone(), 30, "phone"), opt(in.address(), 400, "address"), gstin);
    }

    /** Creates a business with its defaults, optionally its first subscription and first sub admin. */
    public Map<String, String> create(CurrentUser u, CreateInput in, String ip) {
        ValidProfile p = validate(in.business(), null);
        if (in.owner() != null) validateOwner(in.owner());
        UUID id = setup.create(new BusinessSetupService.NewBusiness(p.name(), p.legalName(), p.code(), p.prefix(), p.timeZone(), p.contactEmail(), p.contactPhone(),
                p.address(), p.gstin()), u.id());
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
        if (jdbc.queryForList("SELECT 1 FROM businesses WHERE id = ?", id).isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        ValidProfile p = validate(in, id);
        jdbc.update("""
            UPDATE businesses SET name = ?, legal_name = ?, code = ?, employee_prefix = ?, time_zone = ?, contact_email = ?, contact_phone = ?, address = ?,
              gstin = ?, updated_at = now() WHERE id = ?""",
                p.name(), p.legalName(), p.code(), p.prefix(), p.timeZone(), p.contactEmail(), p.contactPhone(), p.address(), p.gstin(), id);
        audit.log(u.id(), "business.update", "business", id.toString(), null, Map.of("name", p.name(), "code", p.code()), ip);
    }

    /** Suspend (everyone in the business is signed out and can't sign in) or reactivate. */
    public String setStatus(CurrentUser u, String idRaw, String statusRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        String status = Check.oneOf(statusRaw, List.of("ACTIVE", "SUSPENDED"));
        int n = jdbc.update("UPDATE businesses SET status = ?, updated_at = now() WHERE id = ?", status, id);
        if (n == 0) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        if ("SUSPENDED".equals(status)) {
            jdbc.update("UPDATE sessions SET revoked_at = now(), revoked_reason = 'business_suspended' WHERE revoked_at IS NULL "
                    + "AND employee_id IN (SELECT id FROM employees WHERE business_id = ?)", id);
        }
        audit.log(u.id(), "business.status", "business", id.toString(), null, Map.of("status", status), ip);
        return "SUSPENDED".equals(status) ? "Business suspended — everyone in it has been signed out" : "Business reactivated";
    }

    // ───────────────────────── sub admins ─────────────────────────

    void validateOwner(OwnerInput o) {
        Check.trimmed(o.name(), 2, "Enter the sub admin's name.", 120, "Keep the name under 120 characters.");
        String email = o.email() == null ? "" : o.email().trim().toLowerCase(Locale.ROOT);
        if (!PeopleRules.EMAIL_RE.matcher(email).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter a valid email for the sub admin.");
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT jurisone_email_in_use(?, NULL)", Boolean.class, email))) {
            throw new UserException("That email is already in use.");
        }
        if (o.password() != null && !o.password().isEmpty()) {
            String problem = Passwords.problem(o.password());
            if (problem != null) throw new UserException(HttpStatus.BAD_REQUEST, problem);
        }
    }

    /** Adds a sub admin: with a password they can sign in at once; without one they get an invite email. */
    public Map<String, String> addOwner(CurrentUser u, String businessIdRaw, OwnerInput o, String ip) {
        UUID business = Check.uuid(businessIdRaw);
        if (jdbc.queryForList("SELECT 1 FROM businesses WHERE id = ?", business).isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        validateOwner(o);
        String password = o.password() == null || o.password().isEmpty() ? null : o.password();
        UUID owner = TenantContext.call(business, () -> {
            subscriptions.checkRoomForOneMore();
            UUID id = setup.addOwner(new BusinessSetupService.NewOwner(o.name().trim(), o.email().trim(), password, null), u.id());
            if (password == null) tokens.sendInvite(id);
            return id;
        });
        String code = jdbc.queryForObject("SELECT code FROM employees WHERE id = ?", String.class, owner);
        audit.log(u.id(), "business.owner_add", "employee", owner.toString(), null, Map.of("business", business.toString(), "invited", password == null), ip);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("id", owner.toString());
        out.put("code", code);
        return out;
    }

    /** Turns a sub admin's portal access on or off (off ends their sessions). */
    public String setOwnerAccess(CurrentUser u, String businessIdRaw, String ownerIdRaw, Boolean on, String ip) {
        UUID business = Check.uuid(businessIdRaw);
        UUID owner = Check.uuid(ownerIdRaw);
        if (on == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean");
        int n = jdbc.update("""
            UPDATE employees e SET portal_access = ?, updated_at = now() FROM roles r
            WHERE r.id = e.role_id AND r.key = 'owner' AND e.id = ? AND e.business_id = ?""", on, owner, business);
        if (n == 0) throw new UserException(HttpStatus.NOT_FOUND, "Sub admin not found.");
        if (!on) sessions.revokeAll(owner, "access_disabled");
        audit.log(u.id(), "business.owner_access", "employee", owner.toString(), null, Map.of("portalAccess", on), ip);
        return on ? "Sub admin can sign in again" : "Sub admin's access turned off";
    }
}
