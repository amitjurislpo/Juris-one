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

/** Audit log view (audit page) — src/lib/audit-view.ts. */
@Service
public class AuditViewService {

    /** Event groups for the filter; each matches action prefixes. */
    public static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("auth", List.of("auth."));
        GROUPS.put("people", List.of("employee."));
        GROUPS.put("roles", List.of("role.", "policy."));
        GROUPS.put("time", List.of("leave.", "regularization.", "attendance."));
        GROUPS.put("org", List.of("department.", "holiday."));
        GROUPS.put("payroll", List.of("salary.", "payroll."));
        GROUPS.put("tax", List.of("tax.", "declaration.", "proof."));
    }

    /** Salary / tax payloads are only shown to viewers who hold a salary permission (HR never sees pay). */
    static final List<String> SALARY_PREFIXES = Stream.concat(GROUPS.get("payroll").stream(), GROUPS.get("tax").stream()).toList();

    static final List<String> SORTS = List.of("at", "actor", "action");
    static final List<String> PERIODS = List.of("7", "30", "90", "365", "All");
    static final String UUIDISH = "^[0-9a-fA-F-]{36}$";

    public record Row(String id, String at, String actor, String actorCode, String action, String entity, String entityId, String subject, String ip,
                      JsonNode before, JsonNode after, boolean redacted) {}

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

    public Map<String, Object> rows(CurrentUser u, Map<String, String> sp) {
        ListParams ls = ListParams.of(sp, SORTS, "at", 50, "desc");
        List<String> groups = new ArrayList<>(List.of("All"));
        groups.addAll(GROUPS.keySet());
        String group = ListParams.pick(sp, "ev", groups, "All");
        String period = ListParams.pick(sp, "days", PERIODS, "30");
        String actorId = ListParams.str(sp, "actor", "");

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
        if (!ls.q().isEmpty()) {
            String l = like(ls.q());
            and.add("(a.action ILIKE ? OR a.entity ILIKE ? OR a.entity_id ILIKE ? OR e.full_name ILIKE ? OR e.code ILIKE ?)");
            for (int i = 0; i < 5; i++) args.add(l);
        }
        String where = and.isEmpty() ? "" : " WHERE " + String.join(" AND ", and);
        String dir = ls.asc() ? "ASC" : "DESC";
        String order = switch (ls.sort()) {
            case "actor" -> "e.full_name " + dir + ", a.at DESC";
            case "action" -> "a.action " + dir + ", a.at DESC";
            default -> "a.at " + dir;
        };
        String from = " FROM audit_log a LEFT JOIN employees e ON e.id = a.actor_id";

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(ls.skip());
        pageArgs.add(ls.take());
        List<LogRow> logs = repo.findPage(from + where, order, pageArgs.toArray());
        long total = repo.count(from + where, args.toArray());
        List<Option> actors = repo.actorOptions();

        // Name the employee an entry is about (entity ids are employee uuids for these entities).
        String[] empIds = logs.stream().filter(l -> ("employee".equals(l.entity()) || "user".equals(l.entity())) && l.entityId() != null)
                .map(LogRow::entityId).distinct().filter(id -> id.matches(UUIDISH)).toArray(String[]::new);
        Map<String, String> subjects = empIds.length > 0 ? repo.employeeLabels(empIds) : new LinkedHashMap<>();

        boolean seePay = u.can("pay.view", "struct.manage", "tax.view", "proofs.verify");
        List<Row> rows = logs.stream().map(l -> {
            boolean redacted = !seePay && SALARY_PREFIXES.stream().anyMatch(p -> l.action().startsWith(p));
            return new Row(l.id(), Format.jsIso(l.at()), l.actor() == null ? "System" : l.actor(), l.actorCode(), l.action(), l.entity(), l.entityId(),
                    l.entityId() == null ? null : subjects.get(l.entityId()), l.ip(), redacted ? null : parse(l.before()), redacted ? null : parse(l.after()), redacted);
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", ls.page());
        out.put("take", ls.take());
        out.put("total", total);
        out.put("rows", rows);
        out.put("actors", actors);
        return out;
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
