package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.exception.ApiException;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.ExitRepository;
import com.jurislpo.jurisone.repository.LeaveRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Employee exit: the employee resigns (or HR starts a termination), notice runs, each department clears its part, and
 * the case ends with the full and final settlement ({@link FnfService}) and the existing "mark as past employee".
 *
 * <pre>
 *   RESIGNED / INITIATED --accept--> NOTICE --> CLEARANCE --(all cleared)--> FNF_PENDING --(settlement approved)--> SETTLED --> CLOSED
 *   WITHDRAWN: the employee, until clearance starts · CANCELLED: HR, until the settlement is approved
 * </pre>
 * A wrong current state is a 409; a rule that is not met yet (open clearance items, no settlement, the day has not come) is a 422.
 * Nothing here handles money: amounts live in {@link FnfService}, behind the pay permissions.
 */
@Service
public class ExitService {

    public static final List<String> KINDS = List.of("RESIGNATION", "TERMINATION", "OTHER");
    static final List<String> OPEN = List.of("RESIGNED", "INITIATED", "NOTICE", "CLEARANCE", "FNF_PENDING", "SETTLED");
    static final List<String> DEPTS = List.of("HR", "FINANCE", "IT", "MANAGER");

    /** The standard checklist every case starts with. */
    static final List<String[]> DEFAULT_ITEMS = List.of(
            new String[] {"MANAGER", "Work handover completed"},
            new String[] {"IT", "Laptop, accessories and access cards returned; accounts closed"},
            new String[] {"FINANCE", "Advances, loans and dues checked"},
            new String[] {"HR", "Exit formalities and documents completed"});

    private final ExitRepository repo;
    private final SettingsService settings;
    private final PeopleService people;
    private final AuditService audit;
    private final NotificationService notifications;
    private final LeaveRepository leave;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public ExitService(ExitRepository repo, SettingsService settings, PeopleService people, AuditService audit, NotificationService notifications,
                       LeaveRepository leave, TransactionTemplate tx, ObjectMapper json) {
        this.json = json;
        this.repo = repo;
        this.settings = settings;
        this.people = people;
        this.audit = audit;
        this.notifications = notifications;
        this.leave = leave;
        this.tx = tx;
    }

