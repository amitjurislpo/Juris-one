package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.repository.PeopleQuery;
import com.jurislpo.jurisone.repository.PeopleRepository;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.crypto.Masks;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.engine.Csv;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.engine.PeopleRules.Actor;
import com.jurislpo.jurisone.engine.PeopleRules.EmployeeInput;
import com.jurislpo.jurisone.engine.PeopleRules.Location;
import com.jurislpo.jurisone.engine.PeopleRules.Target;
import com.jurislpo.jurisone.engine.Shifts;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** People records (REQUIREMENTS §5.2; src/app/(app)/people). HR never receives salary; PII travels masked. */
@Service
public class PeopleService {

    static final String IST = "Asia/Kolkata";

    private final PeopleRepository repo;
    private final TransactionTemplate tx;
    private final ActorService actors;
    private final AuditService audit;
    private final PiiCrypto pii;
    private final SessionService sessions;
    private final AuthTokenService tokens;
    private final com.jurislpo.jurisone.service.SubscriptionService subscriptions;

    public PeopleService(PeopleRepository repo, TransactionTemplate tx, ActorService actors, AuditService audit, PiiCrypto pii, SessionService sessions,
                         AuthTokenService tokens, com.jurislpo.jurisone.service.SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
        this.repo = repo;
        this.tx = tx;
        this.actors = actors;
        this.audit = audit;
        this.pii = pii;
        this.sessions = sessions;
        this.tokens = tokens;
    }

    // ───────────────────────── list ─────────────────────────

    public record PersonRow(String id, String code, String name, String title, String dept, String manager, String loc, boolean india, String role,
                            String status, boolean access, String doj, boolean canToggle) {}

    public record Dept(String id, String name) {}

    public record Page(List<PersonRow> rows, int total, int page, int take, List<Dept> depts, Map<String, Integer> counts, boolean seePay, boolean canAdd) {}

    public Page page(CurrentUser u, Map<String, String> sp) {
        PeopleQuery pq = new PeopleQuery(sp, u.id());
        Actor actor = actors.actor(u);
        List<PersonRow> rows = repo.findPage(pq, t -> PeopleRules.canToggleAccess(actor, t));
        Integer total = repo.count(pq);
        List<Dept> depts = repo.findActiveDepartments();
        Map<String, Integer> k = repo.countTabs(u.id());
        return new Page(rows, total == null ? 0 : total, pq.ls.page(), pq.ls.take(), depts, k, u.perms().contains("struct.manage"), PeopleRules.canAddEmployees(actor));
    }

    // ───────────────────────── directory (dir, everyone) ─────────────────────────

    public record DirRow(String id, String name, String email, String title, String status) {}

    public record Directory(List<DirRow> rows, int total, int page, int take, int nActive, List<Dept> depts) {}

    public Directory directory(Map<String, String> sp) {
        com.jurislpo.jurisone.common.ListParams ls = com.jurislpo.jurisone.common.ListParams.of(sp, List.of("name", "email", "title", "st"), "name");
        String st = com.jurislpo.jurisone.common.ListParams.pick(sp, "st", List.of("All", "Active", "Past"), "All");
        String dept = com.jurislpo.jurisone.common.ListParams.str(sp, "dept", "All");
        List<String> and = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (!"All".equals(st)) and.add("Past".equals(st) ? "status = 'EXITED'" : "status <> 'EXITED'");
        if (!"All".equals(dept) && dept.matches("^[0-9a-f-]{36}$")) {
            and.add("department_id = ?::uuid");
            args.add(dept);
        }
        if (!ls.q().isEmpty()) {
            and.add("(full_name ILIKE ? OR email ILIKE ? OR designation ILIKE ?)");
            for (int i = 0; i < 3; i++) args.add(PeopleQuery.like(ls.q()));
        }
        String where = and.isEmpty() ? "" : " WHERE " + String.join(" AND ", and);
        String d = ls.asc() ? "ASC" : "DESC";
        String order = switch (ls.sort()) {
            case "email" -> "email " + d;
            case "title" -> "designation " + d + ", full_name ASC";
            case "st" -> "status " + d + ", full_name ASC";
            default -> "full_name " + d;
        };
        List<DirRow> rows = repo.findDirectoryPage(where, order, args, ls.take(), ls.skip());
        Integer total = repo.countDirectory(where, args);
        Integer active = repo.countCurrent();
        List<Dept> depts = repo.findActiveDepartments();
        return new Directory(rows, total == null ? 0 : total, ls.page(), ls.take(), active == null ? 0 : active, depts);
    }

