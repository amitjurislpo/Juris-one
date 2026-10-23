package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.TaskDueDates;
import com.jurislpo.jurisone.engine.TaskKeys;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Task list filters from the URL → SQL (plan §13), with {@code ?} arguments only. One builder serves every task collection
 * (all visible tasks, My tasks, Team tasks, a project board, one task). Joins: t task, s status, p project, pt/pp parent task and its
 * project, ae/ie/re the direct assignee, indirect assignee and reporter; {@code clock.local_now} is "now" in the task time zone.
 */
public final class TaskQuery {

    public static final List<String> SORTS = List.of("dueDate", "priority", "updatedAt", "createdAt", "key", "title", "status", "assignee");
    public static final List<String> RELATIONS = List.of("ASSIGNED", "DIRECT", "INDIRECT", "REPORTED", "WATCHING");
    public static final List<String> CATEGORIES = List.of("TODO", "IN_PROGRESS", "DONE");
    public static final List<String> PRIORITIES = List.of("URGENT", "HIGH", "MEDIUM", "LOW", "NONE");
    public static final List<String> TYPES = List.of("TASK", "ISSUE", "REQUEST", "MILESTONE");
    public static final List<String> DUE_BUCKETS = List.of(TaskDueDates.OVERDUE, TaskDueDates.TODAY, TaskDueDates.THIS_WEEK, TaskDueDates.LATER, TaskDueDates.NO_DATE);
    public static final int DEFAULT_PAGE_SIZE = 50, MAX_PAGE_SIZE = 500;
    static final String UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    /** Which tasks a collection starts from (before the filters). */
    public enum Scope {
        /** Every task the user can see (the Owner: all). */
        VISIBLE,
        /** The user's own tasks, by {@code relation}. */
        MINE,
        /** Tasks whose direct assignee is in the user's reporting scope ({@code depth}). */
        TEAM,
        /** Every task of one project whose view the caller has already checked. */
        PROJECT
    }

    static final String ISO = "YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"";

    public static String iso(String column) {
        return "to_char(" + column + " AT TIME ZONE 'UTC', '" + ISO + "')";
    }

    static String photo(String alias) {
        return "(extract(epoch FROM " + alias + ".photo_updated_at) * 1000)::bigint";
    }

    /** "Overdue" in SQL, the same rule as {@link TaskDueDates#isOverdue}. */
    public static final String OVERDUE = "(t.completed_at IS NULL AND t.due_date IS NOT NULL AND CASE WHEN t.due_time IS NULL THEN t.due_date < clock.local_now::date "
            + "ELSE (t.due_date + t.due_time) <= clock.local_now END)";
    static final String TODAY = "clock.local_now::date";
    static final String END_OF_WEEK = "(date_trunc('week', clock.local_now)::date + 6)";

    public static final String FROM = """
        FROM tasks t
        JOIN task_statuses s ON s.id = t.status_id
        LEFT JOIN projects p ON p.id = t.project_id
        LEFT JOIN tasks pt ON pt.id = t.parent_task_id
        LEFT JOIN projects pp ON pp.id = pt.project_id
        LEFT JOIN employees ae ON ae.id = t.assignee_id
        LEFT JOIN employees ie ON ie.id = t.indirect_assignee_id
        JOIN employees re ON re.id = t.reporter_id
        CROSS JOIN LATERAL (SELECT coalesce(ae.time_zone, ie.time_zone, re.time_zone, 'Asia/Kolkata') AS zone) tz
        CROSS JOIN LATERAL (SELECT (now() AT TIME ZONE tz.zone) AS local_now) clock""";

