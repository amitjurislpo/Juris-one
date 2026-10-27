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

/** People records (REQUIREMENTS §5.2). HR never receives salary; PII travels masked. */
@Service
public class PeopleService {

    static final String IST = "Asia/Kolkata";

    private final PeopleRepository repo;
    private final TransactionTemplate transactionTemplate;
    private final ActorService actors;
    private final AuditService audit;
    private final PiiCrypto piiCrypto;
    private final SessionService sessions;
    private final com.jurislpo.jurisone.service.SubscriptionService subscriptions;
    private final JurisTrackRosterTrigger rosterTrigger;

    public PeopleService(PeopleRepository repo, TransactionTemplate transactionTemplate, ActorService actors, AuditService audit, PiiCrypto piiCrypto, SessionService sessions,
                         com.jurislpo.jurisone.service.SubscriptionService subscriptions, JurisTrackRosterTrigger rosterTrigger) {
        this.rosterTrigger = rosterTrigger;
        this.subscriptions = subscriptions;
        this.repo = repo;
        this.transactionTemplate = transactionTemplate;
        this.actors = actors;
        this.audit = audit;
        this.piiCrypto = piiCrypto;
        this.sessions = sessions;
    }

    // ───────────────────────── list ─────────────────────────

    public record PersonRow(String id, String code, String name, String title, String department, String manager, String location, boolean india, String role,
                            String status, boolean access, String dateOfJoining, boolean canToggle, Long photo, String onboardingStatus) {}

    public record DepartmentSummary(String id, String name) {}

    public record Page(List<PersonRow> rows, int total, int page, int take, List<DepartmentSummary> departments, Map<String, Integer> counts, boolean seePay, boolean canAdd) {}

    public Page page(CurrentUser user, Map<String, String> queryParams) {
        PeopleQuery peopleQuery = new PeopleQuery(queryParams, user.id());
        Actor actor = actors.actor(user);
        List<PersonRow> rows = repo.findPage(peopleQuery, t -> PeopleRules.canToggleAccess(actor, t));
        Integer total = repo.count(peopleQuery);
        List<DepartmentSummary> departments = repo.findActiveDepartments();
        Map<String, Integer> counts = repo.countTabs(user.id());
        return new Page(rows, total == null ? 0 : total, peopleQuery.listParams.page(), peopleQuery.listParams.take(), departments, counts, user.perms().contains("struct.manage"), PeopleRules.canAddEmployees(actor));
    }

    // ───────────────────────── directory (dir, everyone) ─────────────────────────

    public record DirectoryRow(String id, String name, String email, String title, String status, Long photo) {}

    public record Directory(List<DirectoryRow> rows, int total, int page, int take, int activeCount, List<DepartmentSummary> departments) {}

