package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Who may decide an attendance request (regularization, On Duty, Remote Work). One rule for all three, checked by the server on every
 * single and bulk decision, on auto-approve, and used to pick whom to notify and what to count in the pending badge.
 * <ul>
 *   <li>An employee's request is decided by anybody holding {@code appr.reg} (Admin, HR), but never by the employee: only the Admin
 *   ({@code roles.manage}) may decide their own request.</li>
 *   <li>A request of an <b>HR</b> person (holds {@code appr.reg}, is not an Admin) is decided by HR's reporting manager, whatever that
 *   manager's role (this is the only attendance request a manager can decide, and only that one request at a time); when HR has no
 *   reporting manager it goes to the Admin. Other HR people cannot decide it, and HR cannot decide their own.</li>
 * </ul>
 * Row-level security keeps every query inside the business.
 */
@Component
public class AttendanceApprovalPolicy {

    private static final String HOLDS_PERMISSION = """
        EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.permission_key = ? AND (rp.role_id = e.role_id
                OR rp.role_id IN (SELECT ar.role_id FROM employee_additional_roles ar WHERE ar.employee_id = e.id)))""";

    /** SQL (alias {@code e} = the requester) that is true for an HR person: holds appr.reg and is not an Admin. Two bound parameters: appr.reg, roles.manage. */
    public static final String SUBJECT_IS_HR = "(" + HOLDS_PERMISSION + " AND NOT " + HOLDS_PERMISSION + ")";

    public record Subject(UUID id, UUID managerId, boolean isHr) {}

    /** {@code managesHr}: the user is the reporting manager of an HR person (so the Team view of On Duty and Regularization shows that person's requests). */
    public record PendingCounts(int regularization, int onDuty, int remoteWork, int total, boolean managesHr) {}

    private final JdbcTemplate jdbc;

    public AttendanceApprovalPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private Subject subject(UUID subjectId) {
        List<Subject> found = jdbc.query("SELECT e.id, e.reporting_manager_id, " + SUBJECT_IS_HR + " FROM employees e WHERE e.id = ?",
                (resultSet, i) -> new Subject((UUID) resultSet.getObject(1), (UUID) resultSet.getObject(2), resultSet.getBoolean(3)), "appr.reg", "roles.manage", subjectId);
        return found.isEmpty() ? null : found.get(0);
    }

    private static boolean mayDecide(CurrentUser decider, Subject subject) {
        if (subject == null) return false;
        if (subject.id().equals(decider.id())) return decider.can("roles.manage") && decider.can("appr.reg");
        if (subject.isHr()) return subject.managerId() != null ? decider.id().equals(subject.managerId()) : decider.can("roles.manage");
        return decider.can("appr.reg");
    }

    public boolean mayDecide(CurrentUser decider, UUID subjectId) {
        return mayDecide(decider, subject(subjectId));
    }

    /** Refuses (422 for the own request, 403 otherwise) when {@code decider} may not decide a request of {@code subjectId}. */
    public void assertMayDecide(CurrentUser decider, UUID subjectId) {
        Subject subject = subject(subjectId);
        if (mayDecide(decider, subject)) return;
        if (subject != null && subject.id().equals(decider.id())) throw new UserException("You can't decide your own request.");
        if (subject != null && subject.isHr() && decider.can("appr.reg", "roles.manage")) {
            throw new UserException(HttpStatus.FORBIDDEN, "A request from HR is decided by HR's reporting manager (by the Admin when there is none).");
        }
        throw new ForbiddenException();
    }

    /** Of these requesters, those whose requests {@code decider} may decide. */
    public Set<UUID> decidableSubjects(CurrentUser decider, Collection<UUID> subjectIds) {
        Set<UUID> allowed = new HashSet<>();
        for (UUID subjectId : new HashSet<>(subjectIds)) if (mayDecide(decider, subjectId)) allowed.add(subjectId);
        return allowed;
    }

    /** Of these pending requests (request id to the requester's id), the ones {@code decider} may decide: what the list screens offer Approve / Reject for. */
    public Set<String> decidableRequests(CurrentUser decider, Map<String, String> requesterByPendingRequest) {
        Set<UUID> allowed = decidableSubjects(decider, requesterByPendingRequest.values().stream().map(UUID::fromString).toList());
        Set<String> ids = new HashSet<>();
        requesterByPendingRequest.forEach((requestId, requesterId) -> { if (allowed.contains(UUID.fromString(requesterId))) ids.add(requestId); });
        return ids;
    }

    /** Active people to tell about a new request of {@code subjectId}: the people who may decide it, not the requester. */
    public List<UUID> approversOf(UUID subjectId) {
        Subject subject = subject(subjectId);
        if (subject == null) return List.of();
        if (subject.isHr() && subject.managerId() != null) return List.of(subject.managerId());
        String permission = subject.isHr() ? "roles.manage" : "appr.reg";
        return new ArrayList<>(jdbc.queryForList("SELECT DISTINCT e.id FROM employees e WHERE e.status IN ('ACTIVE', 'PROBATION') AND e.id <> ? AND " + HOLDS_PERMISSION,
                UUID.class, subjectId, permission));
    }

    /** Whether the signed-in user is the reporting manager of an HR person (they then see that person's attendance requests). */
    public boolean managesHr(CurrentUser user) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM employees e WHERE e.reporting_manager_id = ? AND e.status IN ('ACTIVE', 'PROBATION') AND " + SUBJECT_IS_HR, Integer.class,
                user.id(), "appr.reg", "roles.manage");
        return count != null && count > 0;
    }

    /** How many pending requests of each kind are waiting for {@code user} to decide (from the server's rule, never guessed in the browser). */
    public PendingCounts pendingFor(CurrentUser user) {
        int regularization = countPending(user, "regularization_requests");
        int onDuty = countPending(user, "on_duty_requests");
        int remoteWork = countPending(user, "remote_work_requests");
        return new PendingCounts(regularization, onDuty, remoteWork, regularization + onDuty + remoteWork, user.hasReports() && managesHr(user));
    }

    private int countPending(CurrentUser user, String table) {
        if (!user.can("appr.reg") && !user.hasReports()) return 0;
        int total = 0;
        // table names are fixed constants of this class, never user input
        for (Map<String, Object> row : jdbc.queryForList("SELECT employee_id, count(*) AS n FROM " + table + " WHERE status = 'PENDING' GROUP BY employee_id")) {
            if (mayDecide(user, (UUID) row.get("employee_id"))) total += ((Number) row.get("n")).intValue();
        }
        return total;
    }
}