    static final String COUNTS = """
        LEFT JOIN LATERAL (SELECT count(*) AS total, count(*) FILTER (WHERE ss.category = 'DONE') AS done FROM tasks st JOIN task_statuses ss ON ss.id = st.status_id
                           WHERE st.parent_task_id = t.id AND st.deleted_at IS NULL) subtasks ON true
        LEFT JOIN LATERAL (SELECT count(*) AS total, count(*) FILTER (WHERE c.is_done) AS done FROM task_checklist_items c WHERE c.task_id = t.id) checklist ON true
        LEFT JOIN LATERAL (SELECT count(*) AS total FROM task_comments tc WHERE tc.task_id = t.id AND tc.deleted_at IS NULL) comments ON true
        LEFT JOIN LATERAL (SELECT count(*) AS total FROM task_attachments ta WHERE ta.task_id = t.id AND ta.deleted_at IS NULL) attachments ON true""";

    /** The columns of a TaskRow (plan §12.4) except {@code labels}; two arguments: the viewer's id twice (for {@code myAssignment}). */
    static final String COLUMNS = """
        SELECT t.id::text AS "id", CASE WHEN p.id IS NULL THEN NULL ELSE p.key || '-' || t.number END AS "key", t.number AS "number",
               t.project_id::text AS "projectId", p.key AS "projectKey", p.name AS "projectName",
               t.parent_task_id::text AS "parentTaskId", CASE WHEN pp.id IS NULL THEN NULL ELSE pp.key || '-' || pt.number END AS "parentKey",
               t.title AS "title", t.type AS "type", t.status_id::text AS "statusId", s.name AS "statusName", s.category AS "statusCategory", s.color AS "statusColor",
               t.priority AS "priority",
               t.assignee_id::text AS "assigneeId", ae.full_name AS "assigneeName", ae.code AS "assigneeCode", %s AS "assigneePhoto",
               CASE WHEN ae.id IS NULL THEN NULL ELSE ae.status <> 'EXITED' END AS "isAssigneeActive",
               t.indirect_assignee_id::text AS "indirectAssigneeId", ie.full_name AS "indirectAssigneeName", ie.code AS "indirectAssigneeCode", %s AS "indirectAssigneePhoto",
               CASE WHEN ie.id IS NULL THEN NULL ELSE ie.status <> 'EXITED' END AS "isIndirectAssigneeActive",
               CASE WHEN t.assignee_id = ? THEN 'DIRECT' WHEN t.indirect_assignee_id = ? THEN 'INDIRECT' END AS "myAssignment",
               t.reporter_id::text AS "reporterId", re.full_name AS "reporterName",
               t.start_date::text AS "startDate", t.due_date::text AS "dueDate", to_char(t.due_time, 'HH24:MI') AS "dueTime", tz.zone AS "dueTimeZone",
               t.estimate_minutes AS "estimateMinutes", %s AS "isOverdue",
               subtasks.total AS "subtaskCount", subtasks.done AS "subtaskDoneCount", checklist.total AS "checklistCount", checklist.done AS "checklistDoneCount",
               comments.total AS "commentCount", attachments.total AS "attachmentCount", t.position AS "position", t.version AS "version",
               %s AS "completedAt", %s AS "createdAt", %s AS "updatedAt\""""
            .formatted(photo("ae"), photo("ie"), OVERDUE, iso("t.completed_at"), iso("t.created_at"), iso("t.updated_at"));

    public final ListParams listParams;
    public final int pageSize;
    private final String withClause;
    private final List<Object> withArgs = new ArrayList<>();
    private final List<String> conditions = new ArrayList<>();
    private final List<Object> args = new ArrayList<>();
    private final UUID viewerId;
    private final String orderBy;

