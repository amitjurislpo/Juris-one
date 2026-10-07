package com.jurislpo.jurisone.engine;

import static com.jurislpo.jurisone.engine.Permissions.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Sidebar + route access table (prototype NAV). Port of src/lib/nav.ts — every page checks it on the server. */
public final class Nav {
    private Nav() {}

    /**
     * @param perm         any one of these grants access
     * @param needsReports extra condition: the user has active direct reports
     * @param group        sidebar group; null = "My workspace" / "Workspace"
     */
    public record Item(String id, String label, String icon, List<String> perm, boolean needsReports, String group) {}

    private static Item item(String id, String label, String icon, List<String> perm, String group) {
        return new Item(id, label, icon, perm, false, group);
    }

    public static final List<Item> NAV = List.of(
            item("home", "Home", "home", List.of(SELF), null),
            item("overview", "Overview", "home", List.of(ROLES_MANAGE), null),
            item("attendance", "My attendance", "clock", List.of(SELF), null),
            item("leave", "Leave & holidays", "cal", List.of(SELF), null),
            item("payslips", "Payslips", "doc", List.of(SELF), null),
            item("declare", "Tax declarations", "shield", List.of(SELF), null),
            item("directory", "Directory", "book", List.of(DIR), null),
            new Item("team", "My team", "team", List.of(SELF), true, "Team"),
            item("register", "Attendance register", "grid", List.of(ATT_ALL), "People ops"),
            item("approvals", "Approvals", "check", List.of(APPR_LEAVE, APPR_REG), "People ops"),
            item("people", "People", "users", List.of(PEOPLE_VIEW), "People ops"),
            item("departments", "Departments", "building", List.of(DEPT_MANAGE), "People ops"),
            item("holidays", "Holiday calendar", "sun", List.of(HOLIDAYS_MANAGE), "People ops"),
            item("payroll", "Payroll", "wallet", List.of(PAY_VIEW), "Finance"),
            item("structures", "Salary structures", "layers", List.of(STRUCT_MANAGE), "Finance"),
            item("tax", "Tax & TDS", "receipt", List.of(TAX_VIEW), "Finance"),
            item("proofs", "Investment proofs", "inbox", List.of(PROOFS_VERIFY), "Finance"),
            item("roles", "Roles & permissions", "key", List.of(ROLES_MANAGE), "Administration"),
            item("settings", "Policies", "sliders", List.of(ROLES_MANAGE), "Administration"),
            item("audit", "Audit log", "doc", List.of(ROLES_MANAGE), "Administration"));

    public static Optional<Item> find(String id) {
        return NAV.stream().filter(n -> n.id().equals(id)).findFirst();
    }

    public static boolean allowed(Item item, Set<String> perms, boolean hasReports) {
        return item.perm().stream().anyMatch(perms::contains) && (!item.needsReports() || hasReports);
    }

    public static boolean allowed(String navId, Set<String> perms, boolean hasReports) {
        return find(navId).map(i -> allowed(i, perms, hasReports)).orElse(false);
    }

    public static List<Item> visible(Set<String> perms, boolean hasReports) {
        return NAV.stream().filter(n -> allowed(n, perms, hasReports)).toList();
    }

    /** Landing page after login: Home for anyone with `self`, Overview for Admin. */
    public static String landingFor(Set<String> perms) {
        return perms.contains(SELF) ? "/home" : perms.contains(ROLES_MANAGE) ? "/overview" : "/directory";
    }
}
