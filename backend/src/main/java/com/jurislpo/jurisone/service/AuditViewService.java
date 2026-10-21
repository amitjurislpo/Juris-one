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

    static final List<String> SORTS = List.of("at", "actor", "action");
    static final List<String> PERIODS = List.of("7", "30", "90", "365", "All");
    static final String UUIDISH = "^[0-9a-fA-F-]{36}$";

    /** {@code at} is the full ISO instant with milliseconds; {@code business} is set on the super admin's view only. */
    public record Row(String id, String at, String actor, String actorCode, String action, String entity, String entityId, String subject, String ip,
                      JsonNode before, JsonNode after, boolean redacted, String method, String path, Integer status, Integer durationMs, String business) {}

    public record Option(String v, String l) {}

    private final AuditViewRepository repo;
    private final ObjectMapper json;

    public AuditViewService(AuditViewRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    /** Prisma {@code contains}: the search text is literal (LIKE wildcards escaped). */
    static String like(String q) {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** The business owner's audit log (row-level security limits it to their business). */
    public Map<String, Object> rows(CurrentUser u, Map<String, String> sp) {
        return query(u, sp, false);
    }

    /** The super admin's audit log: every business and the platform itself, filterable by business ({@code biz}). */
    public Map<String, Object> platformRows(CurrentUser u, Map<String, String> sp) {
        return query(u, sp, true);
    }

    private record Q(String fromWhere, String order, List<Object> args, ListParams ls) {}

    private Q build(Map<String, String> sp, boolean platform, int pageSize) {
        ListParams ls = ListParams.of(sp, SORTS, "at", pageSize, "desc");
        List<String> groups = new ArrayList<>(List.of("All"));
        groups.addAll(GROUPS.keySet());
        String group = ListParams.pick(sp, "ev", groups, "All");
        String period = ListParams.pick(sp, "days", PERIODS, "30");
        String actorId = ListParams.str(sp, "actor", "");
        String biz = ListParams.str(sp, "biz", "");

        List<String> and = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (!"All".equals(group)) {
            List<String> or = new ArrayList<>();
            for (String p : GROUPS.get(group)) {
                or.add("a.action LIKE ?");
                args.add(p.replace("_", "\\_") + "%");
            }
            and.add("(" + String.join(" OR ", or) + ")");
        }
        if (!"All".equals(period)) {
            and.add("a.at >= ?");
            args.add(Timestamp.from(Instant.ofEpochMilli(System.currentTimeMillis() - Long.parseLong(period) * 86_400_000L)));
        }
        if (actorId.matches(UUIDISH)) {
            and.add("a.actor_id = ?::uuid");
            args.add(actorId);
        }
        if (platform && biz.matches(UUIDISH)) {
            and.add("a.business_id = ?::uuid");
            args.add(biz);
        } else if (platform && "platform".equals(biz)) {
            and.add("a.business_id IS NULL");
        }
        if (!ls.q().isEmpty()) {
            String l = like(ls.q());
            and.add("(a.action ILIKE ? OR a.entity ILIKE ? OR a.entity_id ILIKE ? OR a.path ILIKE ? OR e.full_name ILIKE ? OR e.code ILIKE ?" + (platform ? " OR b.name ILIKE ?)" : ")"));
            for (int i = 0; i < (platform ? 7 : 6); i++) args.add(l);
        }
        String where = and.isEmpty() ? "" : " WHERE " + String.join(" AND ", and);
        String dir = ls.asc() ? "ASC" : "DESC";
        String order = switch (ls.sort()) {
            case "actor" -> "e.full_name " + dir + ", a.at DESC";
            case "action" -> "a.action " + dir + ", a.at DESC";
            default -> "a.at " + dir;
        };
        String from = " FROM audit_log a LEFT JOIN employees e ON e.id = a.actor_id" + (platform ? " LEFT JOIN businesses b ON b.id = a.business_id" : "");
        return new Q(from + where, order, args, ls);
    }

    private List<Row> rowsOf(CurrentUser u, List<LogRow> logs, boolean platform) {
        // Name the employee an entry is about (entity ids are employee uuids for these entities).
        String[] empIds = logs.stream().filter(l -> ("employee".equals(l.entity()) || "user".equals(l.entity())) && l.entityId() != null)
                .map(LogRow::entityId).distinct().filter(id -> id.matches(UUIDISH)).toArray(String[]::new);
        Map<String, String> subjects = empIds.length > 0 ? repo.employeeLabels(empIds) : new LinkedHashMap<>();

        boolean seePay = platform || u.can("pay.view", "struct.manage", "tax.view", "proofs.verify");
        return logs.stream().map(l -> {
            boolean redacted = !seePay && SALARY_PREFIXES.stream().anyMatch(p -> l.action().startsWith(p));
            return new Row(l.id(), Format.jsIso(l.at()), l.actor() == null ? "System" : l.actor(), l.actorCode(), l.action(), l.entity(), l.entityId(),
                    l.entityId() == null ? null : subjects.get(l.entityId()), l.ip(), redacted ? null : parse(l.before()), redacted ? null : parse(l.after()), redacted,
                    l.method(), l.path(), l.status(), l.durationMs(), platform ? (l.business() == null ? "Platform" : l.business()) : null);
        }).toList();
    }

    private Map<String, Object> query(CurrentUser u, Map<String, String> sp, boolean platform) {
        Q q = build(sp, platform, 50);
        List<Object> pageArgs = new ArrayList<>(q.args());
        pageArgs.add(q.ls().skip());
        pageArgs.add(q.ls().take());
        List<LogRow> logs = repo.findPage(q.fromWhere(), q.order(), pageArgs.toArray());
        long total = repo.count(q.fromWhere(), q.args().toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", q.ls().page());
        out.put("take", q.ls().take());
        out.put("total", total);
        out.put("rows", rowsOf(u, logs, platform));
        out.put("actors", repo.actorOptions());
        if (platform) out.put("businesses", repo.businessOptions());
        return out;
    }

    public static final int EXPORT_MAX = 50_000;

    /** Every row of the filter (at most {@link #EXPORT_MAX}) as CSV: the same columns and redaction as the page. */
    public String exportCsv(CurrentUser u, Map<String, String> sp, boolean platform) {
        Q q = build(sp, platform, EXPORT_MAX);
        List<Object> args = new ArrayList<>(q.args());
        args.add(0);
        args.add(EXPORT_MAX);
        List<Row> rows = rowsOf(u, repo.findPage(q.fromWhere(), q.order(), args.toArray()), platform);
        StringBuilder b = new StringBuilder("At (UTC),Business,Actor,Actor code,Action,Entity,Entity id,Method,Path,Status,Duration ms,IP,Before,After\n");
        for (Row r : rows) {
            b.append(String.join(",", cell(r.at()), cell(r.business()), cell(r.actor()), cell(r.actorCode()), cell(r.action()), cell(r.entity()), cell(r.entityId()),
                    cell(r.method()), cell(r.path()), cell(r.status() == null ? null : r.status().toString()),
                    cell(r.durationMs() == null ? null : r.durationMs().toString()), cell(r.ip()),
                    cell(r.before() == null ? null : r.before().toString()), cell(r.after() == null ? null : r.after().toString()))).append('\n');
        }
        return b.toString();
    }

    /** CSV cell; a value that starts like a formula is prefixed so a spreadsheet won't run it. */
    static String cell(String v) {
        if (v == null) return "";
        String x = v.replace("\"", "\"\"");
        if (!x.isEmpty() && "=+-@".indexOf(x.charAt(0)) >= 0) x = "'" + x;
        return "\"" + x + "\"";
    }

    private JsonNode parse(String s) {
        if (s == null) return null;
        try {
            return json.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
