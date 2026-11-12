package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.exception.ApiException;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.RemoteWorkRepository;
import com.jurislpo.jurisone.repository.RemoteWorkRepository.Filter;
import com.jurislpo.jurisone.repository.RemoteWorkRepository.Item;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Remote work requests (FR-REMOTE-1..6): an employee asks to work remotely for a range of days; Admin and HR ({@code appr.reg}) approve or reject.
 * An approved day is a normal working day for attendance (the engine shows it as "Remote", never Absent for lack of a punch).
 */
@Service
public class RemoteWorkService {

    public static final int PAGE_SIZE = 50;
    public static final int MAX_BULK = 50;
    public static final int MAX_RANGE_DAYS = 31;
    public static final int MAX_DAYS_AHEAD = 365;
    public static final int MAX_REASON_LENGTH = 500;
    static final String ALREADY_DECIDED = "REMOTE_WORK_ALREADY_DECIDED";
    static final String ALREADY_REQUESTED = "REMOTE_WORK_ALREADY_REQUESTED";
    static final List<String> STATUSES = List.of("PENDING", "APPROVED", "DECLINED", "CANCELLED");

    /** The body of a new request: ISO dates and the reason. */
    /** {@code part}: FULL (default), FIRST_HALF or SECOND_HALF; a half day is one day (fromDate = toDate). */
    public record CreateInput(String fromDate, String toDate, String reason, String employeeId, String part) {}

    public record EditInput(String fromDate, String toDate, String reason, String part) {}

    public record DecisionResult(String id, boolean ok, String message) {}

    private final AttendanceService attendanceService;
    private final RemoteWorkRepository repo;
    private final SettingsService settings;
    private final AuditService audit;
    private final NotificationService notifications;
    private final TransactionTemplate transactionTemplate;
    private final AttendanceApprovalPolicy approvalPolicy;
    private static final UUID NO_REQUEST = new UUID(0, 0);
    static final List<String> PARTS = List.of("FULL", "FIRST_HALF", "SECOND_HALF");

    public RemoteWorkService(AttendanceService attendanceService, RemoteWorkRepository repo, SettingsService settings, AuditService audit, NotificationService notifications,
                             TransactionTemplate transactionTemplate, AttendanceApprovalPolicy approvalPolicy) {
        this.approvalPolicy = approvalPolicy;
        this.attendanceService = attendanceService;
        this.repo = repo;
        this.settings = settings;
        this.audit = audit;
        this.notifications = notifications;
        this.transactionTemplate = transactionTemplate;
    }

    private static LocalDate parseDate(String text, String message) {
        return LocalDate.parse(Check.isoDate(text, message));
    }

    static String span(LocalDate from, LocalDate to) {
        return from.equals(to) ? Format.formatDayMonth(from) : Format.formatDayMonth(from) + " to " + Format.formatDayMonth(to);
    }

    /** True when some month touched by the days of [from, to] that are not in the future is locked for attendance. */
    private boolean touchesLockedMonth(LocalDate from, LocalDate to, LocalDate today, Instant now) {
        AttendanceService.ResolvedAttendancePolicy policy = attendanceService.policy();
        LocalDate last = to.isAfter(today) ? today : to;
        for (YearMonth month = YearMonth.from(from); !from.isAfter(last) && !month.atDay(1).isAfter(last); month = month.plusMonths(1)) {
            if (attendanceService.isMonthLocked(month.toString(), policy, now)) return true;
        }
        return false;
    }

    // ───────────────────────── make a request ─────────────────────────

    /**
     * Makes a request for the signed-in employee. Refused: bad dates or reason (400), a range over 31 days, a start more than the back-date limit
     * (the regularization rule) behind or more than a year ahead, a locked month, an overlap with another pending or approved request of the
     * employee (409 REMOTE_WORK_ALREADY_REQUESTED). Returns the new id. Admin / HR ({@code appr.reg}) may name an {@code employeeId} of the same business to
     * raise the request for that employee; it is then approved at once (as the approver) and the employee is told. Anybody else naming another employee gets 403.
     */
    public String create(CurrentUser user, CreateInput input, String ipAddress) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input");
        LocalDate from = parseDate(input.fromDate(), "Invalid date.");
        LocalDate to = parseDate(input.toDate(), "Invalid date.");
        if (to.isBefore(from)) throw new UserException(HttpStatus.BAD_REQUEST, "The last day can't be before the first day.");
        if (from.until(to, ChronoUnit.DAYS) + 1 > MAX_RANGE_DAYS) throw new UserException(HttpStatus.BAD_REQUEST, "A request can cover at most " + MAX_RANGE_DAYS + " days.");
        String reason = Check.trimmed(input.reason(), 1, "Enter the reason for working remotely.", MAX_REASON_LENGTH, "Keep the reason under 500 characters.");
        String part = partOf(input.part(), from, to);

