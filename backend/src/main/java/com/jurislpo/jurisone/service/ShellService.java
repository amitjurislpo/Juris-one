package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.Nav;
import com.jurislpo.jurisone.engine.Shifts;
import com.jurislpo.jurisone.security.CurrentUser;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Sidebar groups, badges, bell contents and the profile card for the signed-in user — src/lib/shell-data.ts and
 * the data part of src/app/(app)/layout.tsx. Everything is permission-driven.
 */
@Service
public class ShellService {

    public record NavLink(String id, String label, String icon) {}

    public record Group(String g, List<NavLink> items) {}

    /** A bell line; {@code id} set for a stored notification ({@code unread}, {@code sticky}: stays until the policy is accepted). */
    public record Note(String t, String s, String href, String id, boolean unread, boolean sticky) {
        Note(String t, String s, String href) {
            this(t, s, href, null, false, false);
        }
    }

    public record Me(String code, String name, String designation, String roleName, String department, String manager, String doj, String shift,
                     boolean isAdminLike) {}

    /**
     * {@code businessName} null for the super admin (platform); {@code subscription} null for the super admin — when it
     * is not active the app shows the "subscription ended" screen instead of the pages.
     */
    public record Shell(Me me, List<Group> groups, Map<String, Integer> badges, List<Note> notes, int unread, String landing, boolean canSelf,
                        String businessName, com.jurislpo.jurisone.service.SubscriptionService.Current subscription) {}

    private final JdbcTemplate jdbc;
    private final com.jurislpo.jurisone.service.SubscriptionService subscriptions;
    private final InboxService inbox;

    public ShellService(JdbcTemplate jdbc, com.jurislpo.jurisone.service.SubscriptionService subscriptions, InboxService inbox) {
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.subscriptions = subscriptions;
    }

    public Shell shell(CurrentUser u) {
        boolean adminLike = !u.perms().contains("self");
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Nav.Item n : Nav.visible(u.perms(), u.hasReports(), subscriptions.activeModules(u.businessId()))) {
            String g = n.group() != null ? n.group() : adminLike ? "Workspace" : "My workspace";
            groups.computeIfAbsent(g, k -> new Group(k, new ArrayList<>())).items().add(new NavLink(n.id(), n.label(), n.icon()));
        }

        int pendingLeave = u.can("appr.leave") ? count("SELECT count(*) FROM leave_requests WHERE status = 'PENDING' AND employee_id <> ?", u.id()) : 0;
        int pendingReg = u.can("appr.reg") ? count("SELECT count(*) FROM regularization_requests WHERE status = 'PENDING' AND employee_id <> ?", u.id()) : 0;
        int proofs = u.can("proofs.verify") ? count("SELECT count(*) FROM declaration_items WHERE status = 'PENDING' AND employee_id <> ?", u.id()) : 0;
        int pending = pendingLeave + pendingReg;

        List<InboxService.Item> recent = inbox.recent(u.id(), 8);
        LocalDate today = Format.todayIn(u.timeZone());
        List<Map<String, Object>> holiday = jdbc.queryForList(
                "SELECT name, date FROM holidays WHERE date >= ? AND region::text IN ('ALL', ?) ORDER BY date ASC LIMIT 1", today, u.region());

        List<Note> notes = new ArrayList<>();
        if (u.can("appr.leave", "appr.reg")) notes.add(new Note(pending + " request" + (pending == 1 ? "" : "s") + " waiting for approval", "Approvals", "/approvals"));
        if (u.can("proofs.verify")) notes.add(new Note(proofs + " investment proof" + (proofs == 1 ? "" : "s") + " to verify", "Finance", "/proofs"));
        for (InboxService.Item n : recent) {
            notes.add(new Note(n.text(), Format.fmtDateTime(java.time.Instant.parse(n.createdAt()), u.timeZone()), n.link(), n.id(), n.readAt() == null, n.sticky()));
        }
        if (!holiday.isEmpty()) {
            LocalDate d = ((java.sql.Date) holiday.get(0).get("date")).toLocalDate();
            notes.add(new Note(holiday.get(0).get("name") + " holiday on " + Format.fmtDL(d), "Holiday calendar", null));
        }
        int unread = inbox.unread(u.id());

        Map<String, Object> e = jdbc.queryForMap("""
            SELECT d.name AS department, m.full_name AS manager, e.date_of_joining, s.code AS shift_code, s.start_local, s.end_local
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id LEFT JOIN employees m ON m.id = e.reporting_manager_id
            LEFT JOIN shifts s ON s.id = e.shift_id WHERE e.id = ?""", u.id());
        String shift = e.get("shift_code") == null ? null : Shifts.label((String) e.get("shift_code"), (String) e.get("start_local"), (String) e.get("end_local"));
        Me me = new Me(u.code(), u.name(), u.designation(), u.roleName(), orDash(e.get("department")), orDash(e.get("manager")),
                ((java.sql.Date) e.get("date_of_joining")).toLocalDate().toString(), shift, adminLike);

        Map<String, Integer> badges = new LinkedHashMap<>();
        badges.put("approvals", pending);
        badges.put("proofs", proofs);
        String businessName = u.businessId() == null ? null : jdbc.queryForObject("SELECT name FROM businesses WHERE id = ?", String.class, u.businessId());
        var subscription = u.businessId() == null ? null : subscriptions.current(u.businessId());
        return new Shell(me, new ArrayList<>(groups.values()), badges, notes, unread, u.landingPath(), u.perms().contains("self"), businessName, subscription);
    }

    private int count(String sql, UUID id) {
        Integer n = jdbc.queryForObject(sql, Integer.class, id);
        return n == null ? 0 : n;
    }

    private static String orDash(Object v) {
        return v == null ? "—" : v.toString();
    }

}
