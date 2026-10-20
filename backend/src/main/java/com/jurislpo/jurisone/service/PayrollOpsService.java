package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.engine.Csv;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.repository.PayrollOpsRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Payroll actions, close & publish, salary structures, payslips and payroll exports. */
@Service
public class PayrollOpsService {

    private final PayrollOpsRepository repo;
    private final TransactionTemplate tx;
    private final PayrollService payroll;
    private final PayslipPdf pdf;
    private final SettingsService settings;
    private final AuditService audit;
    private final MailService mail;
    private final PiiCrypto pii;
    private final ObjectMapper json;
    private final Path projectDir;
    private final String uploadDir;
    private final String appUrl;

    public PayrollOpsService(PayrollOpsRepository repo, TransactionTemplate tx, PayrollService payroll, PayslipPdf pdf, SettingsService settings, AuditService audit,
                             MailService mail, PiiCrypto pii, ObjectMapper json, JurisOneProperties props, @Value("${jurisone.upload-dir:./storage}") String uploadDir,
                             @Value("${jurisone.app-url:http://localhost:3005}") String appUrl) {
        this.repo = repo;
        this.tx = tx;
        this.payroll = payroll;
        this.pdf = pdf;
        this.settings = settings;
        this.audit = audit;
        this.mail = mail;
        this.pii = pii;
        this.json = json;
        this.projectDir = Path.of(props.projectDir() == null ? "." : props.projectDir()).toAbsolutePath().normalize();
        this.uploadDir = uploadDir;
        this.appUrl = appUrl.replaceAll("/$", "");
    }

    static String ym(String v) {
        if (v == null || !v.matches("^\\d{4}-\\d{2}$")) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid string: must match pattern /^\\d{4}-\\d{2}$/");
        return v;
    }

    /** A DB-stored relative path (may use Windows separators) → absolute file under the project directory. */
    public Path resolveStored(String stored) {
        Path p = projectDir.resolve(stored.replace('\\', '/')).normalize();
        if (!p.startsWith(projectDir)) throw new IllegalArgumentException("bad path");
        return p;
    }

    // ───────────────────────── page ─────────────────────────

    public Map<String, Object> page(CurrentUser u, Map<String, String> sp) {
        LocalDate today = Format.todayIn("Asia/Kolkata");
        PayrollService.Months mo = payroll.payrollMonths(today);
        String m = ListParams.str(sp, "m", mo.dflt());
        String month = mo.months().contains(m) ? m : mo.dflt();
        PayrollService.View v = payroll.payrollView(month);
        LocalDate lastDay = YearMonth.parse(month).atEndOfMonth();
        List<String> missing = v.closed() ? List.of() : repo.namesWithoutStructure(lastDay);
        Map<String, Object> run = v.run();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("months", mo.months());
        out.put("closed", v.closed());
        out.put("attendanceLocked", v.attendanceLocked());
        out.put("approved", (run != null && run.get("approved_at") != null) || v.closed());
        out.put("closedOn", run != null && run.get("closed_at") != null ? Format.isoInZone(((Timestamp) run.get("closed_at")).toInstant(), u.timeZone()).toString() : null);
        out.put("missing", missing);
        out.put("rows", v.rows());
        out.put("view", PayrollService.filter(v.rows(), sp));
        out.put("canClose", u.can("pay.close"));
        out.put("canStructures", u.can("struct.manage"));
        return out;
    }

    // ───────────────────────── adjustments ─────────────────────────

    private UUID openRun(String month) {
        PayrollService.RunStatus rs = payroll.runStatus(month);
        if (rs.run() != null && "CLOSED".equals(rs.run().get("status"))) {
            throw new UserException(Format.ymLabel(month) + " payroll is closed. Corrections go into the next month as arrears.");
        }
        if (rs.run() != null) return (UUID) rs.run().get("id");
        UUID id = UUID.randomUUID();
        repo.insertOpenRun(id, month);
        return id;
    }

    public record AdjInput(String month, String employeeId, Double lopDays, Double bonus, Double otherDeduction, String note) {}

