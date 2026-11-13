package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.service.EmployeeNameDirectory;
import com.jurislpo.jurisone.service.EmployeeNameDirectory.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * People list filters from the URL → SQL. Used by the People page and its CSV export.
 * Joins: e employee, d department, m reporting manager, r role.
 */
public final class PeopleQuery {

    public static final List<String> SORTS = List.of("name", "code", "department", "manager", "location", "role", "status", "access", "dateOfJoining");
    public static final List<String> STATUS = List.of("Current", "ACTIVE", "PROBATION", "ONBOARDING", "EXITED", "All");
    public static final List<String> ACCESS = List.of("All", "Enabled", "Disabled");

    public static final String FROM = """
        FROM employees e
        LEFT JOIN departments d ON d.id = e.department_id
        LEFT JOIN employees m ON m.id = e.reporting_manager_id
        JOIN roles r ON r.id = e.role_id""";

    public final ListParams listParams;
    public final String where;
    public final List<Object> args = new ArrayList<>();
    public final String orderBy;
    /** Values the {@link #orderBy} binds (the name ranking when names are sorted in memory); they come after the {@link #args}. */
    public final List<Object> orderArgs = new ArrayList<>();

    /** Prisma {@code contains, mode: insensitive} → ILIKE with the LIKE wildcards escaped. */
    public static String like(String search) {
        return "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public PeopleQuery(Map<String, String> queryParams, EmployeeNameDirectory names) {
        this(queryParams, null, names);
    }

    /**
     * Owners (the business admin) are never listed or exported; {@code viewer} (the signed-in person) is left out of
     * their own list.
     */
    public PeopleQuery(Map<String, String> queryParams, java.util.UUID viewer, EmployeeNameDirectory names) {
        listParams = ListParams.of(queryParams, SORTS, "name");
        String status = ListParams.pick(queryParams, "status", STATUS, "Current");
        String access = ListParams.pick(queryParams, "access", ACCESS, "All");
        String department = ListParams.text(queryParams, "departmentId", "All");
        List<String> conditions = new ArrayList<>();
        conditions.add("r.key NOT IN ('owner', 'super_admin')"); // the business admin (or the platform's super admin) is never listed
        if (viewer != null) {
            conditions.add("e.id <> ?");
            args.add(viewer);
        }
        if ("Current".equals(status)) conditions.add("e.status <> 'EXITED'");
        else if (!"All".equals(status)) {
            conditions.add("e.status = ?::\"EmployeeStatus\"");
            args.add(status);
        }
        if (!"All".equals(access)) {
            conditions.add("e.portal_access = ?");
            args.add("Enabled".equals(access));
        }
        if (!"All".equals(department) && department.matches("^[0-9a-f-]{36}$")) {
            conditions.add("e.department_id = ?::uuid");
            args.add(department);
        }
        if (!listParams.search().isEmpty()) {
            String search = listParams.search();
            String nameMatch = names.contains(Field.FULL_NAME, "e.id", "e.full_name", search, args);
            args.add(like(search));
            args.add(like(search));
            String designationMatch = names.contains(Field.DESIGNATION, "e.id", "e.designation", search, args);
            String managerMatch = names.contains(Field.FULL_NAME, "m.id", "m.full_name", search, args);
            conditions.add("(" + nameMatch + " OR e.code ILIKE ? OR e.email ILIKE ? OR " + designationMatch + " OR " + managerMatch + ")");
        }
        where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        String direction = listParams.asc() ? "ASC" : "DESC";
        boolean isAscending = listParams.asc();
        orderBy = " ORDER BY " + switch (listParams.sort()) {
            case "code" -> "e.code " + direction;
            case "department" -> "d.name " + direction + nameTieBreak(names);
            case "manager" -> names.orderBy(Field.FULL_NAME, "m.id", "m.full_name", isAscending, orderArgs) + nameTieBreak(names);
            case "location" -> "e.region " + direction + ", e.time_zone " + direction + nameTieBreak(names);
            case "role" -> "r.name " + direction + nameTieBreak(names);
            case "status" -> "e.status " + direction + nameTieBreak(names);
            case "access" -> "e.portal_access " + direction + nameTieBreak(names);
            case "dateOfJoining" -> "e.date_of_joining " + direction;
            default -> names.orderBy(Field.FULL_NAME, "e.id", "e.full_name", isAscending, orderArgs);
        };
    }

    private String nameTieBreak(EmployeeNameDirectory names) {
        return ", " + names.orderBy(Field.FULL_NAME, "e.id", "e.full_name", true, orderArgs);
    }
}
