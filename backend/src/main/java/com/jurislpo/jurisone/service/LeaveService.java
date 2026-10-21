package com.jurislpo.jurisone.service;

import static com.jurislpo.jurisone.engine.Num.round2;

import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.LeavePart;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.LeaveRules;
import com.jurislpo.jurisone.repository.LeaveRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Leave balances, the apply preview, apply / cancel, and the Leave page. */
@Service
public class LeaveService {

    private final LeaveRepository repo;
    private final AttendanceService att;
    private final AuditService audit;

    public LeaveService(LeaveRepository repo, AttendanceService attendance, AuditService audit) {
        this.repo = repo;
        this.att = attendance;
        this.audit = audit;
    }

    public record Balance(String code, String name, double balance, double pending, boolean paid, boolean needsBalance, double annual, Double maxStretch) {}

    /** Ledger balance per leave type, plus days held by pending requests. */
    public List<Balance> balances(UUID employeeId) {
        Map<String, BigDecimal> sums = repo.ledgerSums(employeeId);
        Map<String, double[]> pending = repo.pendingDays(employeeId);
        List<Balance> out = new ArrayList<>();
        for (LeaveRepository.ActiveType activeType : repo.activeTypes()) {
            String code = activeType.code();
            double[] p = pending.get(code);
            out.add(new Balance(code, activeType.name(), round2(sums.containsKey(code) && sums.get(code) != null ? sums.get(code).doubleValue() : 0),
                    p != null ? round2(p[0] - p[1]) : 0, activeType.paid(), activeType.needsBalance(), activeType.annualQuota(), activeType.maxStretch()));
        }
        return out;
    }

    /** The employee's holidays (own region + everyone) between two dates. */
    public Map<LocalDate, String> holidayMap(String region, LocalDate from, LocalDate to) {
        return repo.holidays(region, from, to);
    }

    static final String EL_ON_PROBATION = "Earned leave isn't available during probation. Choose another leave type.";

    public record Skipped(String date, String name) {}

    public record Preview(String from, String to, List<String> dates, List<Skipped> skipped, double days, double available, double paidDays, double lopDays,
                          Double balanceAfter, String error) {
        Preview withError(String e) {
            return new Preview(from, to, dates, skipped, days, available, paidDays, lopDays, balanceAfter, e);
        }
    }

    /**
     * Everything the apply form shows and the server enforces: working days, balance after, LOP if
     * the balance is short, plus the rules (no past dates, max stretch, overlaps, joining/exit).
     */
    public Preview previewRequest(AttendanceEmployee employee, String typeCode, LocalDate fromIn, LocalDate toIn, LeavePart part, UUID excludeRequestId, boolean forDecision) {
        LocalDate from = fromIn, to = toIn;
        if (to.isBefore(from)) {
            LocalDate x = from;
            from = to;
            to = x;
        }
        AttendanceService.ResolvedAttendancePolicy policy = att.policy();
        List<Map<String, Object>> types = repo.findTypeRules(typeCode);
        Preview base = new Preview(from.toString(), to.toString(), List.of(), List.of(), 0, 0, 0, 0, null, null);
        if (types.isEmpty() || !Boolean.TRUE.equals(types.get(0).get("active"))) return base.withError("Pick a leave type.");
        Map<String, Object> type = types.get(0);
        if (!forDecision && "EL".equals(typeCode) && repo.onProbation(employee.id())) return base.withError(EL_ON_PROBATION);
        Map<LocalDate, String> hol = holidayMap(employee.region(), from, to);
        LeaveRules.WorkingDays workingDays = LeaveRules.workingDays(from, to, policy.engine().weeklyOffs(), hol);
        double days = LeaveRules.requestDays(workingDays.dates(), workingDays.dates().size() == 1 ? part : LeavePart.FULL);
        Balance bal = balances(employee.id()).stream().filter(b -> b.code().equals(typeCode)).findFirst().orElseThrow();
        // pending requests already hold part of the balance (except the one being decided)
        double held = bal.pending();
        if (excludeRequestId != null) {
            List<Map<String, Object>> self = repo.findRequestHold(excludeRequestId);
            if (!self.isEmpty() && "PENDING".equals(self.get(0).get("status"))) {
                held = round2(held - (((BigDecimal) self.get(0).get("days")).doubleValue() - ((BigDecimal) self.get(0).get("lop_days")).doubleValue()));
            }
        }
        double available = round2(bal.balance() - held);
        boolean needsBalance = Boolean.TRUE.equals(type.get("needs_balance"));
        LeaveRules.LopSplit split = LeaveRules.lopSplit(days, available, needsBalance, Boolean.TRUE.equals(type.get("paid")));
        Preview out = new Preview(from.toString(), to.toString(), workingDays.dates().stream().map(LocalDate::toString).toList(),
                workingDays.skipped().stream().map(s -> new Skipped(s.date().toString(), s.name())).toList(), days, available, split.paidDays(), split.lopDays(),
                needsBalance ? round2(available - split.paidDays()) : null, null);

        LocalDate today = att.currentWorkDate(employee, Instant.now());
        if (!forDecision && from.isBefore(today)) return out.withError("Leave can't start in the past. For a missed day, request a regularization.");
        if (from.isBefore(employee.dateOfJoining())) return out.withError("Leave can't start before your joining date.");
        if (employee.exitDate() != null && to.isAfter(employee.exitDate())) return out.withError("Leave can't go beyond your last working day.");
        if (ChronoUnit.DAYS.between(from, to) + 1 > 62) return out.withError("Apply for at most two months at a time.");
        if (workingDays.dates().isEmpty()) return out.withError("Those dates are all weekends or holidays — nothing to apply for.");
        BigDecimal maxStretch = (BigDecimal) type.get("max_stretch");
        if (maxStretch != null && days > maxStretch.doubleValue()) {
            return out.withError(type.get("name") + " can be taken for at most " + num(maxStretch.doubleValue()) + " days at a stretch.");
        }
        if (att.isMonthLocked(from.toString().substring(0, 7), policy, Instant.now())) return out.withError("Attendance for that month is locked.");
        List<Map<String, Object>> overlap = repo.findOverlap(employee.id(), from, to, excludeRequestId);
        if (!overlap.isEmpty()) {
            Map<String, Object> o = overlap.get(0);
            return out.withError("You already have a " + ((String) o.get("status")).toLowerCase() + " request from " + o.get("from_date") + " to " + o.get("to_date") + ".");
        }
        return out;
    }