    static long wholeRupees(Double v) {
        if (v == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected number, received undefined");
        if (v != Math.rint(v)) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected int, received number");
        if (v < 0) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected number to be >=0");
        if (v > 100_000_000) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=100000000");
        return v.longValue();
    }

    /** Finance edits LOP / bonus / other deduction / note while the run is open (each edit audited). */
    public String saveAdjustment(CurrentUser u, AdjInput f, String ip) {
        String month = ym(f.month());
        UUID employeeId = Check.uuid(f.employeeId());
        if (f.lopDays() != null) {
            if (f.lopDays() < 0) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected number to be >=0");
            if (f.lopDays() > 31) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=31");
            if (f.lopDays() * 2 != Math.rint(f.lopDays() * 2)) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid number: must be a multiple of 0.5");
        }
        long bonus = wholeRupees(f.bonus()), other = wholeRupees(f.otherDeduction());
        String note = Check.str(f.note()).trim();
        if (note.length() > 200) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected string to have <=200 characters");
        UUID runId = openRun(month);
        List<Map<String, Object>> emp = repo.employeeRegion(employeeId);
        if (emp.isEmpty() || !"IN".equals(emp.get(0).get("region"))) throw new UserException("Only India payroll employees can be edited.");
        List<Map<String, Object>> before = repo.findAdjustment(runId, employeeId);
        Long bonusP = bonus * 100, otherP = other * 100;
        String noteV = note.isEmpty() ? null : note;
        tx.executeWithoutResult(s -> {
            repo.upsertAdjustment(runId, employeeId, f.lopDays() == null ? null : BigDecimal.valueOf(f.lopDays()), bonusP, otherP, noteV, u.id());
            repo.resetRunToReview(runId);
            Map<String, Object> b = null;
            if (!before.isEmpty()) {
                Map<String, Object> x = before.get(0);
                b = new LinkedHashMap<>();
                b.put("lopDays", x.get("lop_days") == null ? null : ((BigDecimal) x.get("lop_days")).doubleValue());
                b.put("bonus", x.get("bonus"));
                b.put("otherDeduction", x.get("other_deduction"));
                b.put("note", x.get("note"));
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("lopDays", f.lopDays());
            a.put("bonus", bonusP);
            a.put("otherDeduction", otherP);
            a.put("note", noteV);
            audit.log(u.id(), "payroll.edit", "payroll_adjustment", month + ":" + employeeId, b, a, ip);
        });
        return "Saved payroll changes for " + emp.get(0).get("full_name");
    }

    public String resetAdjustment(CurrentUser u, String monthRaw, String employeeIdRaw) {
        String month = ym(monthRaw);
        UUID employeeId = Check.uuid(employeeIdRaw);
        UUID runId = openRun(month);
        int n = repo.deleteAdjustment(runId, employeeId);
        if (n > 0) audit.log(u.id(), "payroll.edit", "payroll_adjustment", month + ":" + employeeId, null, Map.of("reset", true), null);
        return "Back to attendance figures";
    }

    // ───────────────────────── close & publish ─────────────────────────

    /** Approve & close (pay.close only): snapshots every payslip and publishes them. */
    public String approveAndClose(CurrentUser u, String monthRaw) {
        String month = ym(monthRaw);
        PayrollService.RunStatus rs = payroll.runStatus(month);
        if (!rs.attendanceLocked()) throw new UserException("Attendance for this month locks on the 1st of next month at 10:00 AM. Close after that.");
        if (rs.run() != null && "CLOSED".equals(rs.run().get("status"))) throw new UserException("Already closed.");
        UUID runId = rs.run() != null ? (UUID) rs.run().get("id") : null;
        if (runId == null) {
            runId = UUID.randomUUID();
            repo.insertOpenRun(runId, month);
        }
        repo.approveRun(u.id(), runId);
        audit.log(u.id(), "payroll.approve", "payroll_run", runId.toString(), null, Map.of("month", month), null);
        int n;
        try {
            n = closeRun(month, u.id(), true, null);
        } catch (IllegalStateException e) {
            throw new UserException(e.getMessage());
        }
        return Format.ymLabel(month) + " payroll closed — " + n + " payslips published";
    }

    /**
     * Close & publish (REQUIREMENTS §5.8): snapshots every payslip as immutable JSON + PDF. Later changes to structures,
     * regimes or attendance never change a closed payslip.
     */
    public int closeRun(String ym, UUID actorId, boolean notify, String payDateIn) {
        PayrollService.RunStatus rs = payroll.runStatus(ym);
        if (!rs.attendanceLocked()) throw new IllegalStateException("Attendance for this month isn't locked yet.");
        if (rs.run() != null && "CLOSED".equals(rs.run().get("status"))) throw new IllegalStateException("This payroll is already closed.");
        List<PayrollService.PayRow> rows = payroll.computeRun(ym, null);
        SettingsService.Company company = settings.company();
        String payDate = payDateIn != null ? payDateIn : Format.todayIn("Asia/Kolkata").toString();
        // employee IDs repeat across businesses, so each business has its own folder
        java.util.UUID business = com.jurislpo.jurisone.tenancy.TenantContext.current();
        if (business == null) throw new IllegalStateException("Payroll closes inside a business.");
        Path dir = projectDir.resolve(uploadDir).normalize().resolve("payslips").resolve(business.toString()).resolve(ym);
        record File(UUID employeeId, String pdfPath, String snapshot, long net) {}
        List<File> files = new ArrayList<>();
        Map<UUID, Map<String, Object>> emps = new LinkedHashMap<>();
        try {
            Files.createDirectories(dir);
            for (PayrollService.PayRow r : rows) {
                Map<String, Object> e = repo.payslipEmployee(r.employeeId());
                emps.put(r.employeeId(), e);
                PayrollService.Snapshot snap = payroll.toSnapshot(r, ym, e, company, payDate);
                String snapJson = json.writeValueAsString(snap);
                Path p = dir.resolve(r.code() + ".pdf");
                Files.write(p, pdf.render(json.readTree(snapJson)));
                files.add(new File(r.employeeId(), projectDir.relativize(p).toString(), snapJson, snap.net()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not write payslips: " + e.getMessage(), e);
        }
        tx.executeWithoutResult(s -> {
            Instant now = Instant.now();
            List<Map<String, Object>> existing = repo.runForClose(ym);
            UUID runId;
            if (existing.isEmpty()) {
                runId = UUID.randomUUID();
                repo.insertOpenRun(runId, ym);
                existing = repo.runForClose(ym);
            }
            Map<String, Object> run = existing.get(0);
            runId = (UUID) run.get("id");
            // atomic: only an open run can close
            int n = repo.closeOpenRun(actorId, now, payDate, runId);
            if (n == 0) throw new IllegalStateException("This payroll is already closed.");
            for (File f : files) {
                repo.insertPayslip(runId, f.employeeId(), f.snapshot(), f.pdfPath());
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("month", ym);
            after.put("payslips", files.size());
            after.put("net", files.stream().mapToLong(File::net).sum());
            audit.log(actorId, "payroll.close", "payroll_run", runId.toString(), null, after, null);
            if (notify) {
                for (File f : files) {
                    repo.insertPayslipNotification(f.employeeId(), "Your " + Format.ymLabel(ym) + " payslip is ready");
                }
            }
        });
        if (notify) {
            for (Map<String, Object> e : emps.values()) {
                String label = Format.ymLabel(ym);
                mail.send((String) e.get("email"), new MailTemplates.Content("Your " + label + " payslip is ready",
                        "Hi " + e.get("full_name") + ",\n\nYour payslip for " + label + " has been published. View or download it in JurisOne: " + appUrl + "/payslips", null));
            }
        }
        return files.size();
    }

    // ───────────────────────── salary structures ─────────────────────────

    public record Rev(String from, long basic, long hra, long lta, long special, String reason) {}

    public record StructRow(String id, String code, String name, String title, String doj, List<Rev> revs, Rev cur, long g) {}

    public Map<String, Object> structuresPage(Map<String, String> sp) {
        ListParams ls = ListParams.of(sp, List.of("name", "basic", "g", "since"), "name", 100, "asc");
        String set = ListParams.pick(sp, "set", List.of("All", "Set", "Missing"), "All");
        String dept = ListParams.str(sp, "dept", "All");
        String today = Format.todayIn("Asia/Kolkata").toString();
        boolean byDept = !"All".equals(dept) && dept.matches("^[0-9a-f-]{36}$");
        List<Map<String, Object>> emps = repo.structureEmployees(byDept ? dept : null);
        List<StructRow> all = new ArrayList<>();
        for (Map<String, Object> e : emps) {
            List<Rev> revs = repo.revisions(e.get("id"));
            Rev cur = revs.stream().filter(r -> r.from().compareTo(today) <= 0).findFirst().orElse(revs.isEmpty() ? null : revs.get(revs.size() - 1)); // current, else the upcoming one
            all.add(new StructRow(e.get("id").toString(), (String) e.get("code"), (String) e.get("full_name"), (String) e.get("designation"),
                    ((Date) e.get("date_of_joining")).toLocalDate().toString(), revs, cur, cur == null ? 0 : cur.basic() + cur.hra() + cur.lta() + cur.special()));
        }
        String q = ls.q().toLowerCase();
        List<StructRow> rows = new ArrayList<>(all.stream().filter(r -> (q.isEmpty() || (r.name() + " " + r.code() + " " + r.title()).toLowerCase().contains(q))
                && ("All".equals(set) || ("Set".equals(set)) == (r.cur() != null))).toList());
        Map<String, Function<StructRow, ? extends Comparable>> keys = Map.of("name", StructRow::name, "basic", r -> r.cur() == null ? -1L : r.cur().basic(),
                "g", StructRow::g, "since", r -> r.cur() == null ? "" : r.cur().from());
        rows.sort(ls.comparator(keys));
        List<String> lastClosed = repo.lastClosedMonth();
        String minFrom = lastClosed.isEmpty() ? "" : YearMonth.parse(lastClosed.get(0)).plusMonths(1).atDay(1).toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", all.size());
        out.put("depts", repo.activeDepartments());
        out.put("today", today);
        out.put("minFrom", minFrom);
        return out;
    }

    public record StructureInput(String employeeId, String effectiveFrom, String reason, Double basic, Double hra, Double lta, Double special) {}

    static long amount(Double v, int min, String minMsg) {
        if (v == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected number, received undefined");
        if (v != Math.rint(v)) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected int, received number");
        if (v < min) throw new UserException(HttpStatus.BAD_REQUEST, minMsg);
        if (v > 100_000_000) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=100000000");
        return v.longValue();
    }

    /** Adds a salary revision (REQUIREMENTS §5.7). History is never edited — only added to. Rupees in, paise stored. India only. */
    public String addStructure(CurrentUser u, StructureInput f, String ip) {
        UUID employeeId = Check.uuid(f.employeeId());
        String from = Check.isoDate(f.effectiveFrom(), "Pick the effective date");
        String reason = Check.oneOf(f.reason(), List.of("JOINING", "APPRAISAL", "PROMOTION", "CORRECTION"));
        long basic = amount(f.basic(), 1, "Basic is required"), hra = amount(f.hra(), 0, "Too small: expected number to be >=0"),
                lta = amount(f.lta(), 0, "Too small: expected number to be >=0"), special = amount(f.special(), 0, "Too small: expected number to be >=0");
        List<Map<String, Object>> es = repo.structureEmployee(employeeId);
        if (es.isEmpty()) throw new UserException("Employee not found.");
        Map<String, Object> e = es.get(0);
        if (!"IN".equals(e.get("region"))) throw new UserException("Salary structures are for the India payroll only.");
        if ("EXITED".equals(e.get("status"))) throw new UserException("This person is a past employee.");
        if (from.compareTo(((Date) e.get("date_of_joining")).toLocalDate().toString()) < 0) throw new UserException("The structure can't start before the joining date.");
        List<String> lastClosed = repo.lastClosedMonth();
        if (!lastClosed.isEmpty() && from.substring(0, 7).compareTo(lastClosed.get(0)) <= 0) {
            throw new UserException("Payroll is closed up to " + Format.ymLabel(lastClosed.get(0)) + ". Start the revision after that and pay any difference as arrears.");
        }
        Integer clash = repo.countStructuresEffective(employeeId, from);
        if (clash != null && clash > 0) throw new UserException("There's already a revision effective " + Format.fmtDY(LocalDate.parse(from)) + ". History can't be edited — pick another date.");
        List<Map<String, Object>> prev = repo.previousStructure(employeeId, from);
        String name = (String) e.get("full_name");
        tx.executeWithoutResult(s -> {
            UUID id = UUID.randomUUID();
            repo.insertStructure(id, employeeId, from, basic * 100, hra * 100, lta * 100, special * 100, reason, u.id());
            Map<String, Object> before = null;
            if (!prev.isEmpty()) {
                Map<String, Object> p = prev.get(0);
                before = new LinkedHashMap<>();
                before.put("effectiveFrom", ((Date) p.get("effective_from")).toLocalDate().toString());
                for (String k : List.of("basic", "hra", "lta", "special")) before.put(k, p.get(k));
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("employeeId", employeeId.toString());
            after.put("effectiveFrom", from);
            after.put("reason", reason);
            after.put("basic", basic * 100);
            after.put("hra", hra * 100);
            after.put("lta", lta * 100);
            after.put("special", special * 100);
            audit.log(u.id(), "salary.structure_change", "salary_structure", id.toString(), before, after, ip);
            // the "salary structure pending" reminder is done
            repo.markStructureRemindersRead(name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        });
        return "Salary structure saved for " + name + " · effective " + Format.fmtDY(LocalDate.parse(from));
    }

    // ───────────────────────── payslips ─────────────────────────

    /** A month appears only after Finance closes it; open months show "Processing" (REQUIREMENTS §5.11). */
    public Map<String, Object> payslipsPage(CurrentUser u) {
        boolean india = "IN".equals(u.region());
        LocalDate today = Format.todayIn("Asia/Kolkata");
        List<Map<String, Object>> slips = repo.closedPayslips(u.id());
        List<String> openRuns = repo.openRunMonths();
        Integer hasStructure = repo.countStructures(u.id());
        String prev = YearMonth.from(today).minusMonths(1).toString();
        List<String> slipMonths = slips.stream().map(s -> (String) s.get("month")).toList();
        List<String> processing = new ArrayList<>();
        if (india && hasStructure != null && hasStructure > 0) {
            List<String> cand = new ArrayList<>(openRuns);
            if (!slipMonths.contains(prev)) cand.add(prev);
            for (String mth : cand) if (!processing.contains(mth) && !slipMonths.contains(mth)) processing.add(mth);
        }
        List<Map<String, Object>> months = new ArrayList<>();
        for (String mth : processing) months.add(new LinkedHashMap<>(Map.of("ym", mth, "closed", false)));
        for (Map<String, Object> s : slips) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ym", s.get("month"));
            m.put("closed", true);
            try {
                m.put("snap", json.readTree((String) s.get("snapshot")));
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            m.put("paidOn", s.get("pay_date") == null ? null : ((Date) s.get("pay_date")).toLocalDate().toString());
            months.add(m);
        }
        months.sort((a, b) -> ((String) b.get("ym")).compareTo((String) a.get("ym")));
        return Map.of("india", india, "months", months, "code", u.code());
    }

    public record PdfFile(String filename, byte[] bytes) {}

    /** Payslip PDF of a closed month: your own (`self`), or anyone's with `pay.view`; 404 when there is none. */
    public PdfFile payslipPdf(CurrentUser u, String month, String code) {
        if (!month.matches("^\\d{4}-\\d{2}$") || !code.matches("^JC-\\d+$")) return null;
        boolean own = code.equals(u.code()) && u.can("self");
        if (!own && !u.can("pay.view")) throw new ForbiddenException();
        List<Map<String, Object>> rs = repo.closedPayslipFile(month, code);
        if (rs.isEmpty()) return null;
        byte[] bytes;
        try {
            String stored = (String) rs.get(0).get("pdf_path");
            if (stored == null) throw new IOException("no file");
            bytes = Files.readAllBytes(resolveStored(stored));
        } catch (IOException | IllegalArgumentException e) {
            try {
                bytes = pdf.render(json.readTree((String) rs.get(0).get("snapshot"))); // regenerate from the frozen snapshot
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
        return new PdfFile("Payslip-" + code + "-" + month + ".pdf", bytes);
    }

    // ───────────────────────── exports ─────────────────────────

    static final List<String> TYPES = List.of("bank", "ecr", "pt", "tds", "register");

    record Line(UUID employeeId, String code, String name, long basicEarned, long gross, long pf, long pt, long tds, long net, double lop, double paid, int days) {}

    static String rupees(long p) {
        return String.format(java.util.Locale.ROOT, "%.2f", p / 100.0);
    }

    public record Export(String filename, String body) {}

    /** Payroll exports (pay.view): bank transfer file, PF ECR, PT and TDS summaries, register; null = 400. */
    public Export export(CurrentUser u, String month, String type, String ip) {
        if (month == null || !month.matches("^\\d{4}-\\d{2}$") || type == null || !TYPES.contains(type)) return null;
        if ("tds".equals(type) && !u.can("tax.view")) throw new ForbiddenException();
        PayrollService.RunStatus rs = payroll.runStatus(month);
        boolean closed = rs.run() != null && "CLOSED".equals(rs.run().get("status"));
        List<Line> lines = new ArrayList<>();
        if (closed) {
            for (Map.Entry<UUID, JsonNode> en : payroll.closedRows(month)) {
                JsonNode s = en.getValue();
                long basic = 0;
                for (JsonNode c : s.get("components")) if ("Basic".equals(c.get("label").asText())) { basic = c.get("earned").asLong(); break; }
                JsonNode a = s.get("attendance");
                lines.add(new Line(en.getKey(), s.at("/employee/code").asText(), s.at("/employee/name").asText(), basic, s.get("grossEarned").asLong(), s.get("pf").asLong(),
                        s.get("pt").asLong(), s.get("tds").asLong(), s.get("net").asLong(), a.get("lopDays").asDouble(), a.get("paidDays").asDouble(), a.get("daysInMonth").asInt()));
            }
        } else {
            for (PayrollService.PayRow r : payroll.computeRun(month, null)) {
                var p = r.pay();
                lines.add(new Line(r.employeeId(), r.code(), r.name(), p.components().get(0).earned(), p.grossEarned(), p.pf(), p.pt(), p.tds(), p.net(), p.lopDays(), p.paidDays(), p.daysInMonth()));
            }
        }
        Map<UUID, Map<String, Object>> emps = new LinkedHashMap<>();
        for (Line l : lines) emps.put(l.employeeId(), repo.exportDetails(l.employeeId()));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("type", type);
        after.put("rows", lines.size());
        after.put("closed", closed);
        audit.log(u.id(), "payroll.export", "payroll_run", month, null, after, ip);
        String suffix = closed ? "" : "-draft";
        java.util.function.Function<String, String> dec = v -> { String d = pii.decrypt(v); return d == null ? "" : d; };
        switch (type) {
            case "bank": {
                List<List<Object>> rows = new ArrayList<>();
                for (Line l : lines) {
                    if (l.net() <= 0) continue;
                    Map<String, Object> e = emps.get(l.employeeId());
                    rows.add(Arrays.asList(l.code(), l.name(), dec.apply((String) e.get("bank_account_enc")), nz(e.get("bank_ifsc")), nz(e.get("bank_name")), rupees(l.net()), "Salary " + month));
                }
                return new Export("bank-transfer-" + month + suffix + ".csv", Csv.toCsv(List.of("Employee ID", "Beneficiary name", "Account number", "IFSC", "Bank", "Amount (INR)", "Narration"), rows));
            }
            case "ecr": {
                // EPF ECR: wages capped at ₹15,000; employer EPS 8.33% of EPS wages, the rest of 12% to EPF.
                long cap = 1_500_000;
                List<List<Object>> rows = new ArrayList<>();
                for (Line l : lines) {
                    long w = Math.min(l.basicEarned(), cap);
                    long eps = Math.round(w * 0.0833);
                    rows.add(Arrays.asList(dec.apply((String) emps.get(l.employeeId()).get("uan_enc")), l.name(), Math.round(l.gross() / 100.0), Math.round(w / 100.0),
                            Math.round(w / 100.0), Math.round(w / 100.0), Math.round(l.pf() / 100.0), Math.round(eps / 100.0), Math.round((l.pf() - eps) / 100.0), l.lop(), 0));
                }
                return new Export("pf-ecr-" + month + suffix + ".csv", Csv.toCsv(List.of("UAN", "Member name", "Gross wages", "EPF wages", "EPS wages", "EDLI wages",
                        "EPF contribution (EE)", "EPS contribution (ER)", "EPF-EPS difference (ER)", "NCP days", "Refund of advances"), rows));
            }
            case "pt": {
                List<List<Object>> rows = new ArrayList<>();
                for (Line l : lines) rows.add(Arrays.asList(l.code(), l.name(), rupees(l.gross()), rupees(l.pt())));
                return new Export("professional-tax-" + month + suffix + ".csv", Csv.toCsv(List.of("Employee ID", "Name", "Gross earned", "Professional tax"), rows));
            }
            case "tds": {
                List<List<Object>> rows = new ArrayList<>();
                for (Line l : lines) rows.add(Arrays.asList(l.code(), l.name(), dec.apply((String) emps.get(l.employeeId()).get("pan_enc")), rupees(l.gross()), rupees(l.tds())));
                return new Export("tds-" + month + suffix + ".csv", Csv.toCsv(List.of("Employee ID", "Name", "PAN", "Gross earned", "TDS deducted"), rows));
            }
            default: {
                List<List<Object>> rows = new ArrayList<>();
                for (Line l : lines) {
                    rows.add(Arrays.asList(l.code(), l.name(), l.days(), l.paid(), l.lop(), rupees(l.gross()), rupees(l.pf()), rupees(l.pt()), rupees(l.tds()), rupees(l.net())));
                }
                return new Export("payroll-register-" + month + suffix + ".csv", Csv.toCsv(List.of("Employee ID", "Name", "Days in month", "Paid days", "LOP days", "Gross earned",
                        "PF", "PT", "TDS", "Net pay"), rows));
            }
        }
    }

    static String nz(Object o) {
        return o == null ? "" : o.toString();
    }
}