    /**
     * @param viewerId    the signed-in employee
     * @param isTaskAdmin holds {@code tasks.manage}: sees every task
     * @param scope       the starting set
     * @param depth       reporting-line depth for {@link Scope#TEAM} (and 2 for "manager of the assignee" visibility)
     * @param isDueFilterApplied false to ignore {@code due} (the bucket counts of My tasks)
     */
    public TaskQuery(Map<String, String> queryParams, UUID viewerId, boolean isTaskAdmin, Scope scope, int depth, boolean isDueFilterApplied) {
        this.viewerId = viewerId;
        listParams = ListParams.of(queryParams, SORTS, "dueDate", pageSize(queryParams), "asc");
        pageSize = listParams.take();
        withClause = """
            WITH RECURSIVE team(employee_id, depth) AS (
                SELECT id, 1 FROM employees WHERE reporting_manager_id = ? AND status <> 'EXITED'
                UNION ALL
                SELECT e.id, team.depth + 1 FROM employees e JOIN team ON e.reporting_manager_id = team.employee_id WHERE team.depth < ? AND e.status <> 'EXITED') """;
        withArgs.add(viewerId);
        withArgs.add(scope == Scope.TEAM ? depth : 2);

        conditions.add("t.deleted_at IS NULL");
        conditions.add("(p.id IS NULL OR p.deleted_at IS NULL)");
        switch (scope) {
            case VISIBLE -> addVisibility(isTaskAdmin);
            case MINE -> {
                String relation = ListParams.pick(queryParams, "relation", RELATIONS, "ASSIGNED");
                switch (relation) {
                    case "DIRECT" -> add("t.assignee_id = ?", viewerId);
                    case "INDIRECT" -> add("t.indirect_assignee_id = ?", viewerId);
                    case "REPORTED" -> add("t.reporter_id = ?", viewerId);
                    case "WATCHING" -> add("EXISTS (SELECT 1 FROM task_watchers w WHERE w.task_id = t.id AND w.employee_id = ?)", viewerId);
                    default -> add("(t.assignee_id = ? OR t.indirect_assignee_id = ?)", viewerId, viewerId);
                }
            }
            case TEAM -> conditions.add("t.assignee_id IN (SELECT employee_id FROM team)");
            case PROJECT -> { }
        }
        boolean isOpenByDefault = scope == Scope.MINE || scope == Scope.TEAM;
        addFilters(queryParams, isOpenByDefault, isDueFilterApplied, scope == Scope.PROJECT);
        orderBy = orderBy(listParams);
    }

    static int pageSize(Map<String, String> queryParams) {
        try {
            int size = Integer.parseInt(ListParams.text(queryParams, "pageSize", String.valueOf(DEFAULT_PAGE_SIZE)).trim());
            return size < 1 ? DEFAULT_PAGE_SIZE : Math.min(MAX_PAGE_SIZE, size);
        } catch (NumberFormatException e) {
            return DEFAULT_PAGE_SIZE;
        }
    }

    private void add(String condition, Object... values) {
        conditions.add(condition);
        args.addAll(Arrays.asList(values));
    }

    private void addVisibility(boolean isTaskAdmin) {
        add("(?::boolean OR p.visibility = 'BUSINESS' OR EXISTS (SELECT 1 FROM project_members m WHERE m.project_id = t.project_id AND m.employee_id = ?) "
                        + "OR t.assignee_id = ? OR t.indirect_assignee_id = ? OR t.reporter_id = ? "
                        + "OR EXISTS (SELECT 1 FROM task_watchers w WHERE w.task_id = t.id AND w.employee_id = ?) OR t.assignee_id IN (SELECT employee_id FROM team))",
                isTaskAdmin, viewerId, viewerId, viewerId, viewerId, viewerId);
    }

    static boolean isUuid(String text) {
        return text != null && text.matches(UUID_PATTERN);
    }

    /** Values of a comma list that are allowed, in order; empty = no filter. */
    static List<String> commaList(Map<String, String> queryParams, String key, List<String> allowed, String defaultValue) {
        String text = ListParams.text(queryParams, key, defaultValue);
        List<String> values = new ArrayList<>();
        for (String part : text.split(",")) {
            String value = part.trim().toUpperCase(Locale.ROOT);
            if (allowed.contains(value) && !values.contains(value)) values.add(value);
        }
        return values;
    }

    private void addIn(String column, List<String> values) {
        if (values.isEmpty()) return;
        add(column + " IN (" + String.join(", ", values.stream().map(v -> "?").toList()) + ")", values.toArray());
    }

    private void addPerson(String column, String value) {
        if ("none".equalsIgnoreCase(value)) conditions.add(column + " IS NULL");
        else if (isUuid(value)) add(column + " = ?::uuid", value);
    }

