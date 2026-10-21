package com.jurislpo.jurisone.engine;

import com.jurislpo.jurisone.engine.Csv;
import static com.jurislpo.jurisone.engine.PeopleRules.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.entity.EmployeeStatus;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of tests/unit/people-rules.test.ts (people rules, employee form checks, CSV cells). */
class PeopleRulesTest {

    static final Map<String, String> R = Map.of("owner", "r-admin", "hrm", "r-hrm", "hre", "r-hre", "fm", "r-fm", "fe", "r-fe", "emp", "r-emp");

    static Actor actor(String key, Set<String> perms, Set<String> assign, String department) {
        return new Actor("me", key.equals("emp") ? "employee" : key, perms, assign.stream().map(R::get).collect(Collectors.toSet()), department, R.get("emp"));
    }

    static Target target(String key) { return target(key, "t", EmployeeStatus.ACTIVE, false); }

    static Target target(String key, String id, EmployeeStatus status, boolean superAdmin) {
        return new Target(id, R.get(key), key.equals("emp") ? "employee" : key, status, superAdmin);
    }

    static final Set<String> MANAGE = Set.of("people.add", "access.toggle", "people.view");
    static final Actor ADMIN = actor("owner", MANAGE, Set.of("hrm", "fm", "hre", "fe", "emp"), null);
    static final Actor HRM = actor("hrm", MANAGE, Set.of("hre", "emp"), "d-hr");
    static final Actor FM = actor("fm", MANAGE, Set.of("fe"), "d-fin");
    static final Actor HRE = actor("hre", Set.of("people.view"), Set.of(), null);

    static LocalDate d(String s) { return LocalDate.parse(s); }

    @Nested
    @DisplayName("editing employees")
    class Editing {
        @Test
        @DisplayName("HR Manager edits HR Executives and Employees only")
        void hrManager() {
            assertThat(canEditEmployee(HRM, target("emp"))).isTrue();
            assertThat(canEditEmployee(HRM, target("hre"))).isTrue();
            assertThat(canEditEmployee(HRM, target("fe"))).isFalse();
            assertThat(canEditEmployee(HRM, target("owner"))).isFalse();
        }

        @Test
        @DisplayName("Admin edits anyone except the super admin; nobody edits themselves")
        void admin() {
            assertThat(canEditEmployee(ADMIN, target("hrm"))).isTrue();
            assertThat(canEditEmployee(ADMIN, target("owner", "t", EmployeeStatus.ACTIVE, true))).isFalse();
            assertThat(canEditEmployee(ADMIN, target("owner", "me", EmployeeStatus.ACTIVE, false))).isFalse();
        }

        @Test
        @DisplayName("needs people.add")
        void needsPeopleAdd() {
            assertThat(canEditEmployee(HRE, target("emp"))).isFalse();
            assertThat(canAddEmployees(HRE)).isFalse();
            assertThat(canAddEmployees(FM)).isTrue();
        }
    }

    @Nested
    @DisplayName("access toggle")
    class Access {
        @Test
        @DisplayName("never yourself, never super admin, never exited")
        void never() {
            assertThat(canToggleAccess(HRM, target("emp", "me", EmployeeStatus.ACTIVE, false))).isFalse();
            assertThat(canToggleAccess(ADMIN, target("owner", "t", EmployeeStatus.ACTIVE, true))).isFalse();
            assertThat(canToggleAccess(HRM, target("emp", "t", EmployeeStatus.EXITED, false))).isFalse();
        }

        @Test
        @DisplayName("only Admin can toggle an Admin")
        void onlyAdmin() {
            assertThat(canToggleAccess(HRM, target("owner"))).isFalse();
            assertThat(canToggleAccess(ADMIN, target("owner"))).isTrue();
            assertThat(canToggleAccess(HRM, target("fm"))).isTrue();
        }

        @Test
        @DisplayName("needs access.toggle")
        void needsToggle() {
            assertThat(canToggleAccess(HRE, target("emp"))).isFalse();
        }
    }

