package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.Nav;
import com.jurislpo.jurisone.engine.Shifts;
import com.jurislpo.jurisone.repository.ShellRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Sidebar groups, badges, bell contents and the profile card for the signed-in user.
 * Everything is permission-driven.
 */
@Service
public class ShellService {

    public record NavLink(String id, String label, String icon) {}

    public record NavGroup(String title, List<NavLink> items) {}

    /** A bell line; {@code id} set for a stored notification ({@code unread}, {@code sticky}: stays until the policy is accepted). */
    public record ShellNote(String text, String meta, String href, String id, boolean unread, boolean sticky) {
        ShellNote(String text, String meta, String href) {
            this(text, meta, href, null, false, false);
        }
    }

    public record ShellUser(String code, String name, String designation, String roleName, String department, String manager, String dateOfJoining, String shift,
                     boolean isAdminLike) {}

    /**
     * {@code businessName} null for the super admin (platform); {@code subscription} null for the super admin — when it
     * is not active the app shows the "subscription ended" screen instead of the pages.
     */
    public record Shell(ShellUser currentUser, List<NavGroup> groups, Map<String, Integer> badges, List<ShellNote> notes, int unread, String landing, boolean canSelf,
                        String businessName, com.jurislpo.jurisone.service.SubscriptionService.Current subscription,
                        EmployeeOnboardingService.Summary onboarding) {}

    private final ShellRepository repo;
    private final com.jurislpo.jurisone.service.SubscriptionService subscriptions;
    private final InboxService inbox;
    private final EmployeeOnboardingService onboarding;

    public ShellService(ShellRepository repo, com.jurislpo.jurisone.service.SubscriptionService subscriptions, InboxService inbox, EmployeeOnboardingService onboarding) {
        this.onboarding = onboarding;
        this.repo = repo;
        this.inbox = inbox;
        this.subscriptions = subscriptions;
    }

    public Shell shell(CurrentUser user) {
        boolean adminLike = !user.perms().contains("self");
        Map<String, NavGroup> groups = new LinkedHashMap<>();
        for (Nav.Item item : Nav.visible(user.perms(), user.hasReports(), subscriptions.activeModules(user.businessId()))) {
            String group = item.group() != null ? item.group() : adminLike ? "Workspace" : "My workspace";
            groups.computeIfAbsent(group, k -> new NavGroup(k, new ArrayList<>())).items().add(new NavLink(item.id(), item.label(), item.icon()));
        }

        int pendingLeave = user.can("appr.leave") ? count(repo.countPendingLeave(user.id())) : 0;
        int pendingReg = user.can("appr.reg") ? count(repo.countPendingRegularizations(user.id())) : 0;
        int proofs = user.can("proofs.verify") ? count(repo.countPendingProofs(user.id())) : 0;
        int pending = pendingLeave + pendingReg;

        List<InboxService.Item> recent = inbox.recent(user.id(), 8);
        LocalDate today = Format.todayIn(user.timeZone());
        List<Map<String, Object>> holiday = repo.nextHoliday(today, user.region());

        List<ShellNote> notes = new ArrayList<>();
        if (user.can("appr.leave", "appr.reg")) notes.add(new ShellNote(pending + " request" + (pending == 1 ? "" : "s") + " waiting for approval", "Approvals", "/approvals"));
        if (user.can("proofs.verify")) notes.add(new ShellNote(proofs + " investment proof" + (proofs == 1 ? "" : "s") + " to verify", "Finance", "/proofs"));
        for (InboxService.Item note : recent) {
            notes.add(new ShellNote(note.text(), Format.formatDateTime(java.time.Instant.parse(note.createdAt()), user.timeZone()), note.link(), note.id(), note.readAt() == null, note.sticky()));
        }
        if (!holiday.isEmpty()) {
            LocalDate date = ((java.sql.Date) holiday.get(0).get("date")).toLocalDate();
            notes.add(new ShellNote(holiday.get(0).get("name") + " holiday on " + Format.formatWeekdayDate(date), "Holiday calendar", null));
        }
        int unread = inbox.unread(user.id());

        Map<String, Object> row = repo.profileCard(user.id());
        String shift = row.get("shift_code") == null ? null : Shifts.label((String) row.get("shift_code"), (String) row.get("start_local"), (String) row.get("end_local"));
        ShellUser shellUser = new ShellUser(user.code(), user.name(), user.designation(), user.roleName(), orDash(row.get("department")), orDash(row.get("manager")),
                ((java.sql.Date) row.get("date_of_joining")).toLocalDate().toString(), shift, adminLike);

        Map<String, Integer> badges = new LinkedHashMap<>();
        badges.put("approvals", pending);
        badges.put("proofs", proofs);
        // My tasks: my open tasks (direct or indirect assignee) that are overdue or due today, in each task's time zone
        badges.put("myTasks", user.businessId() != null && user.navAllowed("my-tasks") ? repo.countMyTasksDueByToday(user.id()) : 0);
        String businessName = user.businessId() == null ? null : repo.businessName(user.businessId());
        var subscription = user.businessId() == null ? null : subscriptions.current(user.businessId());
        return new Shell(shellUser, new ArrayList<>(groups.values()), badges, notes, unread, user.landingPath(), user.perms().contains("self"), businessName, subscription,
                onboarding.summary(user.id()));
    }

    private static int count(Integer count) {
        return count == null ? 0 : count;
    }

    private static String orDash(Object value) {
        return value == null ? "—" : value.toString();
    }

}