    private void addFilters(Map<String, String> queryParams, boolean isOpenByDefault, boolean isDueFilterApplied, boolean isOneProject) {
        String projectId = ListParams.text(queryParams, "projectId", "");
        if (isUuid(projectId)) add("t.project_id = ?::uuid", projectId);
        boolean isArchivedIncluded = isOneProject || isUuid(projectId) || "true".equals(ListParams.text(queryParams, "isArchivedProjectIncluded", "false"));
        if (!isArchivedIncluded) conditions.add("(p.id IS NULL OR p.status = 'ACTIVE')");
        String statusId = ListParams.text(queryParams, "statusId", "");
        if (isUuid(statusId)) add("t.status_id = ?::uuid", statusId);
        addIn("s.category", commaList(queryParams, "statusCategory", CATEGORIES, isOpenByDefault ? "TODO,IN_PROGRESS" : ""));
        addPerson("t.assignee_id", ListParams.text(queryParams, "assigneeId", ""));
        addPerson("t.indirect_assignee_id", ListParams.text(queryParams, "indirectAssigneeId", ""));
        String reporterId = ListParams.text(queryParams, "reporterId", "");
        if (isUuid(reporterId)) add("t.reporter_id = ?::uuid", reporterId);
        addIn("t.priority", commaList(queryParams, "priority", PRIORITIES, ""));
        addIn("t.type", commaList(queryParams, "type", TYPES, ""));
        String labelId = ListParams.text(queryParams, "labelId", "");
        if (isUuid(labelId)) add("EXISTS (SELECT 1 FROM task_label_links ll WHERE ll.task_id = t.id AND ll.label_id = ?::uuid)", labelId);
        if (isDueFilterApplied) {
            switch (ListParams.pick(queryParams, "due", DUE_BUCKETS, "")) {
                case TaskDueDates.OVERDUE -> conditions.add(OVERDUE);
                case TaskDueDates.TODAY -> conditions.add("t.due_date = " + TODAY + " AND NOT " + OVERDUE);
                case TaskDueDates.THIS_WEEK -> conditions.add("t.due_date > " + TODAY + " AND t.due_date <= " + END_OF_WEEK);
                case TaskDueDates.LATER -> conditions.add("t.due_date > " + END_OF_WEEK);
                case TaskDueDates.NO_DATE -> conditions.add("t.due_date IS NULL");
                default -> { }
            }
        }
        String dueFrom = ListParams.text(queryParams, "dueFrom", "");
        if (dueFrom.matches("^\\d{4}-\\d{2}-\\d{2}$")) add("t.due_date >= ?::date", dueFrom);
        String dueTo = ListParams.text(queryParams, "dueTo", "");
        if (dueTo.matches("^\\d{4}-\\d{2}-\\d{2}$")) add("t.due_date <= ?::date", dueTo);
        String parentTaskId = ListParams.text(queryParams, "parentTaskId", "");
        if (isUuid(parentTaskId)) add("t.parent_task_id = ?::uuid", parentTaskId);
        addSearch(listParams.search());
    }

    /** {@code word1:* & word2:*} from the letters and digits of the search text; empty when there are none. */
    public static String prefixQuery(String search) {
        List<String> words = new ArrayList<>();
        for (String word : search.split("[^\\p{L}\\p{N}]+")) if (!word.isEmpty()) words.add(word.toLowerCase(Locale.ROOT) + ":*");
        return String.join(" & ", words);
    }

    private void addSearch(String search) {
        if (search.isEmpty()) return;
        Optional<TaskKeys.ParsedKey> key = TaskKeys.parseKey(search);
        if (key.isPresent()) {
            String formatted = TaskKeys.formatKey(key.get().projectKey(), key.get().number());
            add("((p.key = ? AND t.number = ?) OR ? = ANY (t.previous_keys))", key.get().projectKey(), key.get().number(), formatted);
            return;
        }
        String query = prefixQuery(search);
        if (!query.isEmpty()) add("t.search_vector @@ to_tsquery('simple', ?)", query);
    }

