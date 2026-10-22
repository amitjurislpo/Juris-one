package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.repository.AuditViewRepository;
import com.jurislpo.jurisone.repository.AuditViewRepository.LogRow;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** Audit log view (audit page). */
@Service
public class AuditViewService {

    /** Event groups for the filter; each matches action prefixes. */
    public static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("auth", List.of("auth."));
        GROUPS.put("people", List.of("employee."));
        GROUPS.put("roles", List.of("role.", "policy.", "asset.", "announcement."));
        GROUPS.put("time", List.of("leave.", "regularization.", "attendance."));
        GROUPS.put("org", List.of("department.", "holiday."));
        GROUPS.put("exit", List.of("exit."));
        GROUPS.put("payroll", List.of("salary.", "payroll."));
        GROUPS.put("tax", List.of("tax.", "declaration.", "proof."));
        GROUPS.put("letters", List.of("letter.", "letter_template."));
        GROUPS.put("requests", List.of("request."));
        GROUPS.put("pages", List.of("page."));
    }

    /** Salary / tax payloads are only shown to viewers who hold a salary permission (HR never sees pay). */
    static final List<String> SALARY_PREFIXES = Stream.concat(GROUPS.get("payroll").stream(), GROUPS.get("tax").stream()).toList();

    static final List<String> SORTS = List.of("occurredAt", "actor", "action");
    static final List<String> PERIODS = List.of("7", "30", "90", "365", "All");
    static final String UUIDISH = "^[0-9a-fA-F-]{36}$";

    /** {@code occurredAt} is the full ISO instant with milliseconds; {@code business} is set on the super admin's view only. */
    public record Row(String id, String occurredAt, String actor, String actorCode, String action, String entity, String entityId, String subject, String ipAddress,
                      JsonNode before, JsonNode after, boolean redacted, String method, String path, Integer status, Integer durationMs, String business) {}

    public record Option(String value, String label) {}

    private final AuditViewRepository repo;
    private final ObjectMapper json;

    public AuditViewService(AuditViewRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    /** Prisma {@code contains}: the search text is literal (LIKE wildcards escaped). */
    static String like(String search) {
        return "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** The business owner's audit log (row-level security limits it to their business). */
    public Map<String, Object> rows(CurrentUser user, Map<String, String> queryParams) {
        return query(user, queryParams, false);
    }

    /** The super admin's audit log: every business and the platform itself, filterable by business ({@code businessId}). */
    public Map<String, Object> platformRows(CurrentUser user, Map<String, String> queryParams) {
        return query(user, queryParams, true);
    }

    private record AuditQuery(String fromWhere, String order, List<Object> args, ListParams listParams) {}

    private AuditQuery build(Map<String, String> queryParams, boolean platform, int pageSize) {
        ListParams listParams = ListParams.of(queryParams, SORTS, "occurredAt", pageSize, "desc");
        List<String> groups = new ArrayList<>(List.of("All"));
        groups.addAll(GROUPS.keySet());
        String group = ListParams.pick(queryParams, "eventGroup", groups, "All");
        String period = ListParams.pick(queryParams, "periodDays", PERIODS, "30");
        String actorId = ListParams.text(queryParams, "actorId", "");
        String businessId = ListParams.text(queryParams, "businessId", "");

        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (!"All".equals(group)) {
            List<String> clauses = new ArrayList<>();
            for (String prefix : GROUPS.get(group)) {
                clauses.add("a.action LIKE ?");
                args.add(prefix.replace("_", "\\_") + "%");
            }
            conditions.add("(" + String.join(" OR ", clauses) + ")");
        }
        if (!"All".equals(period)) {
            conditions.add("a.at >= ?");
            args.add(Timestamp.from(Instant.ofEpochMilli(System.currentTimeMillis() - Long.parseLong(period) * 86_400_000L)));
        }
        if (actorId.matches(UUIDISH)) {
            conditions.add("a.actor_id = ?::uuid");
            args.add(actorId);
        }
        if (platform && businessId.matches(UUIDISH)) {
            conditions.add("a.business_id = ?::uuid");
            args.add(businessId);
        } else if (platform && "platform".equals(businessId)) {
            conditions.add("a.business_id IS NULL");
        }
        if (!listParams.search().isEmpty()) {
            String likePattern = like(listParams.search());
            conditions.add("(a.action ILIKE ? OR a.entity ILIKE ? OR a.entity_id ILIKE ? OR a.path ILIKE ? OR e.full_name ILIKE ? OR e.code ILIKE ?" + (platform ? " OR b.name ILIKE ?)" : ")"));
            for (int i = 0; i < (platform ? 7 : 6); i++) args.add(likePattern);
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        String dir = listParams.asc() ? "ASC" : "DESC";
        String order = switch (listParams.sort()) {
            case "actor" -> "e.full_name " + dir + ", a.at DESC";
            case "action" -> "a.action " + dir + ", a.at DESC";
            default -> "a.at " + dir;
        };
        String from = " FROM audit_log a LEFT JOIN employees e ON e.id = a.actor_id" + (platform ? " LEFT JOIN businesses b ON b.id = a.business_id" : "");
        return new AuditQuery(from + where, order, args, listParams);
    }

    private List<Row> rowsOf(CurrentUser user, List<LogRow> logs, boolean platform) {
        // Name the employee an entry is about (entity ids are employee uuids for these entities).
        String[] employeeIds = logs.stream().filter(l -> ("employee".equals(l.entity()) || "user".equals(l.entity())) && l.entityId() != null)
                .map(LogRow::entityId).distinct().filter(id -> id.matches(UUIDISH)).toArray(String[]::new);
        Map<String, String> subjects = employeeIds.length > 0 ? repo.employeeLabels(employeeIds) : new LinkedHashMap<>();

        boolean seePay = platform || user.can("pay.view", "struct.manage", "tax.view", "proofs.verify");
        return logs.stream().map(log -> {
            boolean redacted = !seePay && SALARY_PREFIXES.stream().anyMatch(p -> log.action().startsWith(p));
            return new Row(log.id(), Format.isoTimestamp(log.occurredAt()), log.actor() == null ? "System" : log.actor(), log.actorCode(), log.action(), log.entity(), log.entityId(),
                    log.entityId() == null ? null : subjects.get(log.entityId()), log.ipAddress(), redacted ? null : parse(log.before()), redacted ? null : parse(log.after()), redacted,
                    log.method(), log.path(), log.status(), log.durationMs(), platform ? (log.business() == null ? "Platform" : log.business()) : null);
        }).toList();
    }

    private Map<String, Object> query(CurrentUser user, Map<String, String> queryParams, boolean platform) {
        AuditQuery query = build(queryParams, platform, 50);
        List<Object> pageArgs = new ArrayList<>(query.args());
        pageArgs.add(query.listParams().skip());
        pageArgs.add(query.listParams().take());
        List<LogRow> logs = repo.findPage(query.fromWhere(), query.order(), pageArgs.toArray());
        long total = repo.count(query.fromWhere(), query.args().toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", query.listParams().page());
        out.put("take", query.listParams().take());
        out.put("total", total);
        out.put("rows", rowsOf(user, logs, platform));
        out.put("actors", repo.actorOptions());
        if (platform) out.put("businesses", repo.businessOptions());
        return out;
    }

    public static final int EXPORT_MAX = 50_000;

    /** Every row of the filter (at most {@link #EXPORT_MAX}) as CSV: the same columns and redaction as the page. */
    public String exportCsv(CurrentUser user, Map<String, String> queryParams, boolean platform) {
        AuditQuery query = build(queryParams, platform, EXPORT_MAX);
        List<Object> args = new ArrayList<>(query.args());
        args.add(0);
        args.add(EXPORT_MAX);
        List<Row> rows = rowsOf(user, repo.findPage(query.fromWhere(), query.order(), args.toArray()), platform);
        StringBuilder builder = new StringBuilder("At (UTC),Business,Actor,Actor code,Action,Entity,Entity id,Method,Path,Status,Duration ms,IP,Before,After\n");
        for (Row row : rows) {
            builder.append(String.join(",", cell(row.occurredAt()), cell(row.business()), cell(row.actor()), cell(row.actorCode()), cell(row.action()), cell(row.entity()), cell(row.entityId()),
                    cell(row.method()), cell(row.path()), cell(row.status() == null ? null : row.status().toString()),
                    cell(row.durationMs() == null ? null : row.durationMs().toString()), cell(row.ipAddress()),
                    cell(row.before() == null ? null : row.before().toString()), cell(row.after() == null ? null : row.after().toString()))).append('\n');
        }
        return builder.toString();
    }

    /** CSV cell; a value that starts like a formula is prefixed so a spreadsheet won't run it. */
    static String cell(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        if (!escaped.isEmpty() && "=+-@".indexOf(escaped.charAt(0)) >= 0) escaped = "'" + escaped;
        return "\"" + escaped + "\"";
    }

    private JsonNode parse(String text) {
        if (text == null) return null;
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
