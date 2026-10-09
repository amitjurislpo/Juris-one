package com.jurislpo.jurisone.engine;

import com.jurislpo.jurisone.entity.EmployeeStatus;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Who may do what to an employee record (REQUIREMENTS §3.3). Port of src/lib/people-rules.ts and the
 * step checks of src/lib/employee-schema.ts. Pure — services call these after loading actor + target.
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

    public static boolean isAdmin(Actor a) {
        return isAdminRole(a.roleKey()); // the business owner (sub admin), or the super admin in the platform company
    }

    /** People ops can add employees only if their role can assign at least one role. */
    public static boolean canAddEmployees(Actor a) {
        return a.perms().contains(Permissions.PEOPLE_ADD) && (isAdmin(a) || !a.assignable().isEmpty());
    }

    /** Edit needs people.add and the target's role in the actor's assignable list. Admin edits anyone. Nobody edits themselves. */
    public static boolean canEditEmployee(Actor a, Target t) {
        if (!a.perms().contains(Permissions.PEOPLE_ADD) || a.id().equals(t.id()) || t.isSuperAdmin()) return false;
        return isAdmin(a) || a.assignable().contains(t.roleId());
    }

    /** access.toggle; never yourself; never the super admin; only Admin toggles an Admin; exited people stay off. */
    public static boolean canToggleAccess(Actor a, Target t) {
        if (!a.perms().contains(Permissions.ACCESS_TOGGLE) || a.id().equals(t.id()) || t.isSuperAdmin() || t.status() == EmployeeStatus.EXITED) return false;
        return isAdmin(a) || !isAdminRole(t.roleKey());
    }

    /** May the actor give {@code roleId} to someone? Keeping a person's current role is always allowed for an editor. */
    public static boolean canAssignRole(Actor a, String roleId, String currentRoleId) {
        if (currentRoleId != null && !currentRoleId.isEmpty() && roleId.equals(currentRoleId)) return true;
        return a.assignable().contains(roleId);
    }

    /**
     * Managers who can't hire plain Employees (e.g. Finance Manager → Finance Executive) add people
     * into their own department only. Admin and HR choose freely.
     */
    public static String lockedDepartment(Actor a) {
        if (isAdmin(a) || a.assignable().contains(a.employeeRoleId())) return null;
        return a.departmentId();
    }

    /** Status for a new hire: Onboarding until the joining date, then Probation. */
    public static EmployeeStatus initialStatus(LocalDate doj, LocalDate today) {
        return doj.isAfter(today) ? EmployeeStatus.ONBOARDING : EmployeeStatus.PROBATION;
    }

    /**
     * {@code Date.UTC(y, m - 1 + months, d)}: JS lets the day overflow into the next month
     * (31 Aug + 6 months = 3 Mar), unlike {@link LocalDate#plusMonths} which clamps.
     */
    static LocalDate jsAddMonths(LocalDate d, int months) {
        return LocalDate.of(d.getYear(), 1, 1).plusMonths(d.getMonthValue() - 1L + months).plusDays(d.getDayOfMonth() - 1L);
    }

    /** Status as time passes (daily job): Onboarding → Probation on DOJ → Active after N months. */
    public static EmployeeStatus derivedStatus(EmployeeStatus current, LocalDate doj, LocalDate today, int probationMonths) {
        if (current == EmployeeStatus.EXITED) return current;
        if (doj.isAfter(today)) return EmployeeStatus.ONBOARDING;
        LocalDate end = jsAddMonths(doj, probationMonths);
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
    public record Location(String v, String l, String tz, String region) {}

    public static final List<Location> LOCATIONS = List.of(
            new Location("IN_IST", "India · IST", "Asia/Kolkata", "IN"),
            new Location("US_ET", "US East · ET", "America/New_York", "US"),
            new Location("US_CT", "US Central · CT", "America/Chicago", "US"),
            new Location("US_PT", "US West · PT", "America/Los_Angeles", "US"));

    public static Location locationOfTz(String tz) {
        return LOCATIONS.stream().filter(l -> l.tz().equals(tz)).findFirst().orElse(LOCATIONS.get(0));
    }

    public static Location location(String code) {
        return LOCATIONS.stream().filter(l -> l.v().equals(code)).findFirst().orElse(null);
    }

    // ─── identity validation (same regexes as the TS / zod 4 code) ───
    public static final Pattern PAN_RE = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    /** zod 4 {@code z.string().email()}. */
    public static final Pattern EMAIL_RE = Pattern.compile("^(?:[A-Za-z0-9_'+\\-]+\\.)*[A-Za-z0-9_'+\\-]*[A-Za-z0-9_+-]@(?:[A-Za-z0-9][A-Za-z0-9\\-]*\\.)+[A-Za-z]{2,}$");
    /** zod 4 {@code z.string().uuid()}. */
    public static final Pattern UUID_RE = Pattern.compile("^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$");

    public static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    public static boolean validAadhaar(String s) {
        return digits(s).length() == 12;
    }

    public static boolean validUan(String s) {
        return digits(s).length() == 12;
    }

    public static boolean validPhone(String s) {
        int n = digits(s).length();
        return n >= 10 && n <= 15;
    }

    /** The add / edit employee form (employee-schema.ts EmployeeInput); strings already trimmed by the caller where zod trims. */
    /**
     * Add / edit employee. {@code location} empty = India (IST); {@code phone} is the national number for
     * {@code phoneCountry} (e.g. "+91"); PAN, Aadhaar and UAN are optional (checked when given).
     */
    public record EmployeeInput(String firstName, String middleName, String lastName, String fatherName, String email, String phoneCountry, String phone,
                                String location, String joiningOffice, String pan, String aadhaar, String uan, String address, String designation,
                                String departmentId, String managerId, String doj, String shift, String roleId) {

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
    public static Map<String, String> stepErrors(int step, EmployeeInput f, boolean editing, boolean hadManager) {
        Map<String, String> x = new LinkedHashMap<>();
        boolean india = "IN_IST".equals(f.locationOrDefault());
        if (step == 1) {
            if (f.firstName().trim().isEmpty()) x.put("firstName", "First name is required");
            if (f.lastName().trim().isEmpty()) x.put("lastName", "Last name is required");
            if (f.fatherName().trim().length() < 2) x.put("fatherName", "Father’s name is required");
            if (!EMAIL_RE.matcher(f.email().trim()).matches()) x.put("email", "Enter a valid email");
            if (!f.phone().isEmpty() || !editing) {
                String p = Phones.problem(f.phoneCountry(), f.phone());
                if (p != null) x.put("phone", p);
            }
        }
        if (step == 2 && india) {
            if (!f.pan().isEmpty() && !PAN_RE.matcher(f.pan().trim().toUpperCase()).matches()) x.put("pan", "PAN looks like ABCDE1234F");
            if (!f.aadhaar().isEmpty() && !validAadhaar(f.aadhaar())) x.put("aadhaar", "Aadhaar has 12 digits");
            if (!f.uan().isEmpty() && !validUan(f.uan())) x.put("uan", "UAN has 12 digits");
        }
        if (step == 3) {
            if (f.designation().trim().length() < 2) x.put("designation", "Designation is required");
            if (f.departmentId().isEmpty()) x.put("departmentId", "Pick a department");
            if (f.managerId().isEmpty() && (!editing || hadManager)) x.put("managerId", "Pick a reporting manager");
            if (f.doj().isEmpty()) x.put("doj", "Pick the date of joining");
            if (f.roleId().isEmpty()) x.put("roleId", "Pick a role");
        }
        return x;
    }

    public static Map<String, String> stepErrors(int step, EmployeeInput f, boolean editing) {
        return stepErrors(step, f, editing, false);
    }
}
