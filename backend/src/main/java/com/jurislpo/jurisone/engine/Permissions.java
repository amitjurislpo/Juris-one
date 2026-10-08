package com.jurislpo.jurisone.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Permission catalogue (REQUIREMENTS §3.1). Port of src/lib/permissions.ts. */
public final class Permissions {
    private Permissions() {}

    public static final String SELF = "self", DIR = "dir", ATT_ALL = "att.all", APPR_LEAVE = "appr.leave", APPR_REG = "appr.reg",
            PEOPLE_VIEW = "people.view", PEOPLE_ADD = "people.add", ACCESS_TOGGLE = "access.toggle", DEPT_MANAGE = "dept.manage",
            HOLIDAYS_MANAGE = "holidays.manage", PAY_VIEW = "pay.view", PAY_CLOSE = "pay.close", STRUCT_MANAGE = "struct.manage",
            TAX_VIEW = "tax.view", PROOFS_VERIFY = "proofs.verify", ROLES_MANAGE = "roles.manage";

    /**
     * The platform super admin's permission (businesses, plans, subscriptions). It is not part of the business catalogue
     * below, so no business role can ever be given it.
     */
    public static final String PLATFORM_MANAGE = "platform.manage";

    /** key → label, in catalogue order. */
    public static final Map<String, String> LABELS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(SELF, "Own attendance, leave, payslips & tax declarations");
        m.put(DIR, "Employee directory");
        m.put(ATT_ALL, "View everyone’s attendance (with time zones)");
        m.put(APPR_LEAVE, "Approve leave");
        m.put(APPR_REG, "Approve attendance regularization");
        m.put(PEOPLE_VIEW, "View employee records");
        m.put(PEOPLE_ADD, "Add & edit employees");
        m.put(ACCESS_TOGGLE, "Enable / disable portal access");
        m.put(DEPT_MANAGE, "Create departments");
        m.put(HOLIDAYS_MANAGE, "Manage holiday calendar");
        m.put(PAY_VIEW, "View & edit payroll");
        m.put(PAY_CLOSE, "Close payroll & publish payslips");
        m.put(STRUCT_MANAGE, "Salary structures");
        m.put(TAX_VIEW, "Tax & TDS");
        m.put(PROOFS_VERIFY, "Verify investment proofs");
        m.put(ROLES_MANAGE, "Roles, permissions & policies");
        LABELS = java.util.Collections.unmodifiableMap(m);
    }

    public static final List<String> KEYS = List.copyOf(LABELS.keySet());

    public record Group(String name, List<String> keys) {}

    public static final List<Group> GROUPS = List.of(
            new Group("Self-service", List.of(SELF, DIR)),
            new Group("People operations", List.of(ATT_ALL, APPR_LEAVE, APPR_REG, PEOPLE_VIEW, PEOPLE_ADD, ACCESS_TOGGLE, DEPT_MANAGE, HOLIDAYS_MANAGE)),
            new Group("Finance", List.of(PAY_VIEW, PAY_CLOSE, STRUCT_MANAGE, TAX_VIEW, PROOFS_VERIFY)),
            new Group("Administration", List.of(ROLES_MANAGE)));

    /** Permissions that expose salary / tax figures. HR roles must never hold these. */
    public static final Set<String> SALARY = Set.of(PAY_VIEW, PAY_CLOSE, STRUCT_MANAGE, TAX_VIEW, PROOFS_VERIFY);

    public static boolean isPermission(String s) {
        return LABELS.containsKey(s);
    }
}