        UUID subjectId = user.id();
        if (input.employeeId() != null && !input.employeeId().isBlank()) {
            UUID requested = Check.uuid(input.employeeId());
            if (!requested.equals(user.id())) {
                if (!user.can("appr.reg")) throw new ForbiddenException();
                subjectId = requested;
            }
        }
        boolean onBehalf = !subjectId.equals(user.id());
        UUID employeeId = subjectId;
        List<AttendanceEmployee> employees = attendanceService.loadAttendanceEmployees("e.id = ?", employeeId);
        if (employees.isEmpty()) throw onBehalf ? new UserException(HttpStatus.NOT_FOUND, "Employee not found.") : new UserException(MyAttendanceService.NOT_TRACKED);
        AttendanceEmployee employee = employees.get(0);
        Instant now = Instant.now();
        LocalDate today = attendanceService.currentWorkDate(employee, now);
        int backDays = settings.regularization().backDateLimitDays();
        if (from.isBefore(today.minusDays(backDays))) throw new UserException("Requests can be made up to " + backDays + " days back.");
        if (from.isAfter(today.plusDays(MAX_DAYS_AHEAD))) throw new UserException("Requests can be made up to " + MAX_DAYS_AHEAD + " days ahead.");
        if (touchesLockedMonth(from, to, today, now)) throw new UserException("Attendance for this month is locked. Ask Finance to adjust LOP in payroll.");
        checkMonthlyLimit(employeeId, from, to, part, NO_REQUEST, onBehalf);

