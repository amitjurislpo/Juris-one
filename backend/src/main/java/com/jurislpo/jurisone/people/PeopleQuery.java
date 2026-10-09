package com.jurislpo.jurisone.people;

import com.jurislpo.jurisone.common.ListParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * People list filters from the URL → SQL (src/lib/people-query.ts). Used by the People page and its CSV export.
 * Joins: e employee, d department, m reporting manager, r role.
 */
public final class PeopleQuery {

    public static final List<String> SORTS = List.of("name", "code", "dept", "manager", "loc", "role", "status", "access", "doj");
    public static final List<String> STATUS = List.of("Current", "ACTIVE", "PROBATION", "ONBOARDING", "EXITED", "All");
    public static final List<String> ACCESS = List.of("All", "Enabled", "Disabled");

    public static final String FROM = """
        FROM employees e
        LEFT JOIN departments d ON d.id = e.department_id
        LEFT JOIN employees m ON m.id = e.reporting_manager_id
        JOIN roles r ON r.id = e.role_id""";

    public final ListParams ls;
    public final String where;
    public final List<Object> args = new ArrayList<>();
    public final String orderBy;

    /** Prisma {@code contains, mode: insensitive} → ILIKE with the LIKE wildcards escaped. */
    public static String like(String q) {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public PeopleQuery(Map<String, String> sp) {
        this(sp, null);
    }

    /**
     * Owners (the business admin) are never listed or exported; {@code viewer} (the signed-in person) is left out of
     * their own list.
     */
    public PeopleQuery(Map<String, String> sp, java.util.UUID viewer) {
        ls = ListParams.of(sp, SORTS, "name");
        String status = ListParams.pick(sp, "status", STATUS, "Current");
        String access = ListParams.pick(sp, "access", ACCESS, "All");
        String dept = ListParams.str(sp, "dept", "All");
        List<String> and = new ArrayList<>();
        and.add("r.key NOT IN ('owner', 'super_admin')"); // the business admin (or the platform's super admin) is never listed
        if (viewer != null) {
            and.add("e.id <> ?");
            args.add(viewer);
        }
        if ("Current".equals(status)) and.add("e.status <> 'EXITED'");
        else if (!"All".equals(status)) {
            and.add("e.status = ?::\"EmployeeStatus\"");
            args.add(status);
        }
        if (!"All".equals(access)) {
            and.add("e.portal_access = ?");
            args.add("Enabled".equals(access));
        }
        if (!"All".equals(dept) && dept.matches("^[0-9a-f-]{36}$")) {
            and.add("e.department_id = ?::uuid");
            args.add(dept);
        }
        if (!ls.q().isEmpty()) {
            and.add("(e.full_name ILIKE ? OR e.code ILIKE ? OR e.email ILIKE ? OR e.designation ILIKE ? OR m.full_name ILIKE ?)");
            for (int i = 0; i < 5; i++) args.add(like(ls.q()));
        }
        where = and.isEmpty() ? "" : " WHERE " + String.join(" AND ", and);
        String d = ls.asc() ? "ASC" : "DESC";
        orderBy = " ORDER BY " + switch (ls.sort()) {
            case "code" -> "e.code " + d;
            case "dept" -> "d.name " + d + ", e.full_name ASC";
            case "manager" -> "m.full_name " + d + ", e.full_name ASC";
            case "loc" -> "e.region " + d + ", e.time_zone " + d + ", e.full_name ASC";
            case "role" -> "r.name " + d + ", e.full_name ASC";
            case "status" -> "e.status " + d + ", e.full_name ASC";
            case "access" -> "e.portal_access " + d + ", e.full_name ASC";
            case "doj" -> "e.date_of_joining " + d;
            default -> "e.full_name " + d;
        };
    }
}
