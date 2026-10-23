package com.jurislpo.jurisone.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Permission catalogue (REQUIREMENTS §3.1). */
public final class Permissions {
    private Permissions() {}

    public static final String SELF = "self", DIR = "dir", ATT_ALL = "att.all", APPR_LEAVE = "appr.leave", APPR_REG = "appr.reg",
            PEOPLE_VIEW = "people.view", PEOPLE_ADD = "people.add", ACCESS_TOGGLE = "access.toggle", DEPT_MANAGE = "dept.manage",
            HOLIDAYS_MANAGE = "holidays.manage", PAY_VIEW = "pay.view", PAY_CLOSE = "pay.close", STRUCT_MANAGE = "struct.manage",
            TAX_VIEW = "tax.view", PROOFS_VERIFY = "proofs.verify", POLICIES_MANAGE = "policies.manage", ASSETS_MANAGE = "assets.manage",
            ANNOUNCEMENTS_MANAGE = "announcements.manage", LETTERS_MANAGE = "letters.manage", ROLES_MANAGE = "roles.manage";

    /** Task settings, and the "sees every project and task" exception (the Owner only; private access is audited). */
    public static final String TASKS_MANAGE = "tasks.manage";
    /** Create task projects (Owner and HR Manager by default). Using tasks needs no permission. */
    public static final String PROJECTS_CREATE = "projects.create";

    /**
     * The platform super admin's permission (businesses, plans, subscriptions). It is not part of the business catalogue
     * below, so no business role can ever be given it.
     */
    public static final String PLATFORM_MANAGE = "platform.manage";

    /** The business owner's (sub admin's) permission: subscription details and the employee export. Owner role only. */
    public static final String BUSINESS_MANAGE = "business.manage";

    /** key → label, in catalogue order. */
    public static final Map<String, String> LABELS;

    static {
        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put(SELF, "Own attendance, leave, payslips & tax declarations");
        descriptions.put(DIR, "Employee directory");
        descriptions.put(ATT_ALL, "View everyone’s attendance (with time zones)");
        descriptions.put(APPR_LEAVE, "Approve leave");
        descriptions.put(APPR_REG, "Approve attendance regularization");
        descriptions.put(PEOPLE_VIEW, "View employee records");
        descriptions.put(PEOPLE_ADD, "Add & edit employees");
        descriptions.put(ACCESS_TOGGLE, "Enable / disable portal access");
        descriptions.put(DEPT_MANAGE, "Create departments");
        descriptions.put(HOLIDAYS_MANAGE, "Manage holiday calendar");
        descriptions.put(LETTERS_MANAGE, "Issue HR letters & edit letter templates");
        descriptions.put(PAY_VIEW, "View & edit payroll");
        descriptions.put(PAY_CLOSE, "Close payroll & publish payslips");
        descriptions.put(STRUCT_MANAGE, "Salary structures");
        descriptions.put(TAX_VIEW, "Tax & TDS");
        descriptions.put(PROOFS_VERIFY, "Verify investment proofs");
        descriptions.put(POLICIES_MANAGE, "Publish company policies & see acknowledgements");
        descriptions.put(ASSETS_MANAGE, "Manage the asset register & assign assets");
        descriptions.put(ANNOUNCEMENTS_MANAGE, "Post announcements");
        descriptions.put(ROLES_MANAGE, "Roles, permissions & policies");
        descriptions.put(TASKS_MANAGE, "Task settings & every project and task");
        descriptions.put(PROJECTS_CREATE, "Create task projects");
        LABELS = java.util.Collections.unmodifiableMap(descriptions);
    }

    public static final List<String> KEYS = List.copyOf(LABELS.keySet());

    public record Group(String name, List<String> keys) {}

    public static final List<Group> GROUPS = List.of(
            new Group("Self-service", List.of(SELF, DIR)),
            new Group("People operations", List.of(ATT_ALL, APPR_LEAVE, APPR_REG, PEOPLE_VIEW, PEOPLE_ADD, ACCESS_TOGGLE, DEPT_MANAGE, HOLIDAYS_MANAGE, LETTERS_MANAGE)),
            new Group("Finance", List.of(PAY_VIEW, PAY_CLOSE, STRUCT_MANAGE, TAX_VIEW, PROOFS_VERIFY)),
            new Group("Administration", List.of(POLICIES_MANAGE, ASSETS_MANAGE, ANNOUNCEMENTS_MANAGE, ROLES_MANAGE)),
            new Group("Tasks", List.of(TASKS_MANAGE, PROJECTS_CREATE)));

    /** Permissions that expose salary / tax figures. HR roles must never hold these. */
    public static final Set<String> SALARY = Set.of(PAY_VIEW, PAY_CLOSE, STRUCT_MANAGE, TAX_VIEW, PROOFS_VERIFY);

    public static boolean isPermission(String text) {
        return LABELS.containsKey(text);
    }
}