        UUID id = UUID.randomUUID();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                repo.lockEmployee(employeeId);
                if (repo.hasOpenOverlap(employeeId, from, to, NO_REQUEST)) throw alreadyRequested(onBehalf);
                repo.insert(id, employeeId, from, to, part, reason, user.id());
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("fromDate", from.toString());
                view.put("toDate", to.toString());
                view.put("days", from.until(to, ChronoUnit.DAYS) + 1);
                view.put("part", part);
                view.put("reason", reason);
                if (onBehalf) {
                    view.put("onBehalfOf", employeeId.toString()); // the actor is the audit row's actor; the employee it was raised for is named here
                    view.put("employeeCode", employee.code());
                }
                audit.log(user.id(), onBehalf ? "remote_work.request_on_behalf" : "remote_work.request", "remote_work", id.toString(), null, view, ipAddress);
            });
        } catch (DuplicateKeyException e) {
            throw alreadyRequested(onBehalf); // two requests that start the same day at the same moment: the database index decides
        }
        if (onBehalf) {
            // Admin / HR raised it for the employee. With the business setting autoApprove on (the same one as for regularization, off by default) it is approved
            // now, as the approver; if the approval is refused (the approval rule: an HR person's request is decided by their manager; or a rule changed a moment
            // ago) or the setting is off, the request waits for a normal decision.
            if (settings.regularization().autoApprove()) {
                try {
                    decide(user, id.toString(), true, "Auto-approved: raised by " + user.name() + " for the employee", ipAddress);
                    return id.toString();
                } catch (ApiException e) {
                    // stays pending
                }
            }
            notifications.notify(employeeId, user.name() + " raised a remote work request for you for " + span(from, to), "/attendance?tab=remote-work");
        }
        long days = from.until(to, ChronoUnit.DAYS) + 1;
        String span = span(from, to) + (days > 1 ? " (" + days + " days)" : "");
        String who = onBehalf ? user.name() + " raised a remote work request for " + employee.name() : user.name() + " asked to work remotely";
        for (UUID approverId : approvalPolicy.approversOf(employeeId)) {
            if (approverId.equals(user.id())) continue;
            notifications.notify(approverId, who + " for " + span(from, to), "/attendance?scope=team&tab=remote-work");
            notifications.emailEmployee(approverId, name -> MailTemplates.remoteWorkRequested(name, who, span));
        }
        return id.toString();
    }

    /** FULL by default; a half day (first or second half) must be a single day (400 otherwise). */
    static String partOf(String text, LocalDate from, LocalDate to) {
        String part = text == null || text.isBlank() ? "FULL" : Check.oneOf(text, PARTS);
        if (!"FULL".equals(part) && !from.equals(to)) throw new UserException(HttpStatus.BAD_REQUEST, "A half day is a single day.");
        return part;
    }

    /** The monthly remote work limit (days; a half day is 0.5), for every month the request touches, counting the employee's other pending and approved requests. */
    private void checkMonthlyLimit(UUID employeeId, LocalDate from, LocalDate to, String part, UUID exceptId, boolean onBehalf) {
        int limit = settings.regularization().remoteWorkMonthlyLimit();
        double perDay = "FULL".equals(part) ? 1 : 0.5;
        for (YearMonth month = YearMonth.from(from); !month.atDay(1).isAfter(to); month = month.plusMonths(1)) {
            LocalDate first = month.atDay(1).isBefore(from) ? from : month.atDay(1), last = month.atEndOfMonth().isAfter(to) ? to : month.atEndOfMonth();
            double asked = (first.until(last, ChronoUnit.DAYS) + 1) * perDay;
            if (repo.usedDays(employeeId, month.atDay(1), month.atEndOfMonth(), exceptId) + asked > limit + 1e-9) {
                throw new UserException((onBehalf ? "They have" : "You have") + " used all " + limit + " remote work days for " + Format.monthLabel(month.toString()) + ".");
            }
        }
    }

    private static ApiException alreadyRequested(boolean onBehalf) {
        return new ApiException(HttpStatus.CONFLICT, ALREADY_REQUESTED, (onBehalf ? "The employee already has" : "You already have") + " a remote work request for some of these days.");
    }

    // ───────────────────────── withdraw ─────────────────────────

    public String cancel(CurrentUser user, String idRaw, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = repo.findForDecision(id);
        if (rows.isEmpty() || !user.id().equals(rows.get(0).get("employee_id"))) throw new UserException(HttpStatus.NOT_FOUND, "Request not found.");
        if (!"PENDING".equals(rows.get(0).get("status"))) throw new UserException("Only pending requests can be withdrawn.");
        if (repo.cancel(id, user.id()) == 0) throw new ApiException(HttpStatus.CONFLICT, ALREADY_DECIDED, "This request was decided a moment ago.");
        audit.log(user.id(), "remote_work.cancel", "remote_work", id.toString(), Map.of("status", "PENDING"), Map.of("status", "CANCELLED"), ipAddress);
        return "Request withdrawn";
    }

    // ───────────────────────── edit ─────────────────────────

    /**
     * Changes a request that is still pending. Only the person who raised it may; every rule of a new request applies again (dates, window, locked month,
     * overlap, monthly limit); the first decision wins (409). Audited with the old and new values.
     */
    public String edit(CurrentUser user, String idRaw, EditInput input, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input");
        List<Map<String, Object>> rows = repo.findForDecision(id);
        if (rows.isEmpty() || !user.id().equals(rows.get(0).get("created_by"))) throw new UserException(HttpStatus.NOT_FOUND, "Request not found.");
        Map<String, Object> row = rows.get(0);
        if (!"PENDING".equals(row.get("status"))) throw new UserException("Only pending requests can be changed.");
        UUID employeeId = (UUID) row.get("employee_id");
        boolean onBehalf = !employeeId.equals(user.id());
        LocalDate from = parseDate(input.fromDate(), "Invalid date."), to = parseDate(input.toDate(), "Invalid date.");
        if (to.isBefore(from)) throw new UserException(HttpStatus.BAD_REQUEST, "The last day can't be before the first day.");
        if (from.until(to, ChronoUnit.DAYS) + 1 > MAX_RANGE_DAYS) throw new UserException(HttpStatus.BAD_REQUEST, "A request can cover at most " + MAX_RANGE_DAYS + " days.");
        String reason = Check.trimmed(input.reason(), 1, "Enter the reason for working remotely.", MAX_REASON_LENGTH, "Keep the reason under 500 characters.");
        String part = partOf(input.part(), from, to);
        List<AttendanceEmployee> employees = attendanceService.loadAttendanceEmployees("e.id = ?", employeeId);
        if (employees.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Employee not found.");
        Instant now = Instant.now();
        LocalDate today = attendanceService.currentWorkDate(employees.get(0), now);
        int backDays = settings.regularization().backDateLimitDays();
        if (from.isBefore(today.minusDays(backDays))) throw new UserException("Requests can be made up to " + backDays + " days back.");
        if (from.isAfter(today.plusDays(MAX_DAYS_AHEAD))) throw new UserException("Requests can be made up to " + MAX_DAYS_AHEAD + " days ahead.");
        if (touchesLockedMonth(from, to, today, now)) throw new UserException("Attendance for this month is locked. Ask Finance to adjust LOP in payroll.");
        checkMonthlyLimit(employeeId, from, to, part, id, onBehalf);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("fromDate", ((Date) row.get("from_date")).toLocalDate().toString());
        before.put("toDate", ((Date) row.get("to_date")).toLocalDate().toString());
        before.put("part", row.get("part"));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fromDate", from.toString());
        after.put("toDate", to.toString());
        after.put("part", part);
        after.put("reason", reason);
        if (onBehalf) after.put("onBehalfOf", employeeId.toString());
        try {
            transactionTemplate.executeWithoutResult(status -> {
                repo.lockEmployee(employeeId);
                if (repo.hasOpenOverlap(employeeId, from, to, id)) throw alreadyRequested(onBehalf);
                if (repo.updatePending(id, from, to, part, reason) == 0) throw new ApiException(HttpStatus.CONFLICT, ALREADY_DECIDED, "This request was decided a moment ago.");
                audit.log(user.id(), "remote_work.edit", "remote_work", id.toString(), before, after, ipAddress);
            });
        } catch (DuplicateKeyException e) {
            throw alreadyRequested(onBehalf);
        }
        return "Remote work request updated";
    }

    // ───────────────────────── read ─────────────────────────

    private static Filter filterOf(Map<String, String> queryParams, UUID employeeId, UUID managerId) {
        String status = ListParams.pick(queryParams, "status", STATUSES, "");
        LocalDate first = null, last = null;
        String month = ListParams.text(queryParams, "month", "");
        if (month.matches("^\\d{4}-(0[1-9]|1[0-2])$")) {
            first = YearMonth.parse(month).atDay(1);
            last = YearMonth.parse(month).atEndOfMonth();
        }
        UUID departmentId = null;
        String departmentText = ListParams.text(queryParams, "departmentId", "");
        if (!departmentText.isEmpty()) departmentId = Check.uuid(departmentText);
        UUID requestedEmployee = employeeId;
        String employeeText = ListParams.text(queryParams, "employeeId", "");
        if (employeeId == null && !employeeText.isEmpty()) requestedEmployee = Check.uuid(employeeText);
        return new Filter(requestedEmployee, managerId, status.isEmpty() ? null : status, first, last, ListParams.text(queryParams, "search", "").trim(), departmentId);
    }

    /**
     * {@code scope=mine} (default): the signed-in employee's requests. {@code scope=team}: everybody's for Admin / HR ({@code appr.reg}), a manager's direct
     * reports (read only) for a manager, a refusal (403) for anyone else. Filters: status, month (requests touching it), search, departmentId, employeeId.
     * The employee and the manager always come from the session, never from a parameter.
     */
    public Map<String, Object> list(CurrentUser user, Map<String, String> queryParams) {
        boolean isTeam = "team".equals(queryParams.get("scope"));
        Filter filter;
        if (!isTeam) {
            filter = filterOf(queryParams, user.id(), null);
        } else if (user.can("appr.reg")) {
            filter = filterOf(queryParams, null, null);
        } else if (user.hasReports()) {
            filter = filterOf(queryParams, null, user.id());
        } else {
            throw new ForbiddenException();
        }
        ListParams page = ListParams.of(queryParams, List.of("date"), "date", PAGE_SIZE, "desc");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", isTeam ? "team" : "mine");
        out.put("canDecide", isTeam && user.can("appr.reg"));
        List<Item> items = repo.list(filter, page.skip(), page.take());
        Map<String, String> pending = new LinkedHashMap<>();
        for (Item item : items) if ("PENDING".equals(item.status())) pending.put(item.id(), item.employeeId());
        out.put("items", items);
        out.put("decidable", approvalPolicy.decidableRequests(user, pending)); // the pending requests on this page the signed-in user may decide (the server's rule)
        out.put("total", repo.count(filter));
        out.put("page", page.page());
        out.put("pageSize", PAGE_SIZE);
        return out;
    }

    /** One request: its owner, an approver, or the owner's manager; anybody else (another person, another business) gets "not found". */
    public Item details(CurrentUser user, String idRaw) {
        UUID id = Check.uuid(idRaw);
        Item item = repo.find(id);
        if (item == null) throw new UserException(HttpStatus.NOT_FOUND, "Request not found.");
        boolean isOwner = item.employeeId().equals(user.id().toString());
        boolean isManager = !isOwner && !user.can("appr.reg") && user.hasReports() && user.id().equals(repo.findForDecision(id).get(0).get("manager_id"));
        if (!isOwner && !isManager && !user.can("appr.reg")) throw new UserException(HttpStatus.NOT_FOUND, "Request not found.");
        return item;
    }

    // ───────────────────────── decisions ─────────────────────────

    /** Approves or rejects one pending request (appr.reg). The first decision wins; a rejection needs remarks (up to 300 characters); nobody decides their own request. */
    public String decide(CurrentUser user, String idRaw, Boolean approve, String noteRaw, String ipAddress) {
        if (!user.can("appr.reg", "roles.manage") && !user.hasReports()) throw new ForbiddenException(); // only approvers and managers reach the approval rule
        UUID id = Check.uuid(idRaw);
        if (approve == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean, received undefined");
        String note = noteRaw == null ? null : noteRaw.trim();
        if (note != null && note.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected string to have <=300 characters");
        if (!approve && (note == null || note.isEmpty())) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the remarks for rejecting this request.");
        if (note != null && note.isEmpty()) note = null;
        List<Map<String, Object>> rows = repo.findForDecision(id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Request not found.");
        Map<String, Object> row = rows.get(0);
        UUID employeeId = (UUID) row.get("employee_id");
        approvalPolicy.assertMayDecide(user, employeeId); // Admin may decide their own; HR's goes to HR's manager (or the Admin); never your own as HR
        if (!"PENDING".equals(row.get("status"))) throw new UserException("This request was already decided.");
        LocalDate from = ((Date) row.get("from_date")).toLocalDate(), to = ((Date) row.get("to_date")).toLocalDate();
        Instant now = Instant.now();
        if (approve) {
            List<AttendanceEmployee> employees = attendanceService.loadAttendanceEmployees("e.id = ?", employeeId);
            LocalDate today = employees.isEmpty() ? LocalDate.now() : attendanceService.currentWorkDate(employees.get(0), now);
            if (touchesLockedMonth(from, to, today, now)) throw new UserException("That month's attendance is locked — reject this and adjust LOP in payroll instead.");
        }
        String finalNote = note;
        transactionTemplate.executeWithoutResult(status -> {
            if (repo.decide(id, approve ? "APPROVED" : "DECLINED", user.id(), user.roleName(), finalNote) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, ALREADY_DECIDED, "Someone else decided this request a moment ago.");
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", approve ? "APPROVED" : "DECLINED");
            after.put("fromDate", from.toString());
            after.put("toDate", to.toString());
            if (finalNote != null) after.put("note", finalNote);
            after.put("role", user.roleName());
            audit.log(user.id(), approve ? "remote_work.approve" : "remote_work.decline", "remote_work", id.toString(), Map.of("status", "PENDING"), after, ipAddress);
        });
        notifications.notifyDecision(employeeId, "Remote work request for " + span(from, to), approve, user.name() + " (" + user.roleName() + ")",
                "/attendance?tab=remote-work&month=" + from.toString().substring(0, 7), finalNote);
        return (approve ? "Approved " : "Declined ") + row.get("full_name") + "’s remote work request";
    }

    /** Approves or rejects several requests; each one is decided on its own (one failing does not stop the others). */
    public List<DecisionResult> decideMany(CurrentUser user, List<String> ids, Boolean approve, String note, String ipAddress) {
        if (!user.can("appr.reg") && !user.hasReports()) throw new ForbiddenException(); // only approvers and managers reach the approval rule
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BULK) throw new UserException(HttpStatus.BAD_REQUEST, "Choose between 1 and " + MAX_BULK + " requests.");
        if (approve == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean, received undefined");
        List<DecisionResult> results = new ArrayList<>();
        for (String id : ids.stream().distinct().toList()) {
            try {
                results.add(new DecisionResult(id, true, decide(user, id, approve, note, ipAddress)));
            } catch (ApiException e) {
                results.add(new DecisionResult(id, false, e.getMessage()));
            }
        }
        return results;
    }
}
