package com.jurislpo.jurisone.engine;

import com.jurislpo.jurisone.entity.EmployeeStatus;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Who may do what to an employee record (REQUIREMENTS §3.3), including the
 * step checks of the employee form. Pure — services call these after loading actor + target.
 */
public final class PeopleRules {
    private PeopleRules() {}

    /**
     * @param assignable     role ids this actor's role may assign (role_assignable_roles)
     * @param employeeRoleId role id of the built-in Employee role — decides the department lock
     */
    public record Actor(String id, String roleKey, Set<String> perms, Set<String> assignable, String departmentId, String employeeRoleId) {}

    public record Target(String id, String roleId, String roleKey, EmployeeStatus status, boolean isSuperAdmin) {}

    /** Roles that run a business: the owner, and the super admin inside the platform company. Never assignable, never listed. */
    public static boolean isAdminRole(String roleKey) {
        return "owner".equals(roleKey) || "super_admin".equals(roleKey);
    }

    public static boolean isAdmin(Actor actor) {
        return isAdminRole(actor.roleKey()); // the business owner (sub admin), or the super admin in the platform company
    }

    /** The HR Manager role (built into every business). */
    public static boolean isHr(Actor actor) {
        return "hrm".equals(actor.roleKey());
    }

    /** Only the business admin (owner) and HR may change an employee's status (confirmation) or role. */
    public static boolean canManageStatusAndRole(Actor actor) {
        return isAdmin(actor) || isHr(actor);
    }

    /** Make a probation / onboarding employee permanent: admin or HR with people.add; never yourself, never the super admin. */
    public static boolean canConfirm(Actor actor, Target target) {
        return actor.perms().contains(Permissions.PEOPLE_ADD) && canManageStatusAndRole(actor) && !actor.id().equals(target.id()) && !target.isSuperAdmin()
                && (target.status() == EmployeeStatus.PROBATION || target.status() == EmployeeStatus.ONBOARDING);
    }

    /** People ops can add employees only if their role can assign at least one role. */
    public static boolean canAddEmployees(Actor actor) {
        return actor.perms().contains(Permissions.PEOPLE_ADD) && (isAdmin(actor) || !actor.assignable().isEmpty());
    }

    /** Edit needs people.add and the target's role in the actor's assignable list. Admin edits anyone. Nobody edits themselves. */
    public static boolean canEditEmployee(Actor actor, Target target) {
        if (!actor.perms().contains(Permissions.PEOPLE_ADD) || actor.id().equals(target.id()) || target.isSuperAdmin()) return false;
        return isAdmin(actor) || actor.assignable().contains(target.roleId());
    }

    /** access.toggle; never yourself; never the super admin; only Admin toggles an Admin; exited people stay off. */
    public static boolean canToggleAccess(Actor actor, Target target) {
        if (!actor.perms().contains(Permissions.ACCESS_TOGGLE) || actor.id().equals(target.id()) || target.isSuperAdmin() || target.status() == EmployeeStatus.EXITED) return false;
        return isAdmin(actor) || !isAdminRole(target.roleKey());
    }

    /** May the actor give {@code roleId} to someone? Keeping a person's current role is always allowed for an editor. */
    public static boolean canAssignRole(Actor actor, String roleId, String currentRoleId) {
        if (currentRoleId != null && !currentRoleId.isEmpty() && roleId.equals(currentRoleId)) return true;
        return actor.assignable().contains(roleId);
    }

    /**
     * Managers who can't hire plain Employees (e.g. Finance Manager → Finance Executive) add people
     * into their own department only. Admin and HR choose freely.
     */
    public static String lockedDepartment(Actor actor) {
        if (isAdmin(actor) || actor.assignable().contains(actor.employeeRoleId())) return null;
        return actor.departmentId();
    }

    /** Status for a new hire: Onboarding until the joining date, then Probation. */
    public static EmployeeStatus initialStatus(LocalDate dateOfJoining, LocalDate today) {
        return dateOfJoining.isAfter(today) ? EmployeeStatus.ONBOARDING : EmployeeStatus.PROBATION;
    }

    /**
     * {@code Date.UTC(y, m - 1 + months, d)}: JS lets the day overflow into the next month
     * (31 Aug + 6 months = 3 Mar), unlike {@link LocalDate#plusMonths} which clamps.
     */
    static LocalDate jsAddMonths(LocalDate date, int months) {
        return LocalDate.of(date.getYear(), 1, 1).plusMonths(date.getMonthValue() - 1L + months).plusDays(date.getDayOfMonth() - 1L);
    }

    /** Status as time passes (daily job): Onboarding → Probation on DOJ → Active after N months. */
    public static EmployeeStatus derivedStatus(EmployeeStatus current, LocalDate dateOfJoining, LocalDate today, int probationMonths) {
        if (current == EmployeeStatus.EXITED) return current;
        if (dateOfJoining.isAfter(today)) return EmployeeStatus.ONBOARDING;
        LocalDate end = jsAddMonths(dateOfJoining, probationMonths);
        if (!today.isBefore(end)) return current == EmployeeStatus.ONBOARDING || current == EmployeeStatus.PROBATION ? EmployeeStatus.ACTIVE : current;
        return current == EmployeeStatus.ONBOARDING ? EmployeeStatus.PROBATION : current;
    }