    static String orderBy(ListParams listParams) {
        String direction = listParams.asc() ? "ASC" : "DESC";
        String order = switch (listParams.sort()) {
            case "priority" -> "CASE t.priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 3 ELSE 4 END " + direction
                    + ", t.due_date ASC NULLS LAST";
            case "updatedAt" -> "t.updated_at " + direction;
            case "createdAt" -> "t.created_at " + direction;
            case "key" -> "p.key " + direction + " NULLS LAST, t.number " + direction;
            case "title" -> "lower(t.title) " + direction;
            case "status" -> "CASE s.category WHEN 'TODO' THEN 0 WHEN 'IN_PROGRESS' THEN 1 ELSE 2 END " + direction + ", s.position " + direction;
            case "assignee" -> "ae.full_name " + direction + " NULLS LAST";
            // a date-only task is due at the end of its day, so it sorts after the timed tasks of that day; no due date last
            default -> listParams.asc() ? "t.due_date ASC NULLS LAST, t.due_time ASC NULLS LAST" : "t.due_date DESC NULLS LAST, t.due_time DESC NULLS FIRST";
        };
        return " ORDER BY " + order + ", t.created_at DESC, t.id";
    }

    /** Only this task (the task panel). */
    public TaskQuery restrictToTask(UUID taskId) {
        add("t.id = ?", taskId);
        return this;
    }

    /** Only tasks completed within the last {@code days} days (board done columns), or before that ({@code isWithin} false). */
    public TaskQuery restrictToCompleted(int days, boolean isWithin) {
        add("t.completed_at " + (isWithin ? ">=" : "<") + " now() - make_interval(days => ?)", days);
        return this;
    }

    private String where() {
        return " WHERE " + String.join(" AND ", conditions);
    }

    /** One page of TaskRows (without labels). */
    public String rowsSql() {
        return withClause + COLUMNS + "\n" + FROM + "\n" + COUNTS + where() + orderBy + " LIMIT ? OFFSET ?";
    }

    public Object[] rowsArgs() {
        List<Object> all = new ArrayList<>(withArgs);
        all.add(viewerId);
        all.add(viewerId);
        all.addAll(args);
        all.add(listParams.take());
        all.add(listParams.skip());
        return all.toArray();
    }

    /** Every matching TaskRow in board order (status position, card position), at most {@code limit}. */
    public String boardSql() {
        return withClause + COLUMNS + "\n" + FROM + "\n" + COUNTS + where() + " ORDER BY s.position, t.position, t.created_at LIMIT ?";
    }

    public Object[] boardArgs(int limit) {
        List<Object> all = new ArrayList<>(withArgs);
        all.add(viewerId);
        all.add(viewerId);
        all.addAll(args);
        all.add(limit);
        return all.toArray();
    }

    public String countSql() {
        return withClause + "SELECT count(*) " + FROM + where();
    }

    /** Matching tasks per step: {@code status_id, total}. */
    public String statusCountsSql() {
        return withClause + "SELECT t.status_id AS status_id, count(*) AS total " + FROM + where() + " GROUP BY t.status_id";
    }

    public Object[] countArgs() {
        List<Object> all = new ArrayList<>(withArgs);
        all.addAll(args);
        return all.toArray();
    }

    /** The due buckets of the matching tasks: overdue, today, thisWeek, later, noDate. */
    public String bucketCountsSql() {
        return withClause + "SELECT count(*) FILTER (WHERE " + OVERDUE + ") AS \"overdue\", "
                + "count(*) FILTER (WHERE t.due_date = " + TODAY + " AND NOT " + OVERDUE + ") AS \"today\", "
                + "count(*) FILTER (WHERE t.due_date > " + TODAY + " AND t.due_date <= " + END_OF_WEEK + ") AS \"thisWeek\", "
                + "count(*) FILTER (WHERE t.due_date > " + END_OF_WEEK + ") AS \"later\", "
                + "count(*) FILTER (WHERE t.due_date IS NULL) AS \"noDate\" " + FROM + where();
    }
}