    /** JS number → text (2 not 2.0). */
    static String num(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    /** Records leave used from the balance when a request is approved. Joins the caller's transaction. */
    public void postUsage(UUID requestId, UUID employeeId, String leaveType, double paidDays) {
        if (paidDays <= 0) return;
        Map<String, Object> row = repo.findTypeFlags(leaveType);
        if (!Boolean.TRUE.equals(row.get("needs_balance")) || !Boolean.TRUE.equals(row.get("paid"))) return;
        repo.insertUsage(employeeId, leaveType, BigDecimal.valueOf(-paidDays), requestId.toString());
    }

    // ───────────────────────── apply / cancel ─────────────────────────

    public record ApplyInput(String type, String from, String to, String part, String reason) {}

    record Parsed(String type, LocalDate from, LocalDate to, LeavePart part, String reason) {}

    static Parsed parse(ApplyInput i) {
        String type = i.type();
        if (type == null || type.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected string to have >=1 characters");
        if (type.length() > 10) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected string to have <=10 characters");
        Check.isoDate(i.from(), "Pick your dates on the calendar.");
        Check.isoDate(i.to(), "Pick your dates on the calendar.");
        LeavePart part = LeavePart.valueOf(Check.oneOf(i.part(), List.of("FULL", "FIRST_HALF", "SECOND_HALF")));
        String reason = i.reason() == null ? "" : i.reason().trim();
        if (reason.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the reason under 500 characters.");
        return new Parsed(type, LocalDate.parse(i.from()), LocalDate.parse(i.to()), part, reason);
    }

    AttendanceEmployee me(CurrentUser user) {
        List<AttendanceEmployee> e = att.loadAttendanceEmployees("e.id = ?", user.id());
        if (e.isEmpty()) throw new UserException("Leave isn't tracked for your account.");
        return e.get(0);
    }

    /** Live numbers for the apply form — same rules the server enforces on submit. */
    public Preview preview(CurrentUser user, ApplyInput i) {
        AttendanceEmployee employee = me(user);
        Parsed f = parse(i);
        return previewRequest(employee, f.type(), f.from(), f.to(), f.part(), null, false);
    }

    public String apply(CurrentUser user, ApplyInput i, String ip) {
        AttendanceEmployee employee = me(user);
        Parsed parsed = parse(i);
        Preview preview = previewRequest(employee, parsed.type(), parsed.from(), parsed.to(), parsed.part(), null, false);
        if (preview.error() != null) throw new UserException(preview.error());
        LeavePart part = preview.dates().size() == 1 ? parsed.part() : LeavePart.FULL;
        String typeName = repo.findTypeName(parsed.type());
        UUID id = UUID.randomUUID();
        repo.insertRequest(id, user.id(), parsed.type(), Date.valueOf(preview.from()), Date.valueOf(preview.to()), part.name(), BigDecimal.valueOf(preview.days()),
                BigDecimal.valueOf(preview.lopDays()), parsed.reason().isEmpty() ? null : parsed.reason());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("type", parsed.type());
        after.put("from", preview.from());
        after.put("to", preview.to());
        after.put("part", part.name());
        after.put("days", preview.days());
        after.put("lopDays", preview.lopDays());
        audit.log(user.id(), "leave.apply", "leave_request", id.toString(), null, after, ip);
        String span = preview.from().equals(preview.to()) ? Format.formatDayMonth(LocalDate.parse(preview.from())) : Format.formatDayMonth(LocalDate.parse(preview.from())) + " – " + Format.formatDayMonth(LocalDate.parse(preview.to()));
        return typeName + " for " + num(preview.days()) + " day" + (preview.days() == 1 ? "" : "s") + " (" + span + ") sent to HR"
                + (preview.lopDays() != 0 ? " · " + num(preview.lopDays()) + " day(s) unpaid" : "");
    }

    public String cancel(CurrentUser user, String idRaw) {
        UUID id = Check.uuid(idRaw);
        // atomic: only a still-pending request of mine can be cancelled
        int count = repo.cancelPending(id, user.id());
        if (count == 0) throw new UserException("Only your pending requests can be cancelled.");
        audit.log(user.id(), "leave.cancel", "leave_request", id.toString(), Map.of("status", "PENDING"), Map.of("status", "CANCELLED"), null);
        return "Leave request cancelled";
    }

    // ───────────────────────── page ─────────────────────────

    static final List<String> STATUS = List.of("All", "PENDING", "APPROVED", "DECLINED", "CANCELLED");
    static final List<String> SORTS = List.of("type", "from", "days", "applied", "status");

    public record RequestRow(String id, String typeName, String leaveType, String from, String to, String part, double days, double lopDays, String applied,
                         String reason, String status, String decidedBy, Instant appliedAt) {}

    /** {@code lockedReason}: why this type can't be applied for right now (null = it can). */
    public record TypeOption(String code, String name, boolean paid, Double available, String lockedReason) {}

    public record HolidayView(String id, String date, String name) {}

    public Map<String, Object> page(CurrentUser user, Map<String, String> queryParams) {
        String tab = ListParams.pick(queryParams, "tab", List.of("requests", "holidays"), "requests");
        String st = ListParams.pick(queryParams, "status", STATUS, "All");
        ListParams listParams = ListParams.of(queryParams, SORTS, "applied", 50, "desc");
        List<AttendanceEmployee> employees = att.loadAttendanceEmployees("e.id = ?", user.id());
        AttendanceEmployee employee = employees.isEmpty() ? null : employees.get(0);
        AttendanceService.ResolvedAttendancePolicy policy = att.policy();
        Instant now = Instant.now();
        LocalDate today = employee != null ? att.currentWorkDate(employee, now) : Format.isoInZone(now, user.timeZone());
        int year = today.getYear();

        List<Balance> bals = balances(user.id());
        List<RequestRow> reqs = new ArrayList<>(repo.findRequests(user.id(), st, user.timeZone()));
        Map<String, Function<RequestRow, ? extends Comparable>> keys = Map.of("type", RequestRow::typeName, "from", RequestRow::from, "days", RequestRow::days,
                "applied", r -> r.appliedAt().toEpochMilli(), "status", RequestRow::status);
        reqs.sort(listParams.comparator(keys));

        double lopYear = 0;
        if (employee != null) {
            LocalDate start = LocalDate.of(year, 1, 1);
            if (policy.trackingStart().isAfter(start)) start = policy.trackingStart();
            if (employee.dateOfJoining().isAfter(start)) start = employee.dateOfJoining();
            lopYear = AttendanceEngine.summarize(att.attendanceFor(List.of(employee), start, today, now).get(employee.id()).stream().filter(x -> !x.r.future).map(x -> x.r).toList(),
                    policy.engine()).lopDays;
        }
        List<HolidayView> holidays = repo.findHolidayViews(user.region(), Date.valueOf(LocalDate.of(year, 1, 1)), Date.valueOf(LocalDate.of(year, 12, 31)));
        Map<String, String> upcoming = repo.upcomingHolidays(user.region(), Date.valueOf(today));
        boolean probation = repo.onProbation(user.id());
        List<TypeOption> types = bals.stream().map(b -> new TypeOption(b.code(), b.name(), b.paid(), b.needsBalance() ? round2(b.balance() - b.pending()) : null, "EL".equals(b.code()) && probation ? EL_ON_PROBATION : null)).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tab", tab);
        out.put("status", st);
        out.put("today", today.toString());
        out.put("year", String.valueOf(year));
        out.put("canApply", employee != null);
        out.put("balances", bals);
        out.put("lopYear", lopYear);
        out.put("requests", reqs);
        out.put("holidays", holidays);
        out.put("holidayMap", upcoming);
        out.put("types", types);
        out.put("weeklyOffs", policy.engine().weeklyOffs().stream().sorted().toList());
        return out;
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