    @Nested
    @DisplayName("roles and departments")
    class Roles {
        @Test
        @DisplayName("assigns only allowed roles, but keeping the current role is fine")
        void assign() {
            assertThat(canAssignRole(FM, R.get("fe"), null)).isTrue();
            assertThat(canAssignRole(FM, R.get("emp"), null)).isFalse();
            assertThat(canAssignRole(FM, R.get("emp"), R.get("emp"))).isTrue();
        }

        @Test
        @DisplayName("locks the Finance Manager to Finance; HR and Admin choose freely")
        void deptLock() {
            assertThat(lockedDepartment(FM)).isEqualTo("d-fin");
            assertThat(lockedDepartment(HRM)).isNull();
            assertThat(lockedDepartment(ADMIN)).isNull();
        }
    }

    @Nested
    @DisplayName("status and reporting lines")
    class Status {
        @Test
        @DisplayName("new hires are Onboarding until they join, then Probation")
        void initial() {
            assertThat(initialStatus(d("2026-10-12"), d("2026-10-06"))).isEqualTo(EmployeeStatus.ONBOARDING);
            assertThat(initialStatus(d("2026-10-06"), d("2026-10-06"))).isEqualTo(EmployeeStatus.PROBATION);
        }

        @Test
        @DisplayName("becomes Active after the probation months")
        void derived() {
            assertThat(derivedStatus(EmployeeStatus.PROBATION, d("2026-03-02"), d("2026-09-01"), 6)).isEqualTo(EmployeeStatus.PROBATION);
            assertThat(derivedStatus(EmployeeStatus.PROBATION, d("2026-03-02"), d("2026-09-02"), 6)).isEqualTo(EmployeeStatus.ACTIVE);
            assertThat(derivedStatus(EmployeeStatus.ONBOARDING, d("2026-10-06"), d("2026-10-06"), 6)).isEqualTo(EmployeeStatus.PROBATION);
            assertThat(derivedStatus(EmployeeStatus.EXITED, d("2020-01-01"), d("2026-10-06"), 6)).isEqualTo(EmployeeStatus.EXITED);
        }

        @Test
        @DisplayName("probation end follows JS Date.UTC month overflow (31 Aug + 6 months = 3 Mar)")
        void jsMonthOverflow() {
            assertThat(jsAddMonths(d("2026-08-31"), 6)).isEqualTo(d("2027-03-03")); // Node: Date.UTC(2026, 13, 31)
            assertThat(derivedStatus(EmployeeStatus.PROBATION, d("2026-08-31"), d("2027-03-02"), 6)).isEqualTo(EmployeeStatus.PROBATION);
            assertThat(derivedStatus(EmployeeStatus.PROBATION, d("2026-08-31"), d("2027-03-03"), 6)).isEqualTo(EmployeeStatus.ACTIVE);
        }

        @Test
        @DisplayName("detects reporting loops")
        void cycles() {
            Map<String, String> m = new HashMap<>();
            m.put("a", null);
            m.put("b", "a");
            m.put("c", "b");
            assertThat(createsCycle("a", "c", m)).isTrue(); // a → c → b → a
            assertThat(createsCycle("c", "a", m)).isFalse();
        }
    }

    @Nested
    @DisplayName("form validation")
    class Form {
        static EmployeeInput base() {
            return new EmployeeInput("Meera", "", "Joshi", "Prakash Joshi", "meera.joshi@jurislpo.com", "+91", "98765 43210", "IN_IST", "", "ABCDE1234F",
                    "2345 6789 0123", "", "", "Associate", "x", "y", "2026-10-12", "IN_NIGHT", "r");
        }