    static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "EXIT_STATE", message);
    }

    static String date(Object d) {
        return d == null ? null : ((Date) d).toLocalDate().toString();
    }

    static LocalDate day(Object d) {
        return d == null ? null : ((Date) d).toLocalDate();
    }

    // ───────────────────────── views ─────────────────────────

    /** Case fields without any money. {@code manage}: HR's extra details. */
    Map<String, Object> view(Map<String, Object> x, boolean manage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.get("id").toString());
        m.put("kind", x.get("kind"));
        m.put("status", x.get("status"));
        m.put("reason", x.get("reason"));
        m.put("requestedLastDay", date(x.get("requested_last_day")));
        m.put("lastWorkingDay", date(x.get("last_working_day")));
        m.put("noticeStartDate", date(x.get("notice_start_date")));
        m.put("noticeDays", x.get("notice_days"));
        m.put("noticePolicyDays", x.get("notice_policy_days"));
        m.put("noticeReason", x.get("notice_reason"));
        m.put("hrComment", x.get("hr_comment"));
        m.put("endedReason", x.get("ended_reason"));
        m.put("initiatedAt", OnboardingService.iso(x.get("initiated_at")));
        m.put("acceptedAt", OnboardingService.iso(x.get("accepted_at")));
        m.put("settledAt", OnboardingService.iso(x.get("settled_at")));
        m.put("closedAt", OnboardingService.iso(x.get("closed_at")));
        if (manage) {
            m.put("employeeId", x.get("employee_id").toString());
            m.put("employeeCode", x.get("emp_code"));
            m.put("employeeName", x.get("emp_name"));
            m.put("designation", x.get("emp_designation"));
            m.put("department", x.get("department"));
            m.put("dateOfJoining", date(x.get("date_of_joining")));
            m.put("employeeStatus", x.get("emp_status"));
            m.put("initiatedBy", x.get("initiated_by_name"));
            m.put("clearance", Map.of("done", x.get("items_cleared"), "total", x.get("items")));
            m.put("settlementStatus", x.get("settlement_status"));
        }
        return m;
    }

    List<Map<String, Object>> itemViews(UUID exitId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> i : repo.items(exitId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.get("id").toString());
            m.put("dept", i.get("dept"));
            m.put("title", i.get("title"));
            m.put("cleared", i.get("cleared"));
            m.put("remarks", i.get("remarks"));
            m.put("clearedAt", OnboardingService.iso(i.get("cleared_at")));
            m.put("clearedBy", i.get("cleared_by_name"));
            out.add(m);
        }
        return out;
    }

    /** Your own exit: its status and checklist, never money. */
    public Map<String, Object> mine(CurrentUser u) {
        Map<String, Object> x = repo.findCurrentOrLatest(u.id());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("noticeDays", settings.exit().noticeDays());
        boolean open = x != null && OPEN.contains((String) x.get("status"));
        m.put("canResign", x == null || !open);
        m.put("exit", x == null ? null : view(x, false));
        m.put("clearance", x == null ? List.of() : itemViews((UUID) x.get("id")));
        m.put("canWithdraw", open && "RESIGNATION".equals(x.get("kind")) && List.of("RESIGNED", "NOTICE").contains((String) x.get("status")));
        return m;
    }

    public List<Map<String, Object>> list(Map<String, String> sp) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> x : repo.findAll(sp.get("status"), sp.get("q"))) out.add(view(x, true));
        return out;
    }

    /** One case with its checklist; the settlement is a separate resource (amounts), only its state is shown here. */
    public Map<String, Object> detail(CurrentUser u, String idRaw) {
        Map<String, Object> x = load(idRaw);
        Map<String, Object> m = view(x, true);
        m.put("clearanceItems", itemViews((UUID) x.get("id")));
        m.put("canManage", u.can("exit.manage"));
        return m;
    }

    Map<String, Object> load(String idRaw) {
        Map<String, Object> x = repo.findById(Check.uuid(idRaw));
        if (x == null) throw new UserException(HttpStatus.NOT_FOUND, "Exit not found.");
        return x;
    }

    // ───────────────────────── policy ─────────────────────────

    public Map<String, Object> policy() {
        Map<String, Object> m = new LinkedHashMap<>();
        SettingsService.ExitPolicy p = settings.exit();
        m.put("noticeDays", p.noticeDays());
        m.put("encashableLeaveTypes", p.encashableLeaveTypes());
        m.put("gratuityEnabled", p.gratuityEnabled());
        m.put("gratuityMinYears", p.gratuityMinYears());
        m.put("gratuityCapRupees", p.gratuityCapRupees());
        m.put("leaveTypes", leave.activeTypes().stream().filter(t -> t.paid() && t.needsBalance()).map(t -> Map.of("code", t.code(), "name", t.name())).toList());
        return m;
    }

    public record PolicyInput(Integer noticeDays, List<String> encashableLeaveTypes, Boolean gratuityEnabled, Integer gratuityMinYears, Double gratuityCapRupees) {}

    public String savePolicy(CurrentUser u, PolicyInput in, String ip) {
        SettingsService.ExitPolicy before = settings.exit();
        int notice = in.noticeDays() == null ? before.noticeDays() : in.noticeDays();
        if (notice < 0 || notice > 365) throw new UserException(HttpStatus.BAD_REQUEST, "Notice must be between 0 and 365 days.");
        int minYears = in.gratuityMinYears() == null ? before.gratuityMinYears() : in.gratuityMinYears();
        if (minYears < 1 || minYears > 50) throw new UserException(HttpStatus.BAD_REQUEST, "Minimum service must be between 1 and 50 years.");
        double cap = in.gratuityCapRupees() == null ? before.gratuityCapRupees() : in.gratuityCapRupees();
        if (cap < 0 || cap > 100_000_000) throw new UserException(HttpStatus.BAD_REQUEST, "The gratuity ceiling must be between 0 and 10 crore rupees.");
        List<String> known = leave.activeTypes().stream().map(LeaveRepository.ActiveType::code).toList();
        List<String> types = in.encashableLeaveTypes() == null ? before.encashableLeaveTypes() : in.encashableLeaveTypes().stream().distinct().toList();
        for (String t : types) if (!known.contains(t)) throw new UserException(HttpStatus.BAD_REQUEST, "Unknown leave type " + t + ".");
        boolean gratuity = in.gratuityEnabled() == null ? before.gratuityEnabled() : in.gratuityEnabled();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("noticeDays", notice);
        value.put("encashableLeaveTypes", types);
        value.put("gratuityEnabled", gratuity);
        value.put("gratuityMinYears", minYears);
        value.put("gratuityCapRupees", cap);
        tx.executeWithoutResult(s -> {
            settings.save("exit.policy", json.valueToTree(value));
            audit.log(u.id(), "exit.policy", "setting", "exit.policy", before, value, ip);
        });
        return "Exit policy saved";
    }

    // ───────────────────────── opening a case ─────────────────────────

    public record ResignInput(String reason, String requestedLastDay) {}

    /** The employee resigns: the case opens as RESIGNED and HR is told. */
    public String resign(CurrentUser u, ResignInput in, String ip) {
        Map<String, Object> e = repo.findEmployee(u.id());
        if (e == null || !"ACTIVE".equals(e.get("status"))) throw new UserException("Only a current employee can resign.");
        if (repo.findOpen(u.id()) != null) throw conflict("You already have an exit in progress.");
        String reason = Check.trimmed(in.reason(), 3, "Tell us why you are leaving.", 1000, "Keep the reason under 1000 characters.");
        Check.isoDate(in.requestedLastDay(), "Pick your requested last working day.");
        LocalDate requested = LocalDate.parse(in.requestedLastDay());
        LocalDate today = Format.todayIn(u.timeZone());
        if (requested.isBefore(today)) throw new UserException("Your last working day can't be in the past.");
        if (requested.isBefore(day(e.get("date_of_joining")))) throw new UserException("Your last working day can't be before your joining date.");
        int notice = settings.exit().noticeDays();
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            repo.insert(id, u.id(), "RESIGNATION", "RESIGNED", reason, requested, null, today, notice, notice, null, u.id());
            addDefaultItems(id);
            audit.log(u.id(), "exit.resign", "exit", id.toString(), null, Map.of("requestedLastDay", requested.toString(), "noticeDays", notice), ip);
            for (UUID hr : repo.exitManagers()) {
                if (!hr.equals(u.id())) notifications.notify(hr, u.name() + " has resigned (last day requested: " + Format.fmtDY(requested) + ")", "/exits/" + id);
            }
        });
        return "Your resignation has been submitted. HR will confirm your last working day.";
    }

    private void addDefaultItems(UUID exitId) {
        int order = 1;
        for (String[] i : DEFAULT_ITEMS) repo.insertItem(exitId, i[0], i[1], order++);
    }

    /** The employee withdraws their own resignation, while it is still waiting for or serving notice. */
    public String withdraw(CurrentUser u, String comment, String ip) {
        Map<String, Object> x = repo.findOpen(u.id());
        if (x == null) throw new UserException(HttpStatus.NOT_FOUND, "You have no exit in progress.");
        String status = (String) x.get("status");
        if (!"RESIGNATION".equals(x.get("kind"))) throw conflict("This exit was started by HR, so only HR can cancel it.");
        if (!List.of("RESIGNED", "NOTICE").contains(status)) throw conflict("It's too late to withdraw: the exit is already at " + label(status) + ".");
        String reason = comment == null ? "" : comment.trim();
        if (reason.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the note under 500 characters.");
        UUID id = (UUID) x.get("id");
        tx.executeWithoutResult(s -> {
            repo.end(id, "WITHDRAWN", reason.isEmpty() ? null : reason);
            audit.log(u.id(), "exit.withdraw", "exit", id.toString(), Map.of("status", status), Map.of("status", "WITHDRAWN"), ip);
            for (UUID hr : repo.exitManagers()) notifications.notify(hr, u.name() + " withdrew their resignation", "/exits/" + id);
        });
        return "Your resignation has been withdrawn";
    }

    public record Created(String id, String msg) {}

    public record InitiateInput(String employeeId, String kind, String reason, String lastWorkingDay, Integer noticeDays, String noticeReason) {}

    /** HR opens a case (termination, other) — or records a resignation on the employee's behalf. */
    public Created initiate(CurrentUser u, InitiateInput in, String ip) {
        UUID employeeId = Check.uuid(in.employeeId());
        Map<String, Object> e = repo.findEmployee(employeeId);
        if (e == null) throw new UserException(HttpStatus.NOT_FOUND, "Employee not found.");
        String kind = Check.oneOf(in.kind(), KINDS);
        if (!"ACTIVE".equals(e.get("status"))) throw new UserException("Only a current employee can be put through an exit.");
        if (repo.findOpen(employeeId) != null) throw conflict("This employee already has an exit in progress.");
        String reason = Check.trimmed(in.reason(), 3, "Record the reason.", 1000, "Keep the reason under 1000 characters.");
        LocalDate lwd = null;
        if (in.lastWorkingDay() != null && !in.lastWorkingDay().isBlank()) {
            Check.isoDate(in.lastWorkingDay(), "Pick the last working day.");
            lwd = LocalDate.parse(in.lastWorkingDay());
            if (lwd.isBefore(day(e.get("date_of_joining")))) throw new UserException("The last working day can't be before the joining date.");
        }
        int policy = settings.exit().noticeDays();
        int notice = noticeFor(in.noticeDays(), policy, in.noticeReason());
        String noticeReason = notice < policy ? in.noticeReason().trim() : null;
        UUID id = UUID.randomUUID();
        LocalDate today = Format.todayIn(u.timeZone());
        LocalDate lastDay = lwd;
        tx.executeWithoutResult(s -> {
            repo.insert(id, employeeId, kind, "RESIGNATION".equals(kind) ? "RESIGNED" : "INITIATED", reason, "RESIGNATION".equals(kind) ? lastDay : null, lastDay, today,
                    notice, policy, noticeReason, u.id());
            addDefaultItems(id);
            audit.log(u.id(), "exit.initiate", "exit", id.toString(), null,
                    Map.of("employee", e.get("code"), "kind", kind, "noticeDays", notice, "lastWorkingDay", String.valueOf(lastDay)), ip);
        });
        return new Created(id.toString(), "Exit started for " + e.get("full_name"));
    }

    /** Notice for a case: the policy default, or HR's own number with a reason when it is shorter. */
    static int noticeFor(Integer requested, int policy, String reason) {
        int n = requested == null ? policy : requested;
        if (n < 0 || n > 365) throw new UserException(HttpStatus.BAD_REQUEST, "Notice must be between 0 and 365 days.");
        if (n < policy && (reason == null || reason.trim().length() < 3)) throw new UserException("Say why the notice is shortened or waived.");
        return n;
    }

    // ───────────────────────── notice ─────────────────────────

    public record NoticeInput(String lastWorkingDay, Integer noticeDays, String reason) {}

    /** HR agrees the last working day and may shorten or waive the notice (with a reason). */
    public String setNotice(CurrentUser u, String idRaw, NoticeInput in, String ip) {
        Map<String, Object> x = load(idRaw);
        String status = (String) x.get("status");
        if (!List.of("RESIGNED", "INITIATED", "NOTICE").contains(status)) throw conflict("The notice can't be changed once the exit is at " + label(status) + ".");
        Check.isoDate(in.lastWorkingDay(), "Pick the last working day.");
        LocalDate lwd = LocalDate.parse(in.lastWorkingDay());
        if (lwd.isBefore(day(x.get("date_of_joining")))) throw new UserException("The last working day can't be before the joining date.");
        int policy = ((Number) x.get("notice_policy_days")).intValue();
        int notice = noticeFor(in.noticeDays() == null ? ((Number) x.get("notice_days")).intValue() : in.noticeDays(), policy, in.reason());
        String reason = notice < policy ? in.reason().trim() : null;
        if (reason != null && reason.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the reason under 500 characters.");
        UUID id = (UUID) x.get("id");
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("lastWorkingDay", date(x.get("last_working_day")));
        before.put("noticeDays", x.get("notice_days"));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("lastWorkingDay", lwd.toString());
        after.put("noticeDays", notice);
        after.put("reason", reason);
        tx.executeWithoutResult(s -> {
            repo.setNotice(id, lwd, notice, reason);
            audit.log(u.id(), "exit.notice", "exit", id.toString(), before, after, ip);
        });
        return "Last working day set to " + Format.fmtDY(lwd) + (notice < policy ? " · notice " + (notice == 0 ? "waived" : "shortened to " + notice + " days") : "");
    }

    // ───────────────────────── status ─────────────────────────

    public record StatusInput(String status, String comment) {}

    static String label(String status) {
        return switch (status) {
            case "RESIGNED" -> "resigned";
            case "INITIATED" -> "initiated";
            case "NOTICE" -> "notice period";
            case "CLEARANCE" -> "clearance";
            case "FNF_PENDING" -> "full & final settlement";
            case "SETTLED" -> "settled";
            case "CLOSED" -> "closed";
            case "WITHDRAWN" -> "withdrawn";
            case "CANCELLED" -> "cancelled";
            default -> status;
        };
    }

    /** HR moves the case along: NOTICE (accept), CLEARANCE, FNF_PENDING, CLOSED, or CANCELLED. SETTLED comes from approving the settlement. */
    public String setStatus(CurrentUser u, String idRaw, StatusInput in, String ip) {
        String to = Check.oneOf(in.status(), List.of("NOTICE", "CLEARANCE", "FNF_PENDING", "CLOSED", "CANCELLED"));
        Map<String, Object> x = load(idRaw);
        UUID id = (UUID) x.get("id");
        UUID employeeId = (UUID) x.get("employee_id");
        String from = (String) x.get("status");
        String name = (String) x.get("emp_name");
        String comment = in.comment() == null ? "" : in.comment().trim();
        if (comment.length() > 1000) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the comment under 1000 characters.");
        String msg;
        switch (to) {
            case "NOTICE" -> {
                if (!List.of("RESIGNED", "INITIATED").contains(from)) throw conflict("Only a new exit can be accepted; this one is at " + label(from) + ".");
                if (x.get("last_working_day") == null) throw new UserException("Set the last working day first.");
                LocalDate start = day(x.get("notice_start_date"));
                tx.executeWithoutResult(s -> {
                    repo.accept(id, u.id(), start == null ? Format.todayIn(u.timeZone()) : start, comment.isEmpty() ? null : comment);
                    audit.log(u.id(), "exit.status", "exit", id.toString(), Map.of("status", from), Map.of("status", to), ip);
                    notifications.notify(employeeId, "Your exit is confirmed: last working day " + Format.fmtDY(day(x.get("last_working_day"))), "/my-exit");
                });
                msg = "Exit accepted · notice period started";
            }
            case "CLEARANCE" -> {
                if (!"NOTICE".equals(from)) throw conflict("Clearance starts from the notice period; this exit is at " + label(from) + ".");
                move(u, id, from, to, ip);
                notifications.notify(employeeId, "Clearance has started for your exit", "/my-exit");
                msg = "Clearance started";
            }
            case "FNF_PENDING" -> {
                if (!"CLEARANCE".equals(from)) throw conflict("The settlement follows clearance; this exit is at " + label(from) + ".");
                int open = repo.uncleared(id);
                if (open > 0) throw new UserException(open + " clearance item" + (open == 1 ? " is" : "s are") + " still open.");
                move(u, id, from, to, ip);
                msg = "Clearance complete · full & final settlement is next";
            }
            case "CLOSED" -> {
                if ("FNF_PENDING".equals(from)) throw new UserException("Approve the full & final settlement before closing the exit.");
                if (!"SETTLED".equals(from)) throw conflict("Only a settled exit can be closed; this one is at " + label(from) + ".");
                LocalDate lwd = day(x.get("last_working_day"));
                if (Format.todayIn(u.timeZone()).isBefore(lwd)) throw new UserException("The last working day is " + Format.fmtDY(lwd) + ". Close the exit on or after it.");
                String[] result = new String[1];
                tx.executeWithoutResult(s -> {
                    // the existing "mark as past employee": access removed, sessions revoked, department headship cleared
                    result[0] = people.finalizeExit(u.id(), employeeId, lwd.toString(), ip);
                    repo.close(id, u.id());
                    audit.log(u.id(), "exit.status", "exit", id.toString(), Map.of("status", from), Map.of("status", to), ip);
                });
                msg = result[0];
            }
            default -> {
                if (!List.of("RESIGNED", "INITIATED", "NOTICE", "CLEARANCE", "FNF_PENDING").contains(from)) {
                    throw conflict(("SETTLED".equals(from) ? "A settled exit can't be cancelled" : "This exit is already " + label(from)) + ".");
                }
                if (comment.length() < 3) throw new UserException("Say why the exit is cancelled.");
                tx.executeWithoutResult(s -> {
                    repo.end(id, "CANCELLED", comment);
                    audit.log(u.id(), "exit.status", "exit", id.toString(), Map.of("status", from), Map.of("status", "CANCELLED", "reason", comment), ip);
                    notifications.notify(employeeId, "Your exit has been cancelled by HR", "/my-exit");
                });
                msg = "Exit cancelled for " + name;
            }
        }
        return msg;
    }

    private void move(CurrentUser u, UUID id, String from, String to, String ip) {
        tx.executeWithoutResult(s -> {
            repo.setStatus(id, to);
            audit.log(u.id(), "exit.status", "exit", id.toString(), Map.of("status", from), Map.of("status", to), ip);
        });
    }

    // ───────────────────────── clearance ─────────────────────────

    public record ClearInput(Boolean cleared, String remarks) {}

    /** HR (any item) or Finance (the Finance item) marks an item cleared, with remarks, or reopens it. */
    public String clear(CurrentUser u, String idRaw, String itemRaw, ClearInput in, String ip) {
        Map<String, Object> x = load(idRaw);
        UUID id = (UUID) x.get("id");
        String status = (String) x.get("status");
        if (!List.of("NOTICE", "CLEARANCE").contains(status)) throw conflict("Items can be cleared during the notice period and clearance; this exit is at " + label(status) + ".");
        Map<String, Object> item = repo.findItem(id, Check.uuid(itemRaw));
        if (item == null) throw new UserException(HttpStatus.NOT_FOUND, "Checklist item not found.");
        if (!u.can("exit.manage") && !("FINANCE".equals(item.get("dept")) && u.can("pay.view"))) throw new ForbiddenException();
        if (in.cleared() == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean, received undefined");
        String remarks = in.remarks() == null ? "" : in.remarks().trim();
        if (remarks.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the remarks under 300 characters.");
        boolean cleared = in.cleared();
        tx.executeWithoutResult(s -> {
            repo.setItem((UUID) item.get("id"), cleared, remarks.isEmpty() ? null : remarks, u.id());
            audit.log(u.id(), "exit.clearance", "exit", id.toString(), Map.of("item", item.get("title"), "cleared", item.get("cleared")),
                    Map.of("item", item.get("title"), "cleared", cleared, "remarks", remarks), ip);
        });
        return cleared ? "Cleared: " + item.get("title") : "Reopened: " + item.get("title");
    }

    public record ItemInput(String dept, String title) {}

    public String addItem(CurrentUser u, String idRaw, ItemInput in, String ip) {
        Map<String, Object> x = load(idRaw);
        UUID id = (UUID) x.get("id");
        String status = (String) x.get("status");
        if (!OPEN.contains(status) || "SETTLED".equals(status)) throw conflict("Items can't be added once the exit is at " + label(status) + ".");
        String dept = Check.oneOf(in.dept(), DEPTS);
        String title = Check.trimmed(in.title(), 3, "Name the item.", 120, "Keep the item under 120 characters.");
        tx.executeWithoutResult(s -> {
            repo.insertItem(id, dept, title, repo.nextItemOrder(id));
            audit.log(u.id(), "exit.clearance_item", "exit", id.toString(), null, Map.of("dept", dept, "title", title), ip);
        });
        return "Added to the checklist";
    }
}