    // ───────────────────────── profile drawer ─────────────────────────

    public record Profile(String id, String code, String name, String father, String email, String phone, String address, String pan, String aadhaar,
                          String uan, String designation, String department, String manager, List<String> reports, String doj, String exit, String location,
                          boolean india, String shift, String role, String status, boolean access, boolean invitePending, Long gross, boolean canEdit,
                          boolean canToggle) {}

    static String maskAddress(String a) {
        if (a == null || a.isEmpty()) return "—";
        List<String> parts = Arrays.stream(a.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return parts.size() > 1 ? "•••••, " + parts.get(parts.size() - 1) : "On file";
    }

    public Profile profile(CurrentUser u, String idRaw) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rs = repo.findProfile(id);
        if (rs.isEmpty()) throw new UserException("That employee no longer exists.");
        Map<String, Object> e = rs.get(0);
        Actor actor = actors.actor(u);
        EmployeeStatus status = EmployeeStatus.valueOf((String) e.get("status_s"));
        Target target = new Target(id.toString(), e.get("role_id").toString(), (String) e.get("role_key"), status, Boolean.TRUE.equals(e.get("is_super_admin")));

        // Salary is fetched only for struct.manage — HR never receives it.
        Long gross = null;
        if (u.perms().contains("struct.manage")) {
            List<Long> g = repo.findGross(id, Date.valueOf(Format.todayIn(IST)));
            gross = g.isEmpty() ? 0L : g.get(0);
        }
        boolean india = "IN".equals(e.get("region_s"));
        List<String> reports = repo.findCurrentReportNames(id);
        String shift = e.get("shift_code") == null ? "—" : Shifts.label((String) e.get("shift_code"), (String) e.get("start_local"), (String) e.get("end_local"));
        Date exit = (Date) e.get("exit_date");
        return new Profile(id.toString(), (String) e.get("code"), (String) e.get("full_name"), e.get("father_name") == null ? "—" : (String) e.get("father_name"),
                (String) e.get("email"), Masks.phone((String) e.get("phone_last4")), maskAddress(pii.decrypt((String) e.get("address_enc"))),
                india ? Masks.pan((String) e.get("pan_last4")) : "—", india ? Masks.aadhaar((String) e.get("aadhaar_last4")) : "—",
                india ? Masks.uan((String) e.get("uan_last4")) : "—", (String) e.get("designation"), e.get("dept") == null ? "—" : (String) e.get("dept"),
                e.get("manager") == null ? "—" : (String) e.get("manager"), reports, ((Date) e.get("date_of_joining")).toLocalDate().toString(),
                exit == null ? null : exit.toLocalDate().toString(), PeopleRules.locationOfTz((String) e.get("time_zone")).l(), india, shift,
                (String) e.get("role_name"), status.name(), Boolean.TRUE.equals(e.get("portal_access")), e.get("password_hash") == null, gross,
                PeopleRules.canEditEmployee(actor, target) && status != EmployeeStatus.EXITED, PeopleRules.canToggleAccess(actor, target));
    }

    /** Full phone + address for people who may edit the record. Every reveal is audited. */
    public Map<String, String> reveal(CurrentUser u, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Target t = actors.target(id);
        if (t == null || !PeopleRules.canEditEmployee(actors.actor(u), t)) throw new UserException("You can't view this person's contact details.");
        Map<String, Object> e = repo.findContactEnc(id);
        audit.log(u.id(), "employee.pii_reveal", "employee", id.toString(), null, Map.of("fields", List.of("phone", "address")), ip);
        String phone = pii.decrypt((String) e.get("phone_enc")), address = pii.decrypt((String) e.get("address_enc"));
        return Map.of("phone", phone == null ? "—" : phone, "address", address == null ? "—" : address);
    }