        static EmployeeInput with(String field, String value) {
            EmployeeInput b = base();
            Map<String, String> v = new HashMap<>(Map.ofEntries(
                    Map.entry("firstName", b.firstName()), Map.entry("middleName", b.middleName()), Map.entry("lastName", b.lastName()),
                    Map.entry("fatherName", b.fatherName()), Map.entry("email", b.email()), Map.entry("phoneCountry", b.phoneCountry()), Map.entry("phone", b.phone()),
                    Map.entry("location", b.location()), Map.entry("joiningOffice", b.joiningOffice()), Map.entry("pan", b.pan()), Map.entry("aadhaar", b.aadhaar()),
                    Map.entry("uan", b.uan()), Map.entry("address", b.address()), Map.entry("designation", b.designation()), Map.entry("departmentId", b.departmentId()),
                    Map.entry("managerId", b.managerId()), Map.entry("doj", b.dateOfJoining()), Map.entry("shift", b.shift()), Map.entry("roleId", b.roleId())));
            Stream.of(field.split(",")).forEach(f -> v.put(f, value));
            return new EmployeeInput(v.get("firstName"), v.get("middleName"), v.get("lastName"), v.get("fatherName"), v.get("email"), v.get("phoneCountry"),
                    v.get("phone"), v.get("location"), v.get("joiningOffice"), v.get("pan"), v.get("aadhaar"), v.get("uan"), v.get("address"), v.get("designation"),
                    v.get("departmentId"), v.get("managerId"), v.get("doj"), v.get("shift"), v.get("roleId"));
        }

        @Test
        @DisplayName("PAN and Aadhaar are optional, but checked when given")
        void india() {
            assertThat(stepErrors(2, with("pan,aadhaar", ""), false)).isEmpty();
            assertThat(stepErrors(2, with("pan", "abcde1234f"), false)).doesNotContainKey("pan");
            assertThat(stepErrors(2, with("pan", "ABC"), false)).containsKey("pan");
            assertThat(stepErrors(2, with("aadhaar", "1234"), false)).containsKey("aadhaar");
            assertThat(stepErrors(2, with("aadhaar", "1234 1234 1234"), false)).doesNotContainKey("aadhaar");
            assertThat(stepErrors(2, with("uan", "123"), false)).containsKey("uan");
        }

        @Test
        @DisplayName("needs first and last name, a valid email and a phone that fits its country")
        void personal() {
            assertThat(stepErrors(1, base(), false)).isEmpty();
            assertThat(stepErrors(1, with("lastName", ""), false)).containsKey("lastName");
            assertThat(stepErrors(1, with("firstName", ""), false)).containsKey("firstName");
            assertThat(stepErrors(1, with("middleName", ""), false)).isEmpty();
            assertThat(stepErrors(1, with("email", "meera@"), false)).containsKey("email");
            assertThat(stepErrors(1, with("phone", "98765 4321"), false)).containsEntry("phone", "India numbers have 10 digits");
            assertThat(stepErrors(1, with("phone", "58765 43210"), false)).containsKey("phone");
            assertThat(stepErrors(1, with("phoneCountry,phone", "+65"), false)).containsKey("phone");
        }

        @Test
        @DisplayName("skips Indian IDs for the US team, keeps blanks on edit and treats no location as India")
        void usAndEdit() {
            EmployeeInput us = with("location", "US_ET");
            us = new EmployeeInput(us.firstName(), us.middleName(), us.lastName(), us.fatherName(), us.email(), us.phoneCountry(), us.phone(), "US_ET",
                    us.joiningOffice(), "bad", "bad", us.uan(), us.address(), us.designation(), us.departmentId(), us.managerId(), us.dateOfJoining(), us.shift(), us.roleId());
            assertThat(stepErrors(2, us, false)).isEmpty();
            assertThat(stepErrors(1, with("phone", ""), true)).isEmpty();
            assertThat(with("location", "").locationOrDefault()).isEqualTo("IN_IST");
            assertThat(with("middleName", "Rani").fullName()).isEqualTo("Meera Rani Joshi");
        }

        @Test
        @DisplayName("validates Aadhaar shape")
        void aadhaar() {
            assertThat(validAadhaar("2345 6789 0123")).isTrue();
            assertThat(validAadhaar("1234 1234 1234")).isTrue();
            assertThat(validAadhaar("1234 1234 123")).isFalse();
        }
    }

    @Nested
    @DisplayName("csv export")
    class CsvExport {
        @Test
        @DisplayName("neutralises formulas and quotes commas")
        void cells() {
            assertThat(Csv.cell("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)");
            assertThat(Csv.cell("Doe, Jane")).isEqualTo("\"Doe, Jane\"");
        }
    }
}
