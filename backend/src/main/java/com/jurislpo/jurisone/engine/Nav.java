package com.jurislpo.jurisone.engine;

import static com.jurislpo.jurisone.engine.Permissions.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Sidebar + route access table (prototype NAV). Every page checks it on the server. */
public final class Nav {
    private Nav() {}

    /**
     * @param perm         any one of these grants access
     * @param needsReports extra condition: the user has active direct reports
     * @param group        sidebar group; null = "My workspace" / "Workspace"
     */
    public record Item(String id, String label, String icon, List<String> perm, boolean needsReports, String group, String module) {}

    private static Item item(String id, String label, String icon, List<String> perm, String group) {
        return new Item(id, label, icon, perm, false, group, null);
    }

    /** Juris Track (and later modules): only for businesses whose plan or grant includes the module. */
    public static final String JURIS_TRACK = "JURIS_TRACK";

    public static final List<Item> NAV = List.of(
            item("platform", "Businesses", "building", List.of(PLATFORM_MANAGE), "Platform"),
            item("plans", "Plans", "layers", List.of(PLATFORM_MANAGE), "Platform"),
            item("platform-audit", "Audit log", "doc", List.of(PLATFORM_MANAGE), "Platform"),
            item("home", "Home", "home", List.of(SELF), null),
            item("overview", "Overview", "home", List.of(ROLES_MANAGE), null),
            item("notifications", "Notifications", "bell", List.of(SELF, DIR, ROLES_MANAGE), null),
            item("profile", "My profile", "user", List.of(SELF, DIR, ROLES_MANAGE), null),
            item("attendance", "My attendance", "clock", List.of(SELF), null),
            item("leave", "Leave & holidays", "cal", List.of(SELF), null),
            item("payslips", "Payslips", "doc", List.of(SELF), null),
            item("declare", "Tax declarations", "shield", List.of(SELF), null),
            item("my-letters", "My letters", "doc", List.of(SELF), null),
            item("directory", "Directory", "book", List.of(DIR), null),
            item("my-policies", "My policies", "shield", List.of(SELF, DIR, ROLES_MANAGE), null),
            item("my-assets", "My assets", "layers", List.of(SELF, DIR, ROLES_MANAGE), null),
            item("announcements", "Announcements", "bell", List.of(SELF, DIR, ROLES_MANAGE), null),
            new Item("juris-track", "Juris Track", "sparkle", List.of(SELF, ROLES_MANAGE, DIR), false, null, JURIS_TRACK),
            // Tasks: free for every business (no module); using tasks needs no permission, only the settings do
            item("my-tasks", "My tasks", "tasks", List.of(SELF, DIR, ROLES_MANAGE), "Tasks"),
            new Item("team-tasks", "Team tasks", "team", List.of(SELF, DIR, ROLES_MANAGE), true, "Tasks", null),
            item("projects", "Projects", "folder", List.of(SELF, DIR, ROLES_MANAGE), "Tasks"),
            item("task-settings", "Task settings", "sliders", List.of(TASKS_MANAGE), "Tasks"),
            new Item("team", "My team", "team", List.of(SELF), true, "Team", null),
            item("register", "Attendance register", "grid", List.of(ATT_ALL), "People ops"),
            item("approvals", "Approvals", "check", List.of(APPR_LEAVE, APPR_REG), "People ops"),
            item("employees", "Employees", "users", List.of(PEOPLE_VIEW), "People ops"),
            item("onboarding", "Onboarding", "inbox", List.of(PEOPLE_ADD), "People ops"),
            item("departments", "Departments", "building", List.of(DEPT_MANAGE), "People ops"),
            item("holidays", "Holiday calendar", "sun", List.of(HOLIDAYS_MANAGE), "People ops"),
            item("assets", "Assets", "layers", List.of(ASSETS_MANAGE), "People ops"),
            item("manage-announcements", "Post announcements", "bell", List.of(ANNOUNCEMENTS_MANAGE), "People ops"),
            item("letters", "HR letters", "doc", List.of(LETTERS_MANAGE), "People ops"),
            item("payroll", "Payroll", "wallet", List.of(PAY_VIEW), "Finance"),
            item("structures", "Salary structures", "layers", List.of(STRUCT_MANAGE), "Finance"),
            item("tax", "Tax & TDS", "receipt", List.of(TAX_VIEW), "Finance"),
            item("proofs", "Investment proofs", "inbox", List.of(PROOFS_VERIFY), "Finance"),
            item("roles", "Roles & permissions", "key", List.of(ROLES_MANAGE), "Administration"),
            item("policies", "Company policies", "book", List.of(POLICIES_MANAGE), "Administration"),
            item("settings", "Settings", "sliders", List.of(ROLES_MANAGE), "Administration"),
            item("audit", "Audit log", "doc", List.of(ROLES_MANAGE), "Administration"),
            item("subscription", "Subscription", "receipt", List.of(BUSINESS_MANAGE), "Administration"));

    public static Optional<Item> find(String id) {
        return NAV.stream().filter(n -> n.id().equals(id)).findFirst();
    }

    public static boolean allowed(Item item, Set<String> perms, boolean hasReports) {
        return item.perm().stream().anyMatch(perms::contains) && (!item.needsReports() || hasReports);
    }

    public static boolean allowed(String navId, Set<String> perms, boolean hasReports) {
        return find(navId).map(i -> allowed(i, perms, hasReports)).orElse(false);
    }

    /** Sidebar items without module pages (used for the landing page). */
    public static List<Item> visible(Set<String> perms, boolean hasReports) {
        return visible(perms, hasReports, Set.of());
    }

    /** Sidebar items, including module pages for the business's {@code modules}. */
    public static List<Item> visible(Set<String> perms, boolean hasReports, Set<String> modules) {
        return NAV.stream().filter(n -> allowed(n, perms, hasReports) && (n.module() == null || modules.contains(n.module()))).toList();
    }

    /** Landing page after login: the platform console for the super admin, Home for anyone with `self`, Overview for the business admin. */
    public static String landingFor(Set<String> perms) {
        if (perms.contains(PLATFORM_MANAGE)) return "/platform";
        return perms.contains(SELF) ? "/home" : perms.contains(ROLES_MANAGE) ? "/overview" : "/directory";
    }
}
