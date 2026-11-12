package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.OnDutyRules;
import com.jurislpo.jurisone.exception.ApiException;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.OnDutyRepository;
import com.jurislpo.jurisone.repository.OnDutyRepository.Filter;
import com.jurislpo.jurisone.repository.OnDutyRepository.Item;
import com.jurislpo.jurisone.repository.OnDutyRepository.NewRequest;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * On Duty requests: an employee asks for a day (or half a day) spent on official work away from the usual place; Admin and HR ({@code appr.reg})
 * approve. An approved request makes the day a present, payable day in the attendance engine. A range is one request per day sharing a batch id,
 * every day following all the rules on its own.
 */
@Service
public class OnDutyService {

    public static final int PAGE_SIZE = 50;
    public static final int MAX_BULK = 50;
    static final String ALREADY_DECIDED = "ON_DUTY_ALREADY_DECIDED";
    static final String ALREADY_REQUESTED = "ON_DUTY_ALREADY_REQUESTED";
    static final DateTimeFormatter DAY_FIRST = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT);
    static final List<String> STATUSES = List.of("PENDING", "APPROVED", "DECLINED", "CANCELLED");

    /** The body of a new request: {@code toDate} makes a range, {@code part} is FULL, FIRST_HALF or SECOND_HALF (default FULL), {@code employeeId} (Admin / HR only) raises it for that employee. */
    public record CreateInput(String date, String toDate, String part, String location, String reason, String employeeId) {}

    /** One day of a request: whether it was made, and why not. */
    public record DayOutcome(String date, boolean ok, String message) {}

    public record Created(String message, List<DayOutcome> days) {}

    public record DecisionResult(String id, boolean ok, String message) {}

    private final AttendanceService attendanceService;
    private final OnDutyRepository repo;
    private final SettingsService settings;
    private final AuditService audit;
    private final NotificationService notifications;
    private final TransactionTemplate transactionTemplate;

    public OnDutyService(AttendanceService attendanceService, OnDutyRepository repo, SettingsService settings, AuditService audit, NotificationService notifications,
                         TransactionTemplate transactionTemplate) {
        this.attendanceService = attendanceService;
        this.repo = repo;
        this.settings = settings;
        this.audit = audit;
        this.notifications = notifications;
        this.transactionTemplate = transactionTemplate;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static LocalDate parseDate(String text) {
        Check.isoDate(text, "Invalid date.");
        return LocalDate.parse(text);
    }

    private static ApiException alreadyRequested(LocalDate date) {
        return new ApiException(HttpStatus.CONFLICT, ALREADY_REQUESTED, "An On Duty request for " + DAY_FIRST.format(date) + " already exists.");
    }

    // ───────────────────────── make a request ─────────────────────────

    /**
     * Makes the signed-in employee's request (for one day, or one per day of the range). Only Admin / HR ({@code appr.reg}) may name another employee of the
     * same business in {@code employeeId}; the request is then approved at once, as the approver, and the employee is told. Anybody else naming another
     * employee gets 403, and an employee of another business is "not found".
     */
    public Created create(CurrentUser user, CreateInput input, String ipAddress) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input");
        LocalDate first = parseDate(input.date());
        LocalDate last = isBlank(input.toDate()) ? first : parseDate(input.toDate());
        OnDutyRules.checkRange(first, last);
        String part = isBlank(input.part()) ? "FULL" : Check.oneOf(input.part(), OnDutyRules.PARTS);
        String reason = OnDutyRules.reason(input.reason());
        String location = OnDutyRules.location(input.location());
        boolean isRange = !last.equals(first);

        UUID subjectId = user.id();
        if (!isBlank(input.employeeId())) {
            UUID requested = Check.uuid(input.employeeId());
            if (!requested.equals(user.id())) {
                if (!user.can("appr.reg")) throw new ForbiddenException();
                subjectId = requested;
            }
        }
        boolean onBehalf = !subjectId.equals(user.id());
        List<AttendanceEmployee> employees = attendanceService.loadAttendanceEmployees("e.id = ?", subjectId);
        if (employees.isEmpty()) throw onBehalf ? new UserException(HttpStatus.NOT_FOUND, "Employee not found.") : new UserException(MyAttendanceService.NOT_TRACKED);
        AttendanceEmployee employee = employees.get(0);

        Instant now = Instant.now();
        LocalDate today = attendanceService.currentWorkDate(employee, now);
        AttendanceService.ResolvedAttendancePolicy policy = attendanceService.policy();
        int backDateLimitDays = settings.regularization().backDateLimitDays();
        List<DayView> views = attendanceService.attendanceFor(List.of(employee), first, last, now).get(employee.id());

        UUID batchId = isRange ? UUID.randomUUID() : null;
        List<DayOutcome> outcomes = new ArrayList<>();
        List<LocalDate> made = new ArrayList<>();
        List<LocalDate> waiting = new ArrayList<>(); // days now waiting for a decision (not auto-approved)
        for (DayView view : views) {
            LocalDate date = view.dayResult.date;
            try {
                String problem = OnDutyRules.windowProblem(date, today, backDateLimitDays);
                if (problem != null) throw new UserException(problem);
                if (attendanceService.isMonthLocked(date.toString().substring(0, 7), policy, now)) throw new UserException("Attendance for this month is locked. Ask Finance to adjust LOP in payroll.");
                problem = OnDutyRules.dayProblem(view.dayResult.status);
                if (problem != null) throw new UserException(problem);
                if (!repo.openStatus(employee.id(), date).isEmpty()) throw alreadyRequested(date);
                UUID id = UUID.randomUUID();
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("date", date.toString());
                after.put("part", part);
                after.put("status", "PENDING");
                if (batchId != null) after.put("batchId", batchId.toString());
                if (onBehalf) {
                    after.put("onBehalfOf", employee.id().toString()); // the actor is the audit row's actor; the employee it was raised for is named here
                    after.put("employeeCode", employee.code());
                }
                try {
                    transactionTemplate.executeWithoutResult(transactionStatus -> {
                        repo.insert(new NewRequest(id, employee.id(), date, part, location, reason, user.id(), batchId));
                        audit.log(user.id(), onBehalf ? "attendance.on_duty_request_on_behalf" : "attendance.on_duty_request", "on_duty_request", id.toString(), null, after, ipAddress);
                    });
                } catch (DuplicateKeyException e) {
                    throw alreadyRequested(date); // two requests for the same day at the same moment: the database index decides
                }
                made.add(date);
                if (!onBehalf || !autoApprove(user, id, employee, date, ipAddress)) waiting.add(date);
                outcomes.add(new DayOutcome(date.toString(), true, "On Duty request added successfully"));
            } catch (ApiException e) {
                if (!isRange) throw e;
                outcomes.add(new DayOutcome(date.toString(), false, e.getMessage()));
            }
        }
        if (made.isEmpty()) throw new UserException(outcomes.get(0).message());
        informApprovers(user, employee, onBehalf, waiting);
        return new Created(isRange ? made.size() + " of " + outcomes.size() + " requests added" : "On Duty request added successfully", outcomes);
    }

    /**
     * Admin / HR raised the request for the employee: approve it now, as the approver (the decision tells the employee). Returns false when the approval is
     * refused (for example a rule changed a moment ago); the request then simply stays pending for a normal decision, and the employee is told it was raised.
     */
    private boolean autoApprove(CurrentUser actor, UUID id, AttendanceEmployee employee, LocalDate date, String ipAddress) {
        try {
            decide(actor, id.toString(), true, "Auto-approved: raised by " + actor.name() + " for the employee", ipAddress);
            return true;
        } catch (ApiException e) {
            notifications.notify(employee.id(), actor.name() + " raised an On Duty request for you for " + Format.formatDayMonth(date), "/attendance?tab=on-duty");
            return false;
        }
    }

    /** A bell notification for every approver per waiting day, and ONE email for the whole request; the person who raised it is not told about their own request. */
    private void informApprovers(CurrentUser actor, AttendanceEmployee employee, boolean onBehalf, List<LocalDate> days) {
        if (days.isEmpty()) return;
        String span = days.size() == 1 ? Format.formatDayMonth(days.get(0)) : Format.formatDayMonth(days.get(0)) + " to " + Format.formatDayMonth(days.get(days.size() - 1)) + " (" + days.size() + " days)";
        String who = onBehalf ? actor.name() + " raised an On Duty request for " + employee.name() : actor.name() + " asked for On Duty";
        for (UUID approverId : repo.approverIds(employee.id())) {
            if (approverId.equals(actor.id())) continue;
            for (LocalDate day : days) {
                notifications.notify(approverId, who + " on " + Format.formatDayMonth(day), "/attendance?tab=on-duty&scope=team");
            }
            notifications.emailEmployee(approverId, name -> MailTemplates.onDutyRequested(name, who, span));
        }
    }

    // ───────────────────────── withdraw ─────────────────────────

    /** The employee withdraws their own pending request; anybody else gets "not found". */
    public String cancel(CurrentUser user, String idRaw, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = repo.findForDecision(id);
        if (rows.isEmpty() || !user.id().equals(rows.get(0).get("employee_id"))) throw new UserException(HttpStatus.NOT_FOUND, "Request not found.");
        if (!"PENDING".equals(rows.get(0).get("status"))) throw new UserException("Only pending requests can be withdrawn.");
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            if (repo.cancel(id, user.id()) == 0) throw new ApiException(HttpStatus.CONFLICT, ALREADY_DECIDED, "This request was decided a moment ago.");
            audit.log(user.id(), "attendance.on_duty_cancel", "on_duty_request", id.toString(), Map.of("status", "PENDING"), Map.of("status", "CANCELLED"), ipAddress);
        });
        return "Request withdrawn";
    }

    // ───────────────────────── read ─────────────────────────

    private static Filter filterOf(Map<String, String> queryParams, UUID employeeId) {
        String status = ListParams.pick(queryParams, "status", STATUSES, "");
        LocalDate from = null, to = null;
        String month = ListParams.text(queryParams, "month", "");
        if (month.matches("^\\d{4}-(0[1-9]|1[0-2])$")) {
            from = YearMonth.parse(month).atDay(1);
            to = YearMonth.parse(month).atEndOfMonth();
        }
        UUID departmentId = null;
        String departmentText = ListParams.text(queryParams, "departmentId", "");
        if (!departmentText.isEmpty()) departmentId = Check.uuid(departmentText);
        UUID requestedEmployee = employeeId;
        String employeeText = ListParams.text(queryParams, "employeeId", "");
        if (employeeId == null && !employeeText.isEmpty()) requestedEmployee = Check.uuid(employeeText);
        return new Filter(requestedEmployee, status.isEmpty() ? null : status, from, to, ListParams.text(queryParams, "search", "").trim(), departmentId);
    }

    /** {@code scope=mine} (self) or {@code scope=all} (appr.reg; the whole business), filtered by status, month, search, departmentId, employeeId. */
    public Map<String, Object> list(CurrentUser user, boolean everyone, Map<String, String> queryParams) {
        Filter filter = filterOf(queryParams, everyone ? null : user.id());
        ListParams page = ListParams.of(queryParams, List.of("date"), "date", PAGE_SIZE, "desc");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", everyone ? "all" : "mine");
        out.put("items", repo.list(filter, page.skip(), page.take()));
        out.put("total", repo.count(filter));
        out.put("page", page.page());
        out.put("pageSize", PAGE_SIZE);
        return out;
    }

    /** One request: its owner, or an approver; anybody else (another person, another business) gets "not found". */
    public Item details(CurrentUser user, String idRaw) {
        Item item = repo.find(Check.uuid(idRaw));
        if (item == null || (!item.employeeId().equals(user.id().toString()) && !user.can("appr.reg"))) throw new UserException(HttpStatus.NOT_FOUND, "Request not found.");
        return item;
    }

    // ───────────────────────── decide ─────────────────────────

    /**
     * Admin / HR approve or reject; the first decision is final and a rejection needs remarks. Nobody decides their own request. Approving is
     * refused for a locked month and for a day that has since become a holiday, weekly off or leave.
     */
    public String decide(CurrentUser user, String idRaw, Boolean approve, String noteRaw, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        if (approve == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean, received undefined");
        String note = noteRaw == null ? null : noteRaw.trim();
        if (note != null && note.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected string to have <=300 characters");
        if (!approve && (note == null || note.isEmpty())) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the remarks for rejecting this request.");
        List<Map<String, Object>> rows = repo.findForDecision(id);
        if (rows.isEmpty()) throw new UserException("Request not found.");
        Map<String, Object> row = rows.get(0);
        UUID employeeId = (UUID) row.get("employee_id");
        if (employeeId.equals(user.id())) throw new UserException("You can't decide your own request.");
        if (!"PENDING".equals(row.get("status"))) throw new UserException("This request was already decided.");
        LocalDate date = ((Date) row.get("work_date")).toLocalDate();
        if (approve) {
            Instant now = Instant.now();
            if (attendanceService.isMonthLocked(date.toString().substring(0, 7), attendanceService.policy(), now)) {
                throw new UserException("That month's attendance is locked — reject this and adjust LOP in payroll instead.");
            }
            AttendanceEmployee employee = attendanceService.loadAttendanceEmployees("e.id = ?", employeeId).get(0);
            DayView day = attendanceService.attendanceFor(List.of(employee), date, date, now).get(employee.id()).get(0);
            if (OnDutyRules.dayProblem(day.dayResult.status) != null) throw new UserException("That day is now a holiday, weekly off or leave — reject this request.");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", approve ? "APPROVED" : "DECLINED");
        after.put("date", date.toString());
        after.put("part", row.get("part"));
        if (note != null) after.put("note", note);
        after.put("role", user.roleName());
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            if (repo.decide(id, approve ? "APPROVED" : "DECLINED", user.id(), Instant.now(), user.roleName(), note) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, ALREADY_DECIDED, "Someone else decided this request a moment ago.");
            }
            audit.log(user.id(), approve ? "attendance.on_duty_approve" : "attendance.on_duty_decline", "on_duty_request", id.toString(), Map.of("status", "PENDING"), after, ipAddress);
        });
        notifications.notifyDecision(employeeId, "On Duty for " + Format.formatDayMonth(date), approve, user.name() + " (" + user.roleName() + ")",
                "/attendance?tab=on-duty", note);
        return (approve ? "Approved" : "Declined") + " " + row.get("full_name") + "’s On Duty request";
    }

    /** Several requests at once; each is decided on its own (one failing does not stop the others). */
    public List<DecisionResult> decideMany(CurrentUser user, List<String> ids, Boolean approve, String note, String ipAddress) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BULK) throw new UserException(HttpStatus.BAD_REQUEST, "Choose between 1 and " + MAX_BULK + " requests.");
        if (approve == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean, received undefined");
        if (!approve && isBlank(note)) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the remarks for rejecting this request.");
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