    public Directory directory(Map<String, String> queryParams) {
        com.jurislpo.jurisone.common.ListParams listParams = com.jurislpo.jurisone.common.ListParams.of(queryParams, List.of("name", "email", "title", "status"), "name");
        String status = com.jurislpo.jurisone.common.ListParams.pick(queryParams, "status", List.of("All", "Active", "Past"), "All");
        String departmentId = com.jurislpo.jurisone.common.ListParams.text(queryParams, "departmentId", "All");
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (!"All".equals(status)) conditions.add("Past".equals(status) ? "status = 'EXITED'" : "status <> 'EXITED'");
        if (!"All".equals(departmentId) && departmentId.matches("^[0-9a-f-]{36}$")) {
            conditions.add("department_id = ?::uuid");
            args.add(departmentId);
        }
        if (!listParams.search().isEmpty()) {
            conditions.add("(full_name ILIKE ? OR email ILIKE ? OR designation ILIKE ?)");
            for (int i = 0; i < 3; i++) args.add(PeopleQuery.like(listParams.search()));
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        String direction = listParams.asc() ? "ASC" : "DESC";
        String order = switch (listParams.sort()) {
            case "email" -> "email " + direction;
            case "title" -> "designation " + direction + ", full_name ASC";
            case "status" -> "status " + direction + ", full_name ASC";
            default -> "full_name " + direction;
        };
        List<DirectoryRow> rows = repo.findDirectoryPage(where, order, args, listParams.take(), listParams.skip());
        Integer total = repo.countDirectory(where, args);
        Integer active = repo.countCurrent();
        List<DepartmentSummary> departments = repo.findActiveDepartments();
        return new Directory(rows, total == null ? 0 : total, listParams.page(), listParams.take(), active == null ? 0 : active, departments);
    }

    // ───────────────────────── profile drawer ─────────────────────────

    public record Profile(String id, String code, String name, String fatherName, String email, String phone, String address, String pan, String aadhaar,
                          String uan, String designation, String department, String manager, List<String> reports, String dateOfJoining, String exitDate, String location,
                          boolean india, String shift, String role, String status, boolean access, Long gross, boolean canEdit,
                          boolean canToggle, boolean canConfirm, Long photo) {}

    static String maskAddress(String address) {
        if (address == null || address.isEmpty()) return "—";
        List<String> parts = Arrays.stream(address.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return parts.size() > 1 ? "•••••, " + parts.get(parts.size() - 1) : "On file";
    }

    public Profile profile(CurrentUser user, String idRaw) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = repo.findProfile(id);
        if (rows.isEmpty()) throw new UserException("That employee no longer exists.");
        Map<String, Object> row = rows.get(0);
        Actor actor = actors.actor(user);
        EmployeeStatus status = EmployeeStatus.valueOf((String) row.get("status_s"));
        Target target = new Target(id.toString(), row.get("role_id").toString(), (String) row.get("role_key"), status, Boolean.TRUE.equals(row.get("is_super_admin")));

        // Salary is fetched only for struct.manage — HR never receives it.
        Long gross = null;
        if (user.perms().contains("struct.manage")) {
            List<Long> grossValues = repo.findGross(id, Date.valueOf(Format.todayIn(IST)));
            gross = grossValues.isEmpty() ? 0L : grossValues.get(0);
        }
        boolean india = "IN".equals(row.get("region_s"));
        List<String> reports = repo.findCurrentReportNames(id);
        String shift = row.get("shift_code") == null ? "—" : Shifts.label((String) row.get("shift_code"), (String) row.get("start_local"), (String) row.get("end_local"));
        Date exit = (Date) row.get("exit_date");
        return new Profile(id.toString(), (String) row.get("code"), (String) row.get("full_name"), row.get("father_name") == null ? "—" : (String) row.get("father_name"),
                (String) row.get("email"), Masks.phone((String) row.get("phone_last4")), maskAddress(piiCrypto.decrypt((String) row.get("address_enc"))),
                india ? Masks.pan((String) row.get("pan_last4")) : "—", india ? Masks.aadhaar((String) row.get("aadhaar_last4")) : "—",
                india ? Masks.uan((String) row.get("uan_last4")) : "—", (String) row.get("designation"), row.get("department_name") == null ? "—" : (String) row.get("department_name"),
                row.get("manager") == null ? "—" : (String) row.get("manager"), reports, ((Date) row.get("date_of_joining")).toLocalDate().toString(),
                exit == null ? null : exit.toLocalDate().toString(), PeopleRules.locationOfTimeZone((String) row.get("time_zone")).label(), india, shift,
                (String) row.get("role_name"), status.name(), Boolean.TRUE.equals(row.get("portal_access")), gross,
                PeopleRules.canEditEmployee(actor, target) && status != EmployeeStatus.EXITED, PeopleRules.canToggleAccess(actor, target), PeopleRules.canConfirm(actor, target), PhotoService.version(row.get("photo_updated_at")));
    }

    /** Full phone + address for people who may edit the record. Every reveal is audited. */
    public Map<String, String> reveal(CurrentUser user, String idRaw, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        Target target = actors.target(id);
        if (target == null || !PeopleRules.canEditEmployee(actors.actor(user), target)) throw new UserException("You can't view this person's contact details.");
        Map<String, Object> row = repo.findContactEnc(id);
        audit.log(user.id(), "employee.pii_reveal", "employee", id.toString(), null, Map.of("fields", List.of("phone", "address")), ipAddress);
        String phone = piiCrypto.decrypt((String) row.get("phone_enc")), address = piiCrypto.decrypt((String) row.get("address_enc"));
        return Map.of("phone", phone == null ? "—" : phone, "address", address == null ? "—" : address);
    }

    // ───────────────────────── access & exit ─────────────────────────

    public String toggleAccess(CurrentUser user, String idRaw, boolean enabled, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        Target target = actors.target(id);
        if (target == null || !PeopleRules.canToggleAccess(actors.actor(user), target)) throw new UserException("You can't change portal access for this person.");
        Map<String, Object> row = repo.findNameAndAccess(id);
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            repo.setPortalAccess(id, enabled);
            if (!enabled) sessions.revokeAll(id, "access_disabled");
            audit.log(user.id(), "employee.access_toggle", "employee", id.toString(), Map.of("portalAccess", row.get("portal_access")), Map.of("portalAccess", enabled), ipAddress);
        });
        String name = (String) row.get("full_name");
        return enabled ? "Portal access enabled for " + name + " · they sign in with the code emailed to their work email" : name + " can no longer sign in";
    }

    /** Probation → permanent. Admin (owner) and HR only; the daily job would do it after the probation period anyway. */
    public String confirmEmployee(CurrentUser user, String idRaw, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        Target target = actors.target(id);
        if (target == null || !PeopleRules.canConfirm(actors.actor(user), target)) throw new UserException("You can't make this person permanent.");
        String name = (String) repo.findNameAndAccess(id).get("full_name");
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            repo.markConfirmed(id);
            audit.log(user.id(), "employee.confirm", "employee", id.toString(), Map.of("status", target.status().name()), Map.of("status", "ACTIVE"), ipAddress);
            rosterTrigger.rosterChanged(com.jurislpo.jurisone.tenancy.TenantContext.current());
        });
        return name + " is now a permanent employee";
    }

    public String markExited(CurrentUser user, String idRaw, String exitDate, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        if (exitDate == null || !exitDate.matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new UserException("Pick the last working day.");
        Target target = actors.target(id);
        if (target == null || !PeopleRules.canEditEmployee(actors.actor(user), target)) throw new UserException("You can't change this person's record.");
        return finalizeExit(user.id(), id, exitDate, ipAddress);
    }

    /**
     * The exit itself, shared by "Mark as past employee" and the end of the exit process: the employee becomes a past
     * employee on {@code exitDate}, portal access is removed, department headship is cleared and sessions are revoked.
     * The caller has already checked who may do it.
     */
    public String finalizeExit(UUID actorId, UUID id, String exitDate, String ipAddress) {
        Map<String, Object> row = repo.findForExit(id);
        if (row == null || "EXITED".equals(String.valueOf(row.get("status")))) throw new UserException("Already a past employee.");
        if (exitDate.compareTo(((Date) row.get("date_of_joining")).toLocalDate().toString()) < 0) {
            throw new UserException("The last working day can't be before the joining date.");
        }
        long reports = ((Number) row.get("reports")).longValue();
        if (reports > 0) {
            throw new UserException(row.get("full_name") + " still has " + reports + " direct report" + (reports == 1 ? "" : "s") + ". Assign them a new manager first.");
        }
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            repo.markExited(id, Date.valueOf(exitDate));
            repo.clearDepartmentHead(id);
            sessions.revokeAll(id, "exited");
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("status", row.get("status"));
            before.put("portalAccess", row.get("portal_access"));
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", "EXITED");
            after.put("exitDate", exitDate);
            after.put("portalAccess", false);
            audit.log(actorId, "employee.exit", "employee", id.toString(), before, after, ipAddress);
            rosterTrigger.rosterChanged(com.jurislpo.jurisone.tenancy.TenantContext.current());
        });
        return row.get("full_name") + " marked as a past employee · access removed";
    }

    // ───────────────────────── add / edit ─────────────────────────

    public record DepartmentOption(String id, String name, String headId) {}

    public record Manager(String id, String name, String title) {}

    public record RoleOption(String id, String name, int perms) {}

    public record Last4(String phone, String pan, String aadhaar, String uan) {}

    public record Editing(String code, String name, Last4 last4, boolean hasAddress, boolean hadManager) {}

    public record FormOptions(String nextCode, List<DepartmentOption> departments, String lockedDepartmentId, List<Manager> managers, List<RoleOption> roles,
                              EmployeeInput defaults, Editing editing) {}

    /** Next employee ID in this business: its prefix plus the next number, e.g. AB-007 (row-level security scopes the max). */
    String nextCode() {
        String prefix = repo.findEmployeePrefix();
        Integer maxNumber = repo.findMaxCodeNumber(prefix);
        return prefix + "-" + String.format(java.util.Locale.ROOT, "%03d", (maxNumber == null ? 0 : maxNumber) + 1);
    }

    private Map<String, Object> editable(Actor actor, UUID editId) {
        Target target = actors.target(editId);
        if (target == null || !PeopleRules.canEditEmployee(actor, target) || target.status() == EmployeeStatus.EXITED) throw new UserException("You can't edit this person.");
        return repo.findEditable(editId);
    }

    public record RoleRow(String id, String key, String name, int perms) {}

    private boolean roleAllowed(Actor actor, RoleRow roleRow, String currentRoleId) {
        return PeopleRules.canAssignRole(actor, roleRow.id(), currentRoleId) || (PeopleRules.isAdmin(actor) && !PeopleRules.isAdminRole(roleRow.key())) || roleRow.id().equals(currentRoleId);
    }

    public FormOptions formOptions(CurrentUser user, String editIdRaw) {
        Actor actor = actors.actor(user);
        Map<String, Object> current = null;
        UUID editId = null;
        if (editIdRaw != null && !editIdRaw.isEmpty()) {
            editId = Check.uuid(editIdRaw);
            current = editable(actor, editId);
        } else if (!PeopleRules.canAddEmployees(actor)) {
            throw new UserException("You can't add employees.");
        }
        String locked = PeopleRules.lockedDepartment(actor);
        List<DepartmentOption> departments = locked == null
                ? repo.findDepartmentOptions()
                : repo.findDepartmentOption(locked);
        List<Manager> managers = repo.findManagerOptions(editId);
        List<RoleRow> roles = repo.findRoles();
        String currentRole = current == null ? null : current.get("role_id").toString();
        // changing a role is for the admin and HR only: anyone else editing sees just the person's current role
        boolean editingOne = current != null;
        List<RoleRow> allowed = roles.stream().filter(r -> roleAllowed(actor, r, currentRole) && (!editingOne || PeopleRules.canManageStatusAndRole(actor) || r.id().equals(currentRole))).toList();
        RoleRow employeeRole = roles.stream().filter(r -> "employee".equals(r.key())).findFirst().orElse(null);
        Map<String, String> shiftCodes = repo.findShiftCodesById();

        EmployeeInput defaults;
        if (current != null) {
            Object shiftId = current.get("shift_id");
            String shift = shiftId != null && shiftCodes.containsKey(shiftId.toString()) ? shiftCodes.get(shiftId.toString()) : ("US".equals(current.get("region_s")) ? "US_DAY" : "IN_NIGHT");
            String[] names = names(current);
            defaults = new EmployeeInput(names[0], names[1], names[2], current.get("father_name") == null ? "" : (String) current.get("father_name"),
                    (String) current.get("email"), current.get("phone_country") == null ? "+91" : (String) current.get("phone_country"), "",
                    PeopleRules.locationOfTimeZone((String) current.get("time_zone")).value(), current.get("joining_office") == null ? "" : (String) current.get("joining_office"),
                    "", "", "", "", (String) current.get("designation"), current.get("department_id") == null ? "" : current.get("department_id").toString(),
                    current.get("reporting_manager_id") == null ? "" : current.get("reporting_manager_id").toString(),
                    ((Date) current.get("date_of_joining")).toLocalDate().toString(), shift, currentRole, null);
        } else {
            DepartmentOption department = locked != null ? (departments.isEmpty() ? null : departments.get(0))
                    : departments.stream().filter(d -> !List.of("Human Resources", "Finance").contains(d.name())).findFirst().orElse(departments.isEmpty() ? null : departments.get(0));
            RoleRow role = allowed.stream().filter(r -> employeeRole != null && r.id().equals(employeeRole.id())).findFirst().orElse(allowed.isEmpty() ? null : allowed.get(0));
            String manager = locked != null ? user.id().toString() : department == null ? null : department.headId();
            defaults = new EmployeeInput("", "", "", "", "", "+91", "", "", "", "", "", "", "", "", department == null ? "" : department.id(), manager == null ? "" : manager,
                    Format.todayIn(IST).toString(), "IN_NIGHT", role == null ? "" : role.id(), true);
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
        String[] parts = ((String) row.get("full_name")).trim().split("\\s+");
        if (parts.length == 1) return new String[] {parts[0], "", ""};
        return new String[] {parts[0], String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1)), parts[parts.length - 1]};
    }

    /** employeeInput (zod): trims, lower-cases email, upper-cases PAN; first failing field wins, in schema order. */
    static EmployeeInput parse(EmployeeInput input) {
        return parse(input, true);
    }

    /** {@code isFatherNameRequired} is false for an HR-created onboarding: the employee gives it in the wizard. */
    private static EmployeeInput parse(EmployeeInput input, boolean isFatherNameRequired) {
        java.util.function.BiFunction<String, String, String> trimOrEmpty = (v, f) -> v == null ? "" : v.trim();
        String first = trimOrEmpty.apply(input.firstName(), "firstName");
        if (first.isEmpty()) throw bad("First name is required");
        String middle = trimOrEmpty.apply(input.middleName(), "middleName");
        String last = trimOrEmpty.apply(input.lastName(), "lastName");
        if (last.isEmpty()) throw bad("Last name is required");
        for (String name : List.of(first, middle, last)) if (name.length() > 60) throw bad("Keep each name under 60 characters");
        String father = trimOrEmpty.apply(input.fatherName(), "fatherName");
        if (father.length() < 2 && (isFatherNameRequired || !father.isEmpty())) throw bad("Father’s name is required");
        if (father.length() > 120) throw bad("Too big: expected string to have <=120 characters");
        String email = trimOrEmpty.apply(input.email(), "email").toLowerCase();
        if (email.length() > 200) throw bad("Too big: expected string to have <=200 characters");
        if (!PeopleRules.EMAIL_RE.matcher(email).matches()) throw bad("Enter a valid email");
        String phoneCountry = trimOrEmpty.apply(input.phoneCountry(), "phoneCountry");
        if (phoneCountry.isEmpty()) phoneCountry = "+91";
        if (com.jurislpo.jurisone.engine.Phones.country(phoneCountry).isEmpty()) throw bad("Choose the country code");
        String phone = PeopleRules.digits(trimOrEmpty.apply(input.phone(), "phone"));
        if (phone.length() > 15) throw bad("Too big: expected string to have <=15 digits");
        String location = trimOrEmpty.apply(input.location(), "location");
        if (location.isEmpty()) location = "IN_IST"; // not chosen: India
        location = Check.oneOf(location, PeopleRules.LOCATIONS.stream().map(Location::value).toList());
        String office = trimOrEmpty.apply(input.joiningOffice(), "joiningOffice");
        if (office.length() > 120) throw bad("Keep the joining office under 120 characters");
        String pan = trimOrEmpty.apply(input.pan(), "pan").toUpperCase();
        if (pan.length() > 10) throw bad("Too big: expected string to have <=10 characters");
        String aadhaar = trimOrEmpty.apply(input.aadhaar(), "aadhaar");
        if (aadhaar.length() > 20) throw bad("Too big: expected string to have <=20 characters");
        String uan = trimOrEmpty.apply(input.uan(), "uan");
        if (uan.length() > 20) throw bad("Too big: expected string to have <=20 characters");
        String address = trimOrEmpty.apply(input.address(), "address");
        if (address.length() > 500) throw bad("Too big: expected string to have <=500 characters");
        String designation = trimOrEmpty.apply(input.designation(), "designation");
        if (designation.length() < 2) throw bad("Designation is required");
        if (designation.length() > 120) throw bad("Too big: expected string to have <=120 characters");
        String departmentId = Check.uuid(input.departmentId(), "Pick a department").toString();
        String managerId = input.managerId() == null ? "" : input.managerId();
        if (!managerId.isEmpty() && !PeopleRules.UUID_RE.matcher(managerId).matches()) throw bad("Invalid input");
        String dateOfJoining = Check.isoDate(input.dateOfJoining(), "Pick the date of joining");
        String shift = input.shift() == null || input.shift().isBlank() ? ("IN_IST".equals(location) ? "IN_NIGHT" : "US_DAY") : Check.oneOf(input.shift(), List.of("IN_NIGHT", "IN_DAY", "US_DAY"));
        String roleId = Check.uuid(input.roleId(), "Pick a role").toString();
        return new EmployeeInput(first, middle, last, father, email, phoneCountry, phone, location, office, pan, aadhaar, uan, address, designation, departmentId,
                managerId, dateOfJoining, shift, roleId, Boolean.TRUE.equals(input.isOnboardingRequired()));
    }

    private static UserException bad(String message) {
        return new UserException(HttpStatus.BAD_REQUEST, message);
    }

    public record Saved(String id, String code, String message) {}

    public Saved save(CurrentUser user, EmployeeInput input, String editIdRaw, String ipAddress) {
        return save(user, input, editIdRaw, ipAddress, true, true);
    }

    /** Creates the employee of an HR-started onboarding: the add-employee rules, except that the father's name is left to the employee. */
    public Saved createForOnboarding(CurrentUser user, EmployeeInput input, String ipAddress) {
        return save(user, input, null, ipAddress, true, false);
    }

    private Saved save(CurrentUser user, EmployeeInput input, String editIdRaw, String ipAddress, boolean access, boolean isFatherNameRequired) {
        EmployeeInput employeeInput = parse(input, isFatherNameRequired);
        Actor actor = actors.actor(user);
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
            Map<String, String> errs = PeopleRules.stepErrors(step, employeeInput, editing, hadManager);
            if (!isFatherNameRequired) errs.remove("fatherName");
            if (!errs.isEmpty()) throw new UserException(errs.values().iterator().next());
        }

        // Role: only from the creator's assignable list (or unchanged on edit).
        List<Map<String, Object>> roleRows = repo.findRole(employeeInput.roleId());
        if (roleRows.isEmpty()) throw new UserException("Pick a role.");
        Map<String, Object> role = roleRows.get(0);
        String currentRole = current == null ? null : current.get("role_id").toString();
        boolean canAssign = PeopleRules.canAssignRole(actor, employeeInput.roleId(), currentRole) || (PeopleRules.isAdmin(actor) && !PeopleRules.isAdminRole((String) role.get("key")));
        if (!canAssign) throw new UserException("You can't give people the " + role.get("name") + " role.");
        if (current != null && !employeeInput.roleId().equals(currentRole) && !PeopleRules.canManageStatusAndRole(actor)) throw new UserException("Only the admin or HR can change someone's role.");

        // Department: must exist; locked to the actor's own department where the rule applies.
        List<Map<String, Object>> department = repo.findDepartmentArchivedAt(employeeInput.departmentId());
        if (department.isEmpty() || department.get(0).get("archived_at") != null) throw new UserException("Pick a department.");
        String locked = PeopleRules.lockedDepartment(actor);
        if (locked != null && !employeeInput.departmentId().equals(locked)) throw new UserException("You can add people to your own department only.");

        // Manager: current employee, not themselves, no reporting loops.
        if (!employeeInput.managerId().isEmpty()) {
            List<String> managerStatuses = repo.findStatus(employeeInput.managerId());
            if (managerStatuses.isEmpty() || "EXITED".equals(managerStatuses.get(0))) throw new UserException("Pick a current employee as reporting manager.");
            if (editId != null) {
                if (employeeInput.managerId().equals(editId.toString())) throw new UserException("Someone can't report to themselves.");
                Map<String, String> managerOf = repo.findManagerByEmployee();
                if (PeopleRules.createsCycle(editId.toString(), employeeInput.managerId(), managerOf)) {
                    throw new UserException("That manager already reports (directly or indirectly) to this person.");
                }
            }
        }

        String email = employeeInput.email().toLowerCase();
        List<String> clash = editId == null
                ? repo.findCodeByEmail(email)
                : repo.findCodeByEmailExcept(email, editId);
        if (!clash.isEmpty()) throw new UserException(clash.get(0) + " already uses that email.");
        // the email is the sign-in, so it must also be free in every other business (which is not named)
        if (Boolean.TRUE.equals(repo.emailInUse(email, editId == null ? null : UUID.fromString(editId.toString())))) {
            throw new UserException("That email is already in use.");
        }

        Location location = PeopleRules.location(employeeInput.locationOrDefault());
        boolean india = "IN".equals(location.region());
        String shiftCode = india ? ("US_DAY".equals(employeeInput.shift()) ? "IN_NIGHT" : employeeInput.shift()) : "US_DAY";
        UUID shiftId = repo.findShiftId(shiftCode);

        // PII: on edit, blank means keep; switching to the US clears the Indian IDs. Insertion order = the TS object.
        Map<String, String> piiCols = new LinkedHashMap<>();
        java.util.function.BiConsumer<String, String> setPII = (field, value) -> {
            piiCols.put(field + "Enc", piiCrypto.encrypt(value));
            piiCols.put(field + "Last4", PiiCrypto.last4(value));
        };
        if (!employeeInput.phone().isEmpty()) {
            setPII.accept("phone", employeeInput.phone());
            piiCols.put("phoneCountry", employeeInput.phoneCountry());
        }
        if (!employeeInput.address().isEmpty()) piiCols.put("addressEnc", piiCrypto.encrypt(employeeInput.address()));
        if (india) {
            if (!employeeInput.pan().isEmpty()) setPII.accept("pan", employeeInput.pan().toUpperCase());
            if (!employeeInput.aadhaar().isEmpty()) setPII.accept("aadhaar", PeopleRules.digits(employeeInput.aadhaar()));
            if (!employeeInput.uan().isEmpty()) setPII.accept("uan", PeopleRules.digits(employeeInput.uan()));
        } else {
            for (String key : List.of("pan", "aadhaar", "uan")) {
                piiCols.put(key + "Enc", null);
                piiCols.put(key + "Last4", null);
            }
        }
        List<String> changedPII = piiCols.keySet().stream().filter(k -> k.endsWith("Enc")).map(k -> k.replace("Enc", "")).toList();
        String fullName = employeeInput.fullName();
        String managerId = employeeInput.managerId().isEmpty() ? null : employeeInput.managerId();
        LocalDate today = Format.todayIn(IST);

        if (current != null) {
            Map<String, Object> currentRow = current;
            UUID id = editId;
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("fullName", currentRow.get("full_name"));
            before.put("fatherName", currentRow.get("father_name"));
            before.put("email", currentRow.get("email"));
            before.put("designation", currentRow.get("designation"));
            before.put("departmentId", textOrEmpty(currentRow.get("department_id")));
            before.put("reportingManagerId", textOrEmpty(currentRow.get("reporting_manager_id")));
            before.put("roleId", textOrEmpty(currentRow.get("role_id")));
            before.put("dateOfJoining", ((Date) currentRow.get("date_of_joining")).toLocalDate().toString());
            before.put("timeZone", currentRow.get("time_zone"));
            before.put("shiftId", textOrEmpty(currentRow.get("shift_id")));
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("fullName", fullName);
            after.put("fatherName", employeeInput.fatherName());
            after.put("email", email);
            after.put("designation", employeeInput.designation());
            after.put("departmentId", employeeInput.departmentId());
            after.put("reportingManagerId", managerId);
            after.put("roleId", employeeInput.roleId());
            after.put("dateOfJoining", employeeInput.dateOfJoining());
            after.put("timeZone", location.timeZone());
            after.put("shiftId", shiftId.toString());
            after.put("piiChanged", changedPII);
            String status = (String) currentRow.get("status_s");
            String newStatus = "ONBOARDING".equals(status) || "PROBATION".equals(status)
                    ? PeopleRules.initialStatus(LocalDate.parse(employeeInput.dateOfJoining()), today).name() : status;
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                repo.updateEmployee(id, fullName, employeeInput.firstName(), employeeInput.middleName().isEmpty() ? null : employeeInput.middleName(), employeeInput.lastName(),
                        employeeInput.joiningOffice().isEmpty() ? null : employeeInput.joiningOffice(), employeeInput.fatherName(), email, employeeInput.designation(), employeeInput.departmentId(), managerId, employeeInput.roleId(), Date.valueOf(employeeInput.dateOfJoining()),
                        location.timeZone(), location.region(), shiftId, newStatus);
                repo.updatePii(id, piiCols);
                audit.log(user.id(), "employee.edit", "employee", id.toString(), before, after, ipAddress);
            rosterTrigger.rosterChanged(com.jurislpo.jurisone.tenancy.TenantContext.current());
            });
            return new Saved(id.toString(), (String) currentRow.get("code"), "Saved changes for " + fullName);
        }

        String[] codeOut = new String[1];
        UUID id = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            // serialise code allocation
            repo.lockCodeAllocation(); // one allocation at a time per business
            subscriptions.checkRoomForOneMore(); // the plan's employee limit (under the same lock, so two adds can't both slip past)
            String code = nextCode();
            codeOut[0] = code;
            repo.insertEmployee(id, code, fullName, employeeInput.firstName(), employeeInput.middleName().isEmpty() ? null : employeeInput.middleName(), employeeInput.lastName(), employeeInput.joiningOffice().isEmpty() ? null : employeeInput.joiningOffice(),
                    employeeInput.fatherName().isEmpty() ? null : employeeInput.fatherName(), email, employeeInput.designation(), employeeInput.departmentId(), managerId, employeeInput.roleId(), Date.valueOf(employeeInput.dateOfJoining()),
                    location.timeZone(), location.region(), shiftId, PeopleRules.initialStatus(LocalDate.parse(employeeInput.dateOfJoining()), today).name(), access, user.id());
            repo.updatePii(id, piiCols);
            repo.insertUser(id);
            repo.insertOnboarding(UUID.randomUUID(), id, Boolean.TRUE.equals(employeeInput.isOnboardingRequired()));
            // the TS audit: { code, ...data without *Enc, piiProvided }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("code", code);
            after.put("fullName", fullName);
            after.put("fatherName", employeeInput.fatherName());
            after.put("email", email);
            after.put("designation", employeeInput.designation());
            after.put("departmentId", employeeInput.departmentId());
            after.put("reportingManagerId", managerId);
            after.put("roleId", employeeInput.roleId());
            after.put("dateOfJoining", employeeInput.dateOfJoining() + "T00:00:00.000Z");
            after.put("timeZone", location.timeZone());
            after.put("region", location.region());
            after.put("shiftId", shiftId.toString());
            piiCols.forEach((k, v) -> { if (k.endsWith("Last4")) after.put(k, v); });
            after.put("piiProvided", changedPII);
            audit.log(user.id(), "employee.create", "employee", id.toString(), null, after, ipAddress);
            rosterTrigger.rosterChanged(com.jurislpo.jurisone.tenancy.TenantContext.current());
            if (india) {
                // Finance is told a salary structure is needed (REQUIREMENTS §5.2).
                List<UUID> finance = repo.findFinanceRecipients();
                for (UUID financeUserId : finance) {
                    repo.insertStructuresNotification(financeUserId, fullName + " (" + code + ") joined — salary structure pending");
                }
            }
        });
        return new Saved(id.toString(), codeOut[0], fullName + " added as " + codeOut[0] + " · " + role.get("name")
                + (india ? " · salary structure pending with Finance" : "") + (access ? " · they sign in with the code emailed to their work email" : " · portal access is off until you turn it on"));
    }

    private static String textOrEmpty(Object value) {
        return value == null ? null : value.toString();
    }

    // ───────────────────────── export ─────────────────────────

    public record Export(String filename, String body) {}

    public Export export(CurrentUser user, Map<String, String> queryParams) {
        PeopleQuery peopleQuery = new PeopleQuery(queryParams);
        List<List<Object>> rows = repo.findExportRows(peopleQuery);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("rows", rows.size());
        after.put("filters", queryParams);
        audit.log(user.id(), "employee.export", "employee", null, null, after, null);
        String body = Csv.toCsv(List.of("Employee ID", "Name", "Email", "Designation", "Department", "Reporting manager", "Manager ID", "Location", "Time zone",
                "Shift", "Role", "Status", "Portal access", "Date of joining", "Last working day"), rows);
        return new Export("jurisone-people-" + Format.todayIn(user.timeZone()) + ".csv", body);
    }
}
