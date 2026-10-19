package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.FnfEngine;
import com.jurislpo.jurisone.engine.FnfEngine.Line;
import com.jurislpo.jurisone.engine.FnfEngine.Result;
import com.jurislpo.jurisone.engine.PayrollEngine;
import com.jurislpo.jurisone.engine.TaxEngine;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.ExitRepository;
import com.jurislpo.jurisone.repository.SettlementRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.TaxService.MonthFigures;
import com.jurislpo.jurisone.service.TaxService.Projection;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Full & final settlement of an exit. Finance ({@code pay.view}) drafts it, {@code pay.close} approves it; an approved
 * settlement is frozen as a snapshot (and cannot be updated in the database either). Amounts are integer paise.
 *
 * <p>The computation is {@link FnfEngine}; this class gathers its inputs: the structure and attendance LOP of the last
 * partial month through {@link PayrollService#computeRun}, the leave balances of the encashable types, the notice
 * HR agreed, and the final month's TDS through {@link TaxEngine}. The employee's regular payroll skips them once the
 * settlement is approved, so the last month is paid exactly once.
 */
@Service
public class FnfService {

    public static final int MAX_LINES = 20;
    static final long MAX_AMOUNT = 100_000_000_00L; // ₹10 crore, paise

    private final ExitRepository exits;
    private final SettlementRepository repo;
    private final ExitService exitService;
    private final PayrollService payroll;
    private final TaxService tax;
    private final SettingsService settings;
    private final AuditService audit;
    private final NotificationService notifications;
    private final SettlementPdf pdf;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public FnfService(ExitRepository exits, SettlementRepository repo, ExitService exitService, PayrollService payroll, TaxService tax, SettingsService settings,
                      AuditService audit, NotificationService notifications, SettlementPdf pdf, TransactionTemplate tx, ObjectMapper json) {
        this.exits = exits;
        this.repo = repo;
        this.exitService = exitService;
        this.payroll = payroll;
        this.tax = tax;
        this.settings = settings;
        this.audit = audit;
        this.notifications = notifications;
        this.pdf = pdf;
        this.tx = tx;
        this.json = json;
    }

    /** The computation plus what the statement needs to explain it. */
    record Calc(Result result, String month, Map<String, BigDecimal> leaveDays, String regime) {}

    // ───────────────────────── view ─────────────────────────

    /** The settlement: the approved snapshot, else the draft recomputed now; null result when it can't be computed yet. */
    public Map<String, Object> view(CurrentUser u, String idRaw) {
        Map<String, Object> x = exitService.load(idRaw);
        UUID exitId = (UUID) x.get("id");
        Map<String, Object> s = repo.findByExit(exitId);
        String exitStatus = (String) x.get("status");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exitStatus", exitStatus);
        m.put("lastWorkingDay", ExitService.date(x.get("last_working_day")));
        boolean approved = s != null && "APPROVED".equals(s.get("status"));
        m.put("status", s == null ? "NONE" : s.get("status"));
        m.put("canDraft", "FNF_PENDING".equals(exitStatus) && !approved && u.can("pay.view"));
        m.put("canApprove", "FNF_PENDING".equals(exitStatus) && s != null && !approved && u.can("pay.close"));
        m.put("additions", lines(s == null ? "[]" : (String) s.get("additions")));
        m.put("deductions", lines(s == null ? "[]" : (String) s.get("deductions")));
        m.put("note", s == null ? null : s.get("note"));
        if (approved) {
            JsonNode snap = read((String) s.get("snapshot"));
            m.put("result", snap.get("result"));
            m.put("leave", snap.get("leave"));
            m.put("netPaise", s.get("net_paise"));
            m.put("approvedAt", OnboardingService.iso(s.get("approved_at")));
            m.put("approvedBy", s.get("approved_by_name"));
            m.put("month", snap.get("month"));
            return m;
        }
        m.put("draftedBy", s == null ? null : s.get("drafted_by_name"));
        m.put("draftedAt", s == null ? null : OnboardingService.iso(s.get("drafted_at")));
        String blocker = blocker(x);
        m.put("blocker", blocker);
        if (blocker == null && List.of("FNF_PENDING", "SETTLED").contains(exitStatus)) {
            Calc c = calculate(x, lines(s == null ? "[]" : (String) s.get("additions")), lines(s == null ? "[]" : (String) s.get("deductions")));
            m.put("result", json.valueToTree(c.result()));
            m.put("leave", leaveJson(c));
            m.put("netPaise", c.result().net());
            m.put("month", c.month());
        }
        return m;
    }

    /** Why the settlement can't be computed yet, or null. */
    String blocker(Map<String, Object> x) {
        if (x.get("last_working_day") == null) return "Set the last working day first.";
        LocalDate lwd = ExitService.day(x.get("last_working_day"));
        if (Format.todayIn("Asia/Kolkata").isBefore(lwd)) return "The settlement can be prepared after the last working day (" + Format.fmtDY(lwd) + ").";
        if (!"IN".equals(x.get("emp_region"))) return "Settlements are prepared for India payroll employees only.";
        return null;
    }

    // ───────────────────────── draft ─────────────────────────

    public record LineInput(String label, Long amount) {}

    public record DraftInput(List<LineInput> additions, List<LineInput> deductions, String note) {}

    List<Line> validLines(List<LineInput> in, String what) {
        List<Line> out = new ArrayList<>();
        if (in == null) return out;
        if (in.size() > MAX_LINES) throw new UserException(HttpStatus.BAD_REQUEST, "At most " + MAX_LINES + " " + what + ".");
        for (LineInput l : in) {
            String label = Check.trimmed(l.label(), 1, "Name every line.", 80, "Keep each line name under 80 characters.");
            if (l.amount() == null || l.amount() < 0) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected number to be >=0");
            if (l.amount() > MAX_AMOUNT) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=" + MAX_AMOUNT);
            out.add(new Line(label, l.amount()));
        }
        return out;
    }

    /** Finance saves the additions / deductions and the computation becomes the draft. */
    public String saveDraft(CurrentUser u, String idRaw, DraftInput in, String ip) {
        Map<String, Object> x = exitService.load(idRaw);
        UUID exitId = (UUID) x.get("id");
        requireSettling(x);
        Map<String, Object> existing = repo.findByExit(exitId);
        if (existing != null && "APPROVED".equals(existing.get("status"))) throw ExitService.conflict("This settlement is already approved and can't be changed.");
        String blocker = blocker(x);
        if (blocker != null) throw new UserException(blocker);
        List<Line> additions = validLines(in.additions(), "additions"), deductions = validLines(in.deductions(), "deductions");
        String note = in.note() == null ? "" : in.note().trim();
        if (note.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the note under 500 characters.");
        Calc c = calculate(x, additions, deductions);
        UUID employeeId = (UUID) x.get("employee_id");
        long before = existing == null || existing.get("net_paise") == null ? 0 : ((Number) existing.get("net_paise")).longValue();
        tx.executeWithoutResult(s -> {
            repo.saveDraft(exitId, employeeId, c.month(), write(additions), write(deductions), note.isEmpty() ? null : note, write(c.result()), c.result().net(), u.id());
            audit.log(u.id(), "fnf.draft", "fnf_settlement", exitId.toString(), existing == null ? null : Map.of("netPaise", before),
                    Map.of("netPaise", c.result().net(), "additions", additions.size(), "deductions", deductions.size()), ip);
        });
        return "Settlement draft saved · net " + (c.result().net() < 0 ? "recoverable " : "payable ") + Format.inr(Math.abs(c.result().net()));
    }

    // ───────────────────────── approve ─────────────────────────

    /** {@code pay.close}: recomputes with the saved lines, freezes the statement, takes encashed leave off the balance and settles the exit. */
    public String approve(CurrentUser u, String idRaw, String ip) {
        Map<String, Object> x = exitService.load(idRaw);
        UUID exitId = (UUID) x.get("id");
        requireSettling(x);
        Map<String, Object> s = repo.findByExit(exitId);
        if (s != null && "APPROVED".equals(s.get("status"))) throw ExitService.conflict("This settlement is already approved.");
        if (s == null) throw new UserException("Prepare the settlement first: save the draft, then approve it.");
        String blocker = blocker(x);
        if (blocker != null) throw new UserException(blocker);
        Calc c = calculate(x, lines((String) s.get("additions")), lines((String) s.get("deductions")));
        UUID employeeId = (UUID) x.get("employee_id");
        Map<String, Object> snapshot = snapshot(x, c, (String) s.get("note"), u);
        String name = (String) x.get("emp_name");
        tx.executeWithoutResult(sx -> {
            int n = repo.approve(exitId, c.month(), write(c.result()), c.result().net(), write(snapshot), u.id());
            if (n != 1) throw ExitService.conflict("This settlement is already approved.");
            for (Map.Entry<String, BigDecimal> e : c.leaveDays().entrySet()) {
                if (e.getValue().signum() > 0) repo.insertEncashment(employeeId, e.getKey(), e.getValue().negate(), exitId.toString());
            }
            exits.settle(exitId);
            audit.log(u.id(), "fnf.approve", "fnf_settlement", exitId.toString(), Map.of("status", "DRAFT"),
                    Map.of("status", "APPROVED", "netPaise", c.result().net(), "month", c.month()), ip);
            notifications.notify(employeeId, "Your full & final settlement has been approved", "/my-exit");
        });
        return "Settlement approved for " + name + " · net " + (c.result().net() < 0 ? "recoverable " : "payable ") + Format.inr(Math.abs(c.result().net()));
    }

    private void requireSettling(Map<String, Object> x) {
        String status = (String) x.get("status");
        if (!"FNF_PENDING".equals(status)) {
            throw ExitService.conflict("The settlement is prepared once clearance is complete; this exit is at " + ExitService.label(status) + ".");
        }
    }

    // ───────────────────────── statement ─────────────────────────

    public record Statement(String filename, byte[] bytes) {}

    /** The PDF of the approved settlement, from its frozen snapshot. */
    public Statement statement(String idRaw) {
        Map<String, Object> x = exitService.load(idRaw);
        Map<String, Object> s = repo.findByExit((UUID) x.get("id"));
        if (s == null || !"APPROVED".equals(s.get("status"))) throw ExitService.conflict("The statement is available once the settlement is approved.");
        JsonNode snap = read((String) s.get("snapshot"));
        return new Statement("FnF-" + x.get("emp_code") + ".pdf", pdf.render(snap));
    }

    // ───────────────────────── computation ─────────────────────────

    Calc calculate(Map<String, Object> x, List<Line> additions, List<Line> deductions) {
        UUID employeeId = (UUID) x.get("employee_id");
        LocalDate lwd = ExitService.day(x.get("last_working_day"));
        LocalDate doj = ExitService.day(x.get("date_of_joining"));
        YearMonth month = YearMonth.from(lwd);
        String ym = month.toString();
        List<PayrollService.PayRow> rows = payroll.computeRun(ym, List.of(employeeId), Map.of(employeeId, lwd));
        if (rows.isEmpty()) throw new UserException("There is no salary structure in force on the last working day. Add one under Salary structures first.");
        PayrollService.PayRow row = rows.get(0);

        PayrollService.RunStatus rs = payroll.runStatus(ym);
        boolean alreadyPaid = rs.run() != null && "CLOSED".equals(rs.run().get("status")) && repo.closedPayslip(employeeId, ym) != null;
        JsonNode paidSlip = alreadyPaid ? read(repo.closedPayslip(employeeId, ym)) : null;

        SettingsService.ExitPolicy policy = settings.exit();
        SettingsService.PayrollPolicy pay = settings.payroll();
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        double encashable = 0;
        if (!policy.encashableLeaveTypes().isEmpty()) {
            for (Map.Entry<String, BigDecimal> b : repo.ledgerBalances(employeeId, policy.encashableLeaveTypes()).entrySet()) {
                if (b.getValue() != null && b.getValue().signum() > 0) {
                    balances.put(b.getKey(), b.getValue());
                    encashable += b.getValue().doubleValue();
                }
            }
        }

        FnfEngine.Input in = new FnfEngine.Input(row.structure(), month, doj, lwd, row.pay().lopDays(), alreadyPaid, encashable,
                ((Number) x.get("notice_days")).intValue(), ExitService.day(x.get("notice_start_date")), policy.gratuityEnabled(), policy.gratuityMinYears(),
                Math.round(policy.gratuityCapRupees() * 100), additions, deductions, pay.pfRate(), Math.round(pay.pfWageCeiling() * 100),
                Math.round(pay.professionalTax().monthly() * 100));
        String[] regime = new String[1];
        Result r = FnfEngine.compute(in, base -> finalMonthTds(employeeId, ym, base, paidSlip, regime));
        return new Calc(r, ym, balances, regime[0] == null ? "NEW" : regime[0]);
    }

    /**
     * TDS on the final month through the existing {@link TaxEngine}: annual tax on everything earned this FY (closed
     * payslips, earlier months, this settlement, nothing projected after the last working day) less the TDS already
     * deducted, all of it in this one month (remaining months = 1).
     */
    long finalMonthTds(UUID employeeId, String ym, FnfEngine.TaxBase base, JsonNode paidSlip, String[] regimeOut) {
        long gross = base.gross(), basic = base.basic(), hra = base.hra(), pf = base.pf();
        long paidTds = 0;
        if (paidSlip != null) { // payroll for this month already ran: its earnings and TDS belong to the same financial year
            gross += paidSlip.get("grossEarned").asLong();
            basic += paidSlip.get("components").get(0).get("earned").asLong();
            hra += paidSlip.get("components").get(1).get("earned").asLong();
            pf += paidSlip.get("pf").asLong();
            paidTds = paidSlip.get("tds").asLong();
        }
        Projection p = tax.projectTax(employeeId, ym, new MonthFigures(gross, basic, hra, pf), 0, 0, 0, null, null);
        regimeOut[0] = p.regime();
        double alreadyRupees = p.prevTds() + p.jurisTdsSoFar() + paidTds / 100.0;
        return TaxEngine.monthlyTds(p.current().total(), alreadyRupees, 1) * 100;
    }

    // ───────────────────────── snapshot & json ─────────────────────────

    Map<String, Object> snapshot(Map<String, Object> x, Calc c, String note, CurrentUser approver) {
        UUID employeeId = (UUID) x.get("employee_id");
        Map<String, Object> card = repo.employeeCard(employeeId);
        SettingsService.Company company = settings.company();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 1);
        m.put("month", c.month());
        m.put("company", Map.of("name", company.name(), "address", company.address()));
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", card.get("code"));
        e.put("name", card.get("full_name"));
        e.put("designation", card.get("designation"));
        e.put("department", card.get("department"));
        e.put("doj", ExitService.date(card.get("date_of_joining")));
        e.put("panLast4", card.get("pan_last4"));
        e.put("bankName", card.get("bank_name"));
        e.put("bankLast4", card.get("bank_account_last4"));
        m.put("employee", e);
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("kind", x.get("kind"));
        ex.put("reason", x.get("reason"));
        ex.put("lastWorkingDay", ExitService.date(x.get("last_working_day")));
        ex.put("noticeStartDate", ExitService.date(x.get("notice_start_date")));
        ex.put("noticeDays", x.get("notice_days"));
        ex.put("noticeReason", x.get("notice_reason"));
        m.put("exit", ex);
        m.put("regime", c.regime());
        m.put("result", json.valueToTree(c.result()));
        m.put("leave", leaveJson(c));
        m.put("net", c.result().net());
        m.put("netWords", PayrollEngine.rupeesInWords(Math.abs(c.result().net()) / 100.0));
        m.put("note", note);
        m.put("approvedBy", approver.name());
        m.put("approvedOn", Format.todayIn("Asia/Kolkata").toString());
        return m;
    }

    private JsonNode leaveJson(Calc c) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : c.leaveDays().entrySet()) l.add(Map.of("type", e.getKey(), "days", e.getValue()));
        return json.valueToTree(l);
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Line> lines(String text) {
        try {
            return json.readValue(text, new TypeReference<List<Line>>() {});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