    // ───────────────────────── access & exit ─────────────────────────

    public String toggleAccess(CurrentUser u, String idRaw, boolean on, String ip) {
        UUID id = Check.uuid(idRaw);
        Target t = actors.target(id);
        if (t == null || !PeopleRules.canToggleAccess(actors.actor(u), t)) throw new UserException("You can't change portal access for this person.");
        Map<String, Object> e = repo.findNameAndAccess(id);
        tx.executeWithoutResult(s -> {
            repo.setPortalAccess(id, on);
            if (!on) sessions.revokeAll(id, "access_disabled");
            audit.log(u.id(), "employee.access_toggle", "employee", id.toString(), Map.of("portalAccess", e.get("portal_access")), Map.of("portalAccess", on), ip);
        });
        String name = (String) e.get("full_name");
        // someone who has never set a password (e.g. added from onboarding) gets the invite on their work email now
        boolean invite = on && repo.hasNoPassword(id);
        if (invite) tokens.sendInvite(id);
        return on ? "Portal access enabled for " + name + (invite ? " · invite sent to their work email" : "") : name + " can no longer sign in";
    }

    public String markExited(CurrentUser u, String idRaw, String exitDate, String ip) {
        UUID id = Check.uuid(idRaw);
        if (exitDate == null || !exitDate.matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new UserException("Pick the last working day.");
        Target t = actors.target(id);
        if (t == null || !PeopleRules.canEditEmployee(actors.actor(u), t)) throw new UserException("You can't change this person's record.");
        if (t.status() == EmployeeStatus.EXITED) throw new UserException("Already a past employee.");
        Map<String, Object> e = repo.findForExit(id);
        if (exitDate.compareTo(((Date) e.get("date_of_joining")).toLocalDate().toString()) < 0) {
            throw new UserException("The last working day can't be before the joining date.");
        }
        long reports = ((Number) e.get("reports")).longValue();
        if (reports > 0) {
            throw new UserException(e.get("full_name") + " still has " + reports + " direct report" + (reports == 1 ? "" : "s") + ". Assign them a new manager first.");
        }
        tx.executeWithoutResult(s -> {
            repo.markExited(id, Date.valueOf(exitDate));
            repo.clearDepartmentHead(id);
            sessions.revokeAll(id, "exited");
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("status", e.get("status"));
            before.put("portalAccess", e.get("portal_access"));
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", "EXITED");
            after.put("exitDate", exitDate);
            after.put("portalAccess", false);
            audit.log(u.id(), "employee.exit", "employee", id.toString(), before, after, ip);
        });
        return e.get("full_name") + " marked as a past employee · access removed";
    }

    public String resendInvite(CurrentUser u, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Target t = actors.target(id);
        if (t == null || !PeopleRules.canEditEmployee(actors.actor(u), t) || t.status() == EmployeeStatus.EXITED) {
            throw new UserException("You can't send an invite for this person.");
        }
        List<String> hash = repo.findPasswordHash(id);
        if (!hash.isEmpty() && hash.get(0) != null) throw new UserException("This person has already set a password.");
        tokens.sendInvite(id);
        audit.log(u.id(), "employee.invite_sent", "employee", id.toString(), ip);
        return "Invite sent to their official email";
    }

    // ───────────────────────── add / edit ─────────────────────────

    public record DeptOption(String id, String name, String headId) {}

    public record Manager(String id, String name, String title) {}

    public record RoleOption(String id, String name, int perms) {}

    public record Last4(String phone, String pan, String aadhaar, String uan) {}

    public record Editing(String code, String name, Last4 last4, boolean hasAddress, boolean hadManager) {}

    public record FormOptions(String nextCode, List<DeptOption> departments, String lockedDepartmentId, List<Manager> managers, List<RoleOption> roles,
                              EmployeeInput defaults, Editing editing) {}

    /** Next employee ID in this business: its prefix plus the next number, e.g. AB-007 (row-level security scopes the max). */
    String nextCode() {
        String prefix = repo.findEmployeePrefix();
        Integer n = repo.findMaxCodeNumber(prefix);
        return prefix + "-" + String.format(java.util.Locale.ROOT, "%03d", (n == null ? 0 : n) + 1);
    }

    private Map<String, Object> editable(Actor actor, UUID editId) {
        Target t = actors.target(editId);
        if (t == null || !PeopleRules.canEditEmployee(actor, t) || t.status() == EmployeeStatus.EXITED) throw new UserException("You can't edit this person.");
        return repo.findEditable(editId);
    }

    public record RoleRow(String id, String key, String name, int perms) {}

    private boolean roleAllowed(Actor actor, RoleRow r, String currentRoleId) {
        return PeopleRules.canAssignRole(actor, r.id(), currentRoleId) || (PeopleRules.isAdmin(actor) && !PeopleRules.isAdminRole(r.key())) || r.id().equals(currentRoleId);
    }

    public FormOptions formOptions(CurrentUser u, String editIdRaw) {
        Actor actor = actors.actor(u);
        Map<String, Object> current = null;
        UUID editId = null;
        if (editIdRaw != null && !editIdRaw.isEmpty()) {
            editId = Check.uuid(editIdRaw);
            current = editable(actor, editId);
        } else if (!PeopleRules.canAddEmployees(actor)) {
            throw new UserException("You can't add employees.");
        }
        String locked = PeopleRules.lockedDepartment(actor);
        List<DeptOption> departments = locked == null
                ? repo.findDepartmentOptions()
                : repo.findDepartmentOption(locked);
        List<Manager> managers = repo.findManagerOptions(editId);
        List<RoleRow> roles = repo.findRoles();
        String currentRole = current == null ? null : current.get("role_id").toString();
        List<RoleRow> allowed = roles.stream().filter(r -> roleAllowed(actor, r, currentRole)).toList();
        RoleRow employeeRole = roles.stream().filter(r -> "employee".equals(r.key())).findFirst().orElse(null);
        Map<String, String> shiftCodes = repo.findShiftCodesById();

        EmployeeInput defaults;
        if (current != null) {
            Object sid = current.get("shift_id");
            String shift = sid != null && shiftCodes.containsKey(sid.toString()) ? shiftCodes.get(sid.toString()) : ("US".equals(current.get("region_s")) ? "US_DAY" : "IN_NIGHT");
            String[] names = names(current);
            defaults = new EmployeeInput(names[0], names[1], names[2], current.get("father_name") == null ? "" : (String) current.get("father_name"),
                    (String) current.get("email"), current.get("phone_country") == null ? "+91" : (String) current.get("phone_country"), "",
                    PeopleRules.locationOfTz((String) current.get("time_zone")).v(), current.get("joining_office") == null ? "" : (String) current.get("joining_office"),
                    "", "", "", "", (String) current.get("designation"), current.get("department_id") == null ? "" : current.get("department_id").toString(),
                    current.get("reporting_manager_id") == null ? "" : current.get("reporting_manager_id").toString(),
                    ((Date) current.get("date_of_joining")).toLocalDate().toString(), shift, currentRole);
        } else {
            DeptOption dept = locked != null ? (departments.isEmpty() ? null : departments.get(0))
                    : departments.stream().filter(d -> !List.of("Human Resources", "Finance").contains(d.name())).findFirst().orElse(departments.isEmpty() ? null : departments.get(0));
            RoleRow role = allowed.stream().filter(r -> employeeRole != null && r.id().equals(employeeRole.id())).findFirst().orElse(allowed.isEmpty() ? null : allowed.get(0));
            String manager = locked != null ? u.id().toString() : dept == null ? null : dept.headId();
            defaults = new EmployeeInput("", "", "", "", "", "+91", "", "", "", "", "", "", "", "", dept == null ? "" : dept.id(), manager == null ? "" : manager,
                    Format.todayIn(IST).toString(), "IN_NIGHT", role == null ? "" : role.id());
        }
        Editing editing = current == null ? null : new Editing((String) current.get("code"), (String) current.get("full_name"),
                new Last4((String) current.get("phone_last4"), (String) current.get("pan_last4"), (String) current.get("aadhaar_last4"), (String) current.get("uan_last4")),
                current.get("address_enc") != null, current.get("reporting_manager_id") != null);
        return new FormOptions(current != null ? (String) current.get("code") : nextCode(), departments, locked, managers,
                allowed.stream().map(r -> new RoleOption(r.id(), r.name(), r.perms())).toList(), defaults, editing);
    }

    /** First / middle / last name of an employee row (split from full_name for people saved before the name fields). */
    static String[] names(Map<String, Object> row) {
        if (row.get("first_name") != null) {
            return new String[] {(String) row.get("first_name"), row.get("middle_name") == null ? "" : (String) row.get("middle_name"),
                    row.get("last_name") == null ? "" : (String) row.get("last_name")};
        }
        String[] w = ((String) row.get("full_name")).trim().split("\\s+");
        if (w.length == 1) return new String[] {w[0], "", ""};
        return new String[] {w[0], String.join(" ", java.util.Arrays.copyOfRange(w, 1, w.length - 1)), w[w.length - 1]};
    }

    /** employeeInput (zod): trims, lower-cases email, upper-cases PAN; first failing field wins, in schema order. */
    static EmployeeInput parse(EmployeeInput in) {
        java.util.function.BiFunction<String, String, String> s = (v, f) -> v == null ? "" : v.trim();
        String first = s.apply(in.firstName(), "firstName");
        if (first.isEmpty()) throw bad("First name is required");
        String middle = s.apply(in.middleName(), "middleName");
        String last = s.apply(in.lastName(), "lastName");
        if (last.isEmpty()) throw bad("Last name is required");
        for (String n : List.of(first, middle, last)) if (n.length() > 60) throw bad("Keep each name under 60 characters");
        String father = s.apply(in.fatherName(), "fatherName");
        if (father.length() < 2) throw bad("Father’s name is required");
        if (father.length() > 120) throw bad("Too big: expected string to have <=120 characters");
        String email = s.apply(in.email(), "email").toLowerCase();
        if (email.length() > 200) throw bad("Too big: expected string to have <=200 characters");
        if (!PeopleRules.EMAIL_RE.matcher(email).matches()) throw bad("Enter a valid email");
        String phoneCountry = s.apply(in.phoneCountry(), "phoneCountry");
        if (phoneCountry.isEmpty()) phoneCountry = "+91";
        if (com.jurislpo.jurisone.engine.Phones.country(phoneCountry).isEmpty()) throw bad("Choose the country code");
        String phone = PeopleRules.digits(s.apply(in.phone(), "phone"));
        if (phone.length() > 15) throw bad("Too big: expected string to have <=15 digits");
        String location = s.apply(in.location(), "location");
        if (location.isEmpty()) location = "IN_IST"; // not chosen: India
        location = Check.oneOf(location, PeopleRules.LOCATIONS.stream().map(Location::v).toList());
        String office = s.apply(in.joiningOffice(), "joiningOffice");
        if (office.length() > 120) throw bad("Keep the joining office under 120 characters");
        String pan = s.apply(in.pan(), "pan").toUpperCase();
        if (pan.length() > 10) throw bad("Too big: expected string to have <=10 characters");
        String aadhaar = s.apply(in.aadhaar(), "aadhaar");
        if (aadhaar.length() > 20) throw bad("Too big: expected string to have <=20 characters");
        String uan = s.apply(in.uan(), "uan");
        if (uan.length() > 20) throw bad("Too big: expected string to have <=20 characters");
        String address = s.apply(in.address(), "address");
        if (address.length() > 500) throw bad("Too big: expected string to have <=500 characters");
        String designation = s.apply(in.designation(), "designation");
        if (designation.length() < 2) throw bad("Designation is required");
        if (designation.length() > 120) throw bad("Too big: expected string to have <=120 characters");
        String departmentId = Check.uuid(in.departmentId(), "Pick a department").toString();
        String managerId = in.managerId() == null ? "" : in.managerId();
        if (!managerId.isEmpty() && !PeopleRules.UUID_RE.matcher(managerId).matches()) throw bad("Invalid input");
        String doj = Check.isoDate(in.doj(), "Pick the date of joining");
        String shift = in.shift() == null || in.shift().isBlank() ? ("IN_IST".equals(location) ? "IN_NIGHT" : "US_DAY") : Check.oneOf(in.shift(), List.of("IN_NIGHT", "IN_DAY", "US_DAY"));
        String roleId = Check.uuid(in.roleId(), "Pick a role").toString();
        return new EmployeeInput(first, middle, last, father, email, phoneCountry, phone, location, office, pan, aadhaar, uan, address, designation, departmentId,
                managerId, doj, shift, roleId);
    }

    private static UserException bad(String m) {
        return new UserException(HttpStatus.BAD_REQUEST, m);
    }

    public record Saved(String id, String code, String msg) {}

    public Saved save(CurrentUser u, EmployeeInput input, String editIdRaw, String ip) {
        return save(u, input, editIdRaw, ip, true);
    }

    /** Adds an employee from a verified onboarding: portal access off and no invite yet (HR turns access on later). */
    public Saved addWithoutAccess(CurrentUser u, EmployeeInput input, String ip) {
        return save(u, input, null, ip, false);
    }

    private Saved save(CurrentUser u, EmployeeInput input, String editIdRaw, String ip, boolean access) {
        EmployeeInput f = parse(input);
        Actor actor = actors.actor(u);
        boolean editing = editIdRaw != null && !editIdRaw.isEmpty();
        Map<String, Object> current = null;
        UUID editId = null;
        if (editing) {
            editId = Check.uuid(editIdRaw);
            current = editable(actor, editId);
        } else if (!PeopleRules.canAddEmployees(actor)) {
            throw new UserException("You can't add employees.");
        }
        boolean hadManager = current != null && current.get("reporting_manager_id") != null;
        for (int step = 1; step <= 3; step++) {
            Map<String, String> errs = PeopleRules.stepErrors(step, f, editing, hadManager);
            if (!errs.isEmpty()) throw new UserException(errs.values().iterator().next());
        }

        // Role: only from the creator's assignable list (or unchanged on edit).
        List<Map<String, Object>> roleRows = repo.findRole(f.roleId());
        if (roleRows.isEmpty()) throw new UserException("Pick a role.");
        Map<String, Object> role = roleRows.get(0);
        String currentRole = current == null ? null : current.get("role_id").toString();
        boolean ok = PeopleRules.canAssignRole(actor, f.roleId(), currentRole) || (PeopleRules.isAdmin(actor) && !PeopleRules.isAdminRole((String) role.get("key")));
        if (!ok) throw new UserException("You can't give people the " + role.get("name") + " role.");

        // Department: must exist; locked to the actor's own department where the rule applies.
        List<Map<String, Object>> dept = repo.findDepartmentArchivedAt(f.departmentId());
        if (dept.isEmpty() || dept.get(0).get("archived_at") != null) throw new UserException("Pick a department.");
        String locked = PeopleRules.lockedDepartment(actor);
        if (locked != null && !f.departmentId().equals(locked)) throw new UserException("You can add people to your own department only.");

        // Manager: current employee, not themselves, no reporting loops.
        if (!f.managerId().isEmpty()) {
            List<String> ms = repo.findStatus(f.managerId());
            if (ms.isEmpty() || "EXITED".equals(ms.get(0))) throw new UserException("Pick a current employee as reporting manager.");
            if (editId != null) {
                if (f.managerId().equals(editId.toString())) throw new UserException("Someone can't report to themselves.");
                Map<String, String> managerOf = repo.findManagerByEmployee();
                if (PeopleRules.createsCycle(editId.toString(), f.managerId(), managerOf)) {
                    throw new UserException("That manager already reports (directly or indirectly) to this person.");
                }
            }
        }

        String email = f.email().toLowerCase();
        List<String> clash = editId == null
                ? repo.findCodeByEmail(email)
                : repo.findCodeByEmailExcept(email, editId);
        if (!clash.isEmpty()) throw new UserException(clash.get(0) + " already uses that email.");
        // the email is the sign-in, so it must also be free in every other business (which is not named)
        if (Boolean.TRUE.equals(repo.emailInUse(email, editId == null ? null : UUID.fromString(editId.toString())))) {
            throw new UserException("That email is already in use.");
        }

        Location loc = PeopleRules.location(f.locationOrDefault());
        boolean india = "IN".equals(loc.region());
        String shiftCode = india ? ("US_DAY".equals(f.shift()) ? "IN_NIGHT" : f.shift()) : "US_DAY";
        UUID shiftId = repo.findShiftId(shiftCode);

        // PII: on edit, blank means keep; switching to the US clears the Indian IDs. Insertion order = the TS object.
        Map<String, String> piiCols = new LinkedHashMap<>();
        java.util.function.BiConsumer<String, String> setPII = (field, value) -> {
            piiCols.put(field + "Enc", pii.encrypt(value));
            piiCols.put(field + "Last4", PiiCrypto.last4(value));
        };
        if (!f.phone().isEmpty()) {
            setPII.accept("phone", f.phone());
            piiCols.put("phoneCountry", f.phoneCountry());
        }
        if (!f.address().isEmpty()) piiCols.put("addressEnc", pii.encrypt(f.address()));
        if (india) {
            if (!f.pan().isEmpty()) setPII.accept("pan", f.pan().toUpperCase());
            if (!f.aadhaar().isEmpty()) setPII.accept("aadhaar", PeopleRules.digits(f.aadhaar()));
            if (!f.uan().isEmpty()) setPII.accept("uan", PeopleRules.digits(f.uan()));
        } else {
            for (String k : List.of("pan", "aadhaar", "uan")) {
                piiCols.put(k + "Enc", null);
                piiCols.put(k + "Last4", null);
            }
        }
        List<String> changedPII = piiCols.keySet().stream().filter(k -> k.endsWith("Enc")).map(k -> k.replace("Enc", "")).toList();
        String fullName = f.fullName();
        String managerId = f.managerId().isEmpty() ? null : f.managerId();
        LocalDate today = Format.todayIn(IST);

        if (current != null) {
            Map<String, Object> cur = current;
            UUID id = editId;
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("fullName", cur.get("full_name"));
            before.put("fatherName", cur.get("father_name"));
            before.put("email", cur.get("email"));
            before.put("designation", cur.get("designation"));
            before.put("departmentId", str(cur.get("department_id")));
            before.put("reportingManagerId", str(cur.get("reporting_manager_id")));
            before.put("roleId", str(cur.get("role_id")));
            before.put("dateOfJoining", ((Date) cur.get("date_of_joining")).toLocalDate().toString());
            before.put("timeZone", cur.get("time_zone"));
            before.put("shiftId", str(cur.get("shift_id")));
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("fullName", fullName);
            after.put("fatherName", f.fatherName());
            after.put("email", email);
            after.put("designation", f.designation());
            after.put("departmentId", f.departmentId());
            after.put("reportingManagerId", managerId);
            after.put("roleId", f.roleId());
            after.put("dateOfJoining", f.doj());
            after.put("timeZone", loc.tz());
            after.put("shiftId", shiftId.toString());
            after.put("piiChanged", changedPII);
            String status = (String) cur.get("status_s");
            String newStatus = "ONBOARDING".equals(status) || "PROBATION".equals(status)
                    ? PeopleRules.initialStatus(LocalDate.parse(f.doj()), today).name() : status;
            tx.executeWithoutResult(s -> {
                repo.updateEmployee(id, fullName, f.firstName(), f.middleName().isEmpty() ? null : f.middleName(), f.lastName(),
                        f.joiningOffice().isEmpty() ? null : f.joiningOffice(), f.fatherName(), email, f.designation(), f.departmentId(), managerId, f.roleId(), Date.valueOf(f.doj()),
                        loc.tz(), loc.region(), shiftId, newStatus);
                repo.updatePii(id, piiCols);
                audit.log(u.id(), "employee.edit", "employee", id.toString(), before, after, ip);
            });
            return new Saved(id.toString(), (String) cur.get("code"), "Saved changes for " + fullName);
        }

        String[] codeOut = new String[1];
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            // serialise code allocation
            repo.lockCodeAllocation(); // one allocation at a time per business
            subscriptions.checkRoomForOneMore(); // the plan's employee limit (under the same lock, so two adds can't both slip past)
            String code = nextCode();
            codeOut[0] = code;
            repo.insertEmployee(id, code, fullName, f.firstName(), f.middleName().isEmpty() ? null : f.middleName(), f.lastName(), f.joiningOffice().isEmpty() ? null : f.joiningOffice(),
                    f.fatherName(), email, f.designation(), f.departmentId(), managerId, f.roleId(), Date.valueOf(f.doj()),
                    loc.tz(), loc.region(), shiftId, PeopleRules.initialStatus(LocalDate.parse(f.doj()), today).name(), access, u.id());
            repo.updatePii(id, piiCols);
            repo.insertUser(id);
            // the TS audit: { code, ...data without *Enc, piiProvided }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("code", code);
            after.put("fullName", fullName);
            after.put("fatherName", f.fatherName());
            after.put("email", email);
            after.put("designation", f.designation());
            after.put("departmentId", f.departmentId());
            after.put("reportingManagerId", managerId);
            after.put("roleId", f.roleId());
            after.put("dateOfJoining", f.doj() + "T00:00:00.000Z");
            after.put("timeZone", loc.tz());
            after.put("region", loc.region());
            after.put("shiftId", shiftId.toString());
            piiCols.forEach((k, v) -> { if (k.endsWith("Last4")) after.put(k, v); });
            after.put("piiProvided", changedPII);
            audit.log(u.id(), "employee.create", "employee", id.toString(), null, after, ip);
            if (india) {
                // Finance is told a salary structure is needed (REQUIREMENTS §5.2).
                List<UUID> finance = repo.findFinanceRecipients();
                for (UUID x : finance) {
                    repo.insertStructuresNotification(x, fullName + " (" + code + ") joined — salary structure pending");
                }
            }
        });
        if (access) tokens.sendInvite(id);
        return new Saved(id.toString(), codeOut[0], fullName + " added as " + codeOut[0] + " · " + role.get("name")
                + (india ? " · salary structure pending with Finance" : "") + (access ? " · invite sent" : " · portal access is off until you turn it on"));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    // ───────────────────────── export ─────────────────────────

    public record Export(String filename, String body) {}

    public Export export(CurrentUser u, Map<String, String> sp) {
        PeopleQuery pq = new PeopleQuery(sp);
        List<List<Object>> rows = repo.findExportRows(pq);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("rows", rows.size());
        after.put("filters", sp);
        audit.log(u.id(), "employee.export", "employee", null, null, after, null);
        String body = Csv.toCsv(List.of("Employee ID", "Name", "Email", "Designation", "Department", "Reporting manager", "Manager ID", "Location", "Time zone",
                "Shift", "Role", "Status", "Portal access", "Date of joining", "Last working day"), rows);
        return new Export("jurisone-people-" + Format.todayIn(u.timeZone()) + ".csv", body);
    }
}