    /** Would setting {@code managerId} as the manager of {@code employeeId} create a reporting loop? */
    public static boolean createsCycle(String employeeId, String managerId, Map<String, String> managerOf) {
        String cur = managerId;
        for (int i = 0; cur != null && !cur.isEmpty() && i < 10_000; i++) {
            if (cur.equals(employeeId)) return true;
            cur = managerOf.get(cur);
        }
        return false;
    }

    // ─── locations ───
    public record Location(String value, String label, String timeZone, String region) {}

    public static final List<Location> LOCATIONS = List.of(
            new Location("IN_IST", "India · IST", "Asia/Kolkata", "IN"),
            new Location("US_ET", "US East · ET", "America/New_York", "US"),
            new Location("US_CT", "US Central · CT", "America/Chicago", "US"),
            new Location("US_PT", "US West · PT", "America/Los_Angeles", "US"));

    public static Location locationOfTimeZone(String timeZone) {
        return LOCATIONS.stream().filter(l -> l.timeZone().equals(timeZone)).findFirst().orElse(LOCATIONS.get(0));
    }

    public static Location location(String code) {
        return LOCATIONS.stream().filter(l -> l.value().equals(code)).findFirst().orElse(null);
    }

    // ─── identity validation (same regexes as the TS / zod 4 code) ───
    public static final Pattern PAN_RE = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    /** zod 4 {@code z.string().email()}. */
    public static final Pattern EMAIL_RE = Pattern.compile("^(?:[A-Za-z0-9_'+\\-]+\\.)*[A-Za-z0-9_'+\\-]*[A-Za-z0-9_+-]@(?:[A-Za-z0-9][A-Za-z0-9\\-]*\\.)+[A-Za-z]{2,}$");
    /** zod 4 {@code z.string().uuid()}. */
    public static final Pattern UUID_RE = Pattern.compile("^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$");

    public static String digits(String text) {
        return text == null ? "" : text.replaceAll("\\D", "");
    }

    public static boolean validAadhaar(String text) {
        return digits(text).length() == 12;
    }

    public static boolean validUan(String text) {
        return digits(text).length() == 12;
    }

    public static boolean validPhone(String text) {
        int n = digits(text).length();
        return n >= 10 && n <= 15;
    }

    /** The add / edit employee form (employee-schema.ts EmployeeInput); strings already trimmed by the caller where zod trims. */
    /**
     * Add / edit employee. {@code location} empty = India (IST); {@code phone} is the national number for
     * {@code phoneCountry} (e.g. "+91"); PAN, Aadhaar and UAN are optional (checked when given).
     */
    public record EmployeeInput(String firstName, String middleName, String lastName, String fatherName, String email, String phoneCountry, String phone,
                                String location, String joiningOffice, String pan, String aadhaar, String uan, String address, String designation,
                                String departmentId, String managerId, String dateOfJoining, String shift, String roleId) {

        /** "First Middle Last". */
        public String fullName() {
            return java.util.stream.Stream.of(firstName, middleName, lastName).filter(x -> x != null && !x.isBlank()).map(String::trim)
                    .collect(java.util.stream.Collectors.joining(" "));
        }

        /** The work location code; empty means India (IST). */
        public String locationOrDefault() {
            return location == null || location.isBlank() ? "IN_IST" : location;
        }
    }

    /** Step-by-step checks shown in the form (and re-run on the server). Field → message, in check order. */
    public static Map<String, String> stepErrors(int step, EmployeeInput input, boolean editing, boolean hadManager) {
        Map<String, String> x = new LinkedHashMap<>();
        boolean india = "IN_IST".equals(input.locationOrDefault());
        if (step == 1) {
            if (input.firstName().trim().isEmpty()) x.put("firstName", "First name is required");
            if (input.lastName().trim().isEmpty()) x.put("lastName", "Last name is required");
            if (input.fatherName().trim().length() < 2) x.put("fatherName", "Father’s name is required");
            if (!EMAIL_RE.matcher(input.email().trim()).matches()) x.put("email", "Enter a valid email");
            if (!input.phone().isEmpty() || !editing) {
                String p = Phones.problem(input.phoneCountry(), input.phone());
                if (p != null) x.put("phone", p);
            }
        }
        if (step == 2 && india) {
            if (!input.pan().isEmpty() && !PAN_RE.matcher(input.pan().trim().toUpperCase()).matches()) x.put("pan", "PAN looks like ABCDE1234F");
            if (!input.aadhaar().isEmpty() && !validAadhaar(input.aadhaar())) x.put("aadhaar", "Aadhaar has 12 digits");
            if (!input.uan().isEmpty() && !validUan(input.uan())) x.put("uan", "UAN has 12 digits");
        }
        if (step == 3) {
            if (input.designation().trim().length() < 2) x.put("designation", "Designation is required");
            if (input.departmentId().isEmpty()) x.put("departmentId", "Pick a department");
            if (input.managerId().isEmpty() && (!editing || hadManager)) x.put("managerId", "Pick a reporting manager");
            if (input.dateOfJoining().isEmpty()) x.put("dateOfJoining", "Pick the date of joining");
            if (input.roleId().isEmpty()) x.put("roleId", "Pick a role");
        }
        return x;
    }

    public static Map<String, String> stepErrors(int step, EmployeeInput input, boolean editing) {
        return stepErrors(step, input, editing, false);
    }
}
