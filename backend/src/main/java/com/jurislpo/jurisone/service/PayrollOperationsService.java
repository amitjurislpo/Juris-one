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
import com.jurislpo.jurisone.repository.PayrollOperationsRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.tenancy.TenantContext;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Payroll actions, close & publish, salary structures, payslips and payroll exports. */
@Service
public class PayrollOperationsService {

    private final PayrollOperationsRepository repo;
    private final TransactionTemplate transactionTemplate;
    private final PayrollService payroll;
    private final PayslipPdf payslipPdf;
    private final SettingsService settings;
    private final AuditService audit;
    private final MailService mail;
    private final PiiCrypto piiCrypto;
    private final ObjectMapper json;
    private final Path storageDir;
    private final BusinessUrls businessUrls;

    public PayrollOperationsService(PayrollOperationsRepository repo, TransactionTemplate transactionTemplate, PayrollService payroll, PayslipPdf payslipPdf, SettingsService settings, AuditService audit,
                             MailService mail, PiiCrypto piiCrypto, ObjectMapper json, JurisOneProperties props, BusinessUrls businessUrls) {
        this.repo = repo;
        this.transactionTemplate = transactionTemplate;
        this.payroll = payroll;
        this.payslipPdf = payslipPdf;
        this.settings = settings;
        this.audit = audit;
        this.mail = mail;
        this.piiCrypto = piiCrypto;
        this.json = json;
        this.storageDir = Path.of(props.storageDir()).toAbsolutePath().normalize();
        this.businessUrls = businessUrls;
    }

    static String requireMonth(String value) {
        if (value == null || !value.matches("^\\d{4}-\\d{2}$")) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid string: must match pattern /^\\d{4}-\\d{2}$/");
        return value;
    }

    /** A DB-stored relative path (may use Windows separators) → absolute file under the project directory. */
    public Path resolveStored(String stored) {
        Path path = storageDir.resolve(stored.replace('\\', '/')).normalize();
        if (!path.startsWith(storageDir)) throw new IllegalArgumentException("bad path");
        return path;
    }

    // ───────────────────────── page ─────────────────────────

    public Map<String, Object> page(CurrentUser user, Map<String, String> queryParams) {
        LocalDate today = Format.todayIn("Asia/Kolkata");
        PayrollService.Months months = payroll.payrollMonths(today);
        String requestedMonth = ListParams.text(queryParams, "month", months.defaultMonth());
        String month = months.months().contains(requestedMonth) ? requestedMonth : months.defaultMonth();
        PayrollService.View view = payroll.payrollView(month);
        LocalDate lastDay = YearMonth.parse(month).atEndOfMonth();
        List<String> missing = view.closed() ? List.of() : repo.namesWithoutStructure(lastDay);
        Map<String, Object> run = view.run();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("months", months.months());
        out.put("closed", view.closed());
        out.put("attendanceLocked", view.attendanceLocked());
        out.put("approved", (run != null && run.get("approved_at") != null) || view.closed());
        out.put("closedOn", run != null && run.get("closed_at") != null ? Format.isoInZone(((Timestamp) run.get("closed_at")).toInstant(), user.timeZone()).toString() : null);
        out.put("missing", missing);
        out.put("rows", view.rows());
        out.put("view", PayrollService.filter(view.rows(), queryParams));
        out.put("canClose", user.can("pay.close"));
        out.put("canStructures", user.can("struct.manage"));
        return out;
    }

    // ───────────────────────── adjustments ─────────────────────────

    private UUID openRun(String month) {
        PayrollService.RunStatus runStatus = payroll.runStatus(month);
        if (runStatus.run() != null && "CLOSED".equals(runStatus.run().get("status"))) {
            throw new UserException(Format.monthLabel(month) + " payroll is closed. Corrections go into the next month as arrears.");
        }
        if (runStatus.run() != null) return (UUID) runStatus.run().get("id");
        UUID id = UUID.randomUUID();
        repo.insertOpenRun(id, month);
        return id;
    }

    public record AdjustmentInput(String month, String employeeId, Double lopDays, Double bonus, Double otherDeduction, String note) {}

    static long wholeRupees(Double value) {
        if (value == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected number, received undefined");
        if (value != Math.rint(value)) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected int, received number");
        if (value < 0) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected number to be >=0");
        if (value > 100_000_000) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=100000000");
        return value.longValue();
    }

    /** Finance edits LOP / bonus / other deduction / note while the run is open (each edit audited). */
    public String saveAdjustment(CurrentUser user, AdjustmentInput input, String ipAddress) {
        String month = requireMonth(input.month());
        UUID employeeId = Check.uuid(input.employeeId());
        if (input.lopDays() != null) {
            if (input.lopDays() < 0) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected number to be >=0");
            if (input.lopDays() > 31) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=31");
            if (input.lopDays() * 2 != Math.rint(input.lopDays() * 2)) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid number: must be a multiple of 0.5");
        }
        long bonus = wholeRupees(input.bonus()), other = wholeRupees(input.otherDeduction());
        String note = Check.requireString(input.note()).trim();
        if (note.length() > 200) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected string to have <=200 characters");
        UUID runId = openRun(month);
        List<Map<String, Object>> employee = repo.employeeRegion(employeeId);
        if (employee.isEmpty() || !"IN".equals(employee.get(0).get("region"))) throw new UserException("Only India payroll employees can be edited.");
        List<Map<String, Object>> before = repo.findAdjustment(runId, employeeId);
        Long bonusP = bonus * 100, otherP = other * 100;
        String noteV = note.isEmpty() ? null : note;
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            repo.upsertAdjustment(runId, employeeId, input.lopDays() == null ? null : BigDecimal.valueOf(input.lopDays()), bonusP, otherP, noteV, user.id());
            repo.resetRunToReview(runId);
            Map<String, Object> previousValues = null;
            if (!before.isEmpty()) {
                Map<String, Object> row = before.get(0);
                previousValues = new LinkedHashMap<>();
                previousValues.put("lopDays", row.get("lop_days") == null ? null : ((BigDecimal) row.get("lop_days")).doubleValue());
                previousValues.put("bonus", row.get("bonus"));
                previousValues.put("otherDeduction", row.get("other_deduction"));
                previousValues.put("note", row.get("note"));
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("lopDays", input.lopDays());
            view.put("bonus", bonusP);
            view.put("otherDeduction", otherP);
            view.put("note", noteV);
            audit.log(user.id(), "payroll.edit", "payroll_adjustment", month + ":" + employeeId, previousValues, view, ipAddress);
        });
        return "Saved payroll changes for " + employee.get(0).get("full_name");
    }

    public String resetAdjustment(CurrentUser user, String monthRaw, String employeeIdRaw) {
        String month = requireMonth(monthRaw);
        UUID employeeId = Check.uuid(employeeIdRaw);
        UUID runId = openRun(month);
        int count = repo.deleteAdjustment(runId, employeeId);
        if (count > 0) audit.log(user.id(), "payroll.edit", "payroll_adjustment", month + ":" + employeeId, null, Map.of("reset", true), null);
        return "Back to attendance figures";
    }

    // ───────────────────────── close & publish ─────────────────────────

    /** Approve & close (pay.close only): snapshots every payslip and publishes them. */
    public String approveAndClose(CurrentUser user, String monthRaw) {
        String month = requireMonth(monthRaw);
        PayrollService.RunStatus runStatus = payroll.runStatus(month);
        if (!runStatus.attendanceLocked()) throw new UserException("Attendance for this month locks on the 1st of next month at 10:00 AM. Close after that.");
        if (runStatus.run() != null && "CLOSED".equals(runStatus.run().get("status"))) throw new UserException("Already closed.");
        UUID runId = runStatus.run() != null ? (UUID) runStatus.run().get("id") : null;
        if (runId == null) {
            runId = UUID.randomUUID();
            repo.insertOpenRun(runId, month);
        }
        repo.approveRun(user.id(), runId);
        audit.log(user.id(), "payroll.approve", "payroll_run", runId.toString(), null, Map.of("month", month), null);
        int count;
        try {
            count = closeRun(month, user.id(), true, null);
        } catch (IllegalStateException e) {
            throw new UserException(e.getMessage());
        }
        return Format.monthLabel(month) + " payroll closed — " + count + " payslips published";
    }

    /**
     * Close & publish (REQUIREMENTS §5.8): snapshots every payslip as immutable JSON + PDF. Later changes to structures,
     * regimes or attendance never change a closed payslip.
     */
    public int closeRun(String yearMonth, UUID actorId, boolean notify, String payDateIn) {
        PayrollService.RunStatus runStatus = payroll.runStatus(yearMonth);
        if (!runStatus.attendanceLocked()) throw new IllegalStateException("Attendance for this month isn't locked yet.");
        if (runStatus.run() != null && "CLOSED".equals(runStatus.run().get("status"))) throw new IllegalStateException("This payroll is already closed.");
        List<PayrollService.PayRow> rows = payroll.computeRun(yearMonth, null);
        SettingsService.Company company = settings.company();
        String payDate = payDateIn != null ? payDateIn : Format.todayIn("Asia/Kolkata").toString();
        // employee IDs repeat across businesses, so each business has its own folder
        java.util.UUID business = com.jurislpo.jurisone.tenancy.TenantContext.current();
        if (business == null) throw new IllegalStateException("Payroll closes inside a business.");
        Path directory = storageDir.resolve("payslips").resolve(business.toString()).resolve(yearMonth);
        record File(UUID employeeId, String pdfPath, String snapshot, long net) {}
        List<File> files = new ArrayList<>();
        Map<UUID, Map<String, Object>> employees = new LinkedHashMap<>();
        try {
            Files.createDirectories(directory);
            for (PayrollService.PayRow payRow : rows) {
                Map<String, Object> row = repo.payslipEmployee(payRow.employeeId());
                employees.put(payRow.employeeId(), row);
                PayrollService.Snapshot snapshot = payroll.toSnapshot(payRow, yearMonth, row, company, payDate);
                String snapJson = json.writeValueAsString(snapshot);
                Path path = directory.resolve(payRow.code() + ".pdf");
                Files.write(path, payslipPdf.render(json.readTree(snapJson)));
                files.add(new File(payRow.employeeId(), storageDir.relativize(path).toString(), snapJson, snapshot.net()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not write payslips: " + e.getMessage(), e);
        }
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            Instant now = Instant.now();
            List<Map<String, Object>> existing = repo.runForClose(yearMonth);
            UUID runId;
            if (existing.isEmpty()) {
                runId = UUID.randomUUID();
                repo.insertOpenRun(runId, yearMonth);
                existing = repo.runForClose(yearMonth);
            }
            Map<String, Object> run = existing.get(0);
            runId = (UUID) run.get("id");
            // atomic: only an open run can close
            int count = repo.closeOpenRun(actorId, now, payDate, runId);
            if (count == 0) throw new IllegalStateException("This payroll is already closed.");
            for (File file : files) {
                repo.insertPayslip(runId, file.employeeId(), file.snapshot(), file.pdfPath());
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("month", yearMonth);
            after.put("payslips", files.size());
            after.put("net", files.stream().mapToLong(File::net).sum());
            audit.log(actorId, "payroll.close", "payroll_run", runId.toString(), null, after, null);
            if (notify) {
                for (File file : files) {
                    repo.insertPayslipNotification(file.employeeId(), "Your " + Format.monthLabel(yearMonth) + " payslip is ready");
                }
            }
        });
        if (notify) {
            String payslipsUrl = businessUrls.forBusiness(TenantContext.current(), "/payslips");
            for (Map<String, Object> employee : employees.values()) {
                String label = Format.monthLabel(yearMonth);
                mail.send((String) employee.get("email"), new MailTemplates.Content("Your " + label + " payslip is ready",
                        "Hi " + employee.get("full_name") + ",\n\nYour payslip for " + label + " has been published. View or download it in JurisOne: " + payslipsUrl, null));
            }
        }
        return files.size();
    }

    // ───────────────────────── salary structures ─────────────────────────

    public record SalaryRevision(String from, long basic, long hra, long lta, long special, String reason) {}

    public record StructureRow(String id, String code, String name, String title, String dateOfJoining, List<SalaryRevision> revisions, SalaryRevision current, long gross) {}

    public Map<String, Object> structuresPage(Map<String, String> queryParams) {
        ListParams listParams = ListParams.of(queryParams, List.of("name", "basic", "gross", "since"), "name", 100, "asc");
        String set = ListParams.pick(queryParams, "structureStatus", List.of("All", "Set", "Missing"), "All");
        String department = ListParams.text(queryParams, "departmentId", "All");
        String today = Format.todayIn("Asia/Kolkata").toString();
        boolean byDepartment = !"All".equals(department) && department.matches("^[0-9a-f-]{36}$");
        List<Map<String, Object>> employees = repo.structureEmployees(byDepartment ? department : null);
        List<StructureRow> all = new ArrayList<>();
        for (Map<String, Object> employee : employees) {
            List<SalaryRevision> revisions = repo.revisions(employee.get("id"));
            SalaryRevision currentRevision = revisions.stream().filter(r -> r.from().compareTo(today) <= 0).findFirst().orElse(revisions.isEmpty() ? null : revisions.get(revisions.size() - 1)); // current, else the upcoming one
            all.add(new StructureRow(employee.get("id").toString(), (String) employee.get("code"), (String) employee.get("full_name"), (String) employee.get("designation"),
                    ((Date) employee.get("date_of_joining")).toLocalDate().toString(), revisions, currentRevision, currentRevision == null ? 0 : currentRevision.basic() + currentRevision.hra() + currentRevision.lta() + currentRevision.special()));
        }
        String search = listParams.search().toLowerCase();
        List<StructureRow> rows = new ArrayList<>(all.stream().filter(row -> (search.isEmpty() || (row.name() + " " + row.code() + " " + row.title()).toLowerCase().contains(search))
                && ("All".equals(set) || ("Set".equals(set)) == (row.current() != null))).toList());
        Map<String, Function<StructureRow, ? extends Comparable>> keys = Map.of("name", StructureRow::name, "basic", r -> r.current() == null ? -1L : r.current().basic(),
                "gross", StructureRow::gross, "since", r -> r.current() == null ? "" : r.current().from());
        rows.sort(listParams.comparator(keys));
        List<String> lastClosed = repo.lastClosedMonth();
        String minFrom = lastClosed.isEmpty() ? "" : YearMonth.parse(lastClosed.get(0)).plusMonths(1).atDay(1).toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", all.size());
        out.put("departments", repo.activeDepartments());
        out.put("today", today);
        out.put("minFrom", minFrom);
        return out;
    }

    public record StructureInput(String employeeId, String effectiveFrom, String reason, Double basic, Double hra, Double lta, Double special) {}

    static long amount(Double value, int min, String minMsg) {
        if (value == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected number, received undefined");
        if (value != Math.rint(value)) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected int, received number");
        if (value < min) throw new UserException(HttpStatus.BAD_REQUEST, minMsg);
        if (value > 100_000_000) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected number to be <=100000000");
        return value.longValue();
    }

    /** Adds a salary revision (REQUIREMENTS §5.7). History is never edited — only added to. Rupees in, paise stored. India only. */
    public String addStructure(CurrentUser user, StructureInput input, String ipAddress) {
        UUID employeeId = Check.uuid(input.employeeId());
        String from = Check.isoDate(input.effectiveFrom(), "Pick the effective date");
        String reason = Check.oneOf(input.reason(), List.of("JOINING", "APPRAISAL", "PROMOTION", "CORRECTION"));
        long basic = amount(input.basic(), 1, "Basic is required"), hra = amount(input.hra(), 0, "Too small: expected number to be >=0"),
                lta = amount(input.lta(), 0, "Too small: expected number to be >=0"), special = amount(input.special(), 0, "Too small: expected number to be >=0");
        List<Map<String, Object>> employees = repo.structureEmployee(employeeId);
        if (employees.isEmpty()) throw new UserException("Employee not found.");
        Map<String, Object> employee = employees.get(0);
        if (!"IN".equals(employee.get("region"))) throw new UserException("Salary structures are for the India payroll only.");
        if ("EXITED".equals(employee.get("status"))) throw new UserException("This person is a past employee.");
        if (from.compareTo(((Date) employee.get("date_of_joining")).toLocalDate().toString()) < 0) throw new UserException("The structure can't start before the joining date.");
        List<String> lastClosed = repo.lastClosedMonth();
        if (!lastClosed.isEmpty() && from.substring(0, 7).compareTo(lastClosed.get(0)) <= 0) {
            throw new UserException("Payroll is closed up to " + Format.monthLabel(lastClosed.get(0)) + ". Start the revision after that and pay any difference as arrears.");
        }
        Integer clash = repo.countStructuresEffective(employeeId, from);
        if (clash != null && clash > 0) throw new UserException("There's already a revision effective " + Format.formatDayMonthYear(LocalDate.parse(from)) + ". History can't be edited — pick another date.");
        List<Map<String, Object>> previous = repo.previousStructure(employeeId, from);
        String name = (String) employee.get("full_name");
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            UUID id = UUID.randomUUID();
            repo.insertStructure(id, employeeId, from, basic * 100, hra * 100, lta * 100, special * 100, reason, user.id());
            Map<String, Object> before = null;
            if (!previous.isEmpty()) {
                Map<String, Object> row = previous.get(0);
                before = new LinkedHashMap<>();
                before.put("effectiveFrom", ((Date) row.get("effective_from")).toLocalDate().toString());
                for (String column : List.of("basic", "hra", "lta", "special")) before.put(column, row.get(column));
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("employeeId", employeeId.toString());
            after.put("effectiveFrom", from);
            after.put("reason", reason);
            after.put("basic", basic * 100);
            after.put("hra", hra * 100);
            after.put("lta", lta * 100);
            after.put("special", special * 100);
            audit.log(user.id(), "salary.structure_change", "salary_structure", id.toString(), before, after, ipAddress);
            // the "salary structure pending" reminder is done
            repo.markStructureRemindersRead(name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        });
        return "Salary structure saved for " + name + " · effective " + Format.formatDayMonthYear(LocalDate.parse(from));
    }

    // ───────────────────────── payslips ─────────────────────────

    /** A month appears only after Finance closes it; open months show "Processing" (REQUIREMENTS §5.11). */
    public Map<String, Object> payslipsPage(CurrentUser user) {
        boolean india = "IN".equals(user.region());
        LocalDate today = Format.todayIn("Asia/Kolkata");
        List<Map<String, Object>> slips = repo.closedPayslips(user.id());
        List<String> openRuns = repo.openRunMonths();
        Integer hasStructure = repo.countStructures(user.id());
        String previous = YearMonth.from(today).minusMonths(1).toString();
        List<String> slipMonths = slips.stream().map(s -> (String) s.get("month")).toList();
        List<String> processing = new ArrayList<>();
        if (india && hasStructure != null && hasStructure > 0) {
            List<String> cand = new ArrayList<>(openRuns);
            if (!slipMonths.contains(previous)) cand.add(previous);
            for (String month : cand) if (!processing.contains(month) && !slipMonths.contains(month)) processing.add(month);
        }
        List<Map<String, Object>> months = new ArrayList<>();
        for (String month : processing) months.add(new LinkedHashMap<>(Map.of("month", month, "closed", false)));
        for (Map<String, Object> slip : slips) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("month", slip.get("month"));
            view.put("closed", true);
            try {
                view.put("snapshot", PayrollService.currentSnapshotFormat(json.readTree((String) slip.get("snapshot"))));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            view.put("paidOn", slip.get("pay_date") == null ? null : ((Date) slip.get("pay_date")).toLocalDate().toString());
            months.add(view);
        }
        months.sort((a, b) -> ((String) b.get("month")).compareTo((String) a.get("month")));
        return Map.of("india", india, "months", months, "code", user.code());
    }

    public record PdfFile(String filename, byte[] bytes) {}

    /** Payslip PDF of a closed month: your own (`self`), or anyone's with `pay.view`; 404 when there is none. */
    public PdfFile payslipPdf(CurrentUser user, String month, String code) {
        if (!month.matches("^\\d{4}-\\d{2}$") || !code.matches("^JC-\\d+$")) return null;
        boolean own = code.equals(user.code()) && user.can("self");
        if (!own && !user.can("pay.view")) throw new ForbiddenException();
        List<Map<String, Object>> rows = repo.closedPayslipFile(month, code);
        if (rows.isEmpty()) return null;
        byte[] bytes;
        try {
            String stored = (String) rows.get(0).get("pdf_path");
            if (stored == null) throw new IOException("no file");
            bytes = Files.readAllBytes(resolveStored(stored));
        } catch (IOException | IllegalArgumentException e) {
            try {
                bytes = payslipPdf.render(json.readTree((String) rows.get(0).get("snapshot"))); // regenerate from the frozen snapshot
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
        return new PdfFile("Payslip-" + code + "-" + month + ".pdf", bytes);
    }

    // ───────────────────────── exports ─────────────────────────

    static final List<String> TYPES = List.of("bank", "ecr", "pt", "tds", "register");

    record PayrollLine(UUID employeeId, String code, String name, long basicEarned, long gross, long pf, long pt, long tds, long net, double lop, double paid, int days) {}

    static String rupees(long paise) {
        return String.format(java.util.Locale.ROOT, "%.2f", paise / 100.0);
    }

    public record Export(String filename, String body) {}

    /** Payroll exports (pay.view): bank transfer file, PF ECR, PT and TDS summaries, register; null = 400. */
    public Export export(CurrentUser user, String month, String type, String ipAddress) {
        if (month == null || !month.matches("^\\d{4}-\\d{2}$") || type == null || !TYPES.contains(type)) return null;
        if ("tds".equals(type) && !user.can("tax.view")) throw new ForbiddenException();
        PayrollService.RunStatus runStatus = payroll.runStatus(month);
        boolean closed = runStatus.run() != null && "CLOSED".equals(runStatus.run().get("status"));
        List<PayrollLine> lines = new ArrayList<>();
        if (closed) {
            for (Map.Entry<UUID, JsonNode> entry : payroll.closedRows(month)) {
                JsonNode snapshot = entry.getValue();
                long basic = 0;
                for (JsonNode component : snapshot.get("components")) if ("Basic".equals(component.get("label").asText())) { basic = component.get("earned").asLong(); break; }
                JsonNode node = snapshot.get("attendance");
                lines.add(new PayrollLine(entry.getKey(), snapshot.at("/employee/code").asText(), snapshot.at("/employee/name").asText(), basic, snapshot.get("grossEarned").asLong(), snapshot.get("pf").asLong(),
                        snapshot.get("pt").asLong(), snapshot.get("tds").asLong(), snapshot.get("net").asLong(), node.get("lopDays").asDouble(), node.get("paidDays").asDouble(), node.get("daysInMonth").asInt()));
            }
        } else {
            for (PayrollService.PayRow payRow : payroll.computeRun(month, null)) {
                var pay = payRow.pay();
                lines.add(new PayrollLine(payRow.employeeId(), payRow.code(), payRow.name(), pay.components().get(0).earned(), pay.grossEarned(), pay.pf(), pay.pt(), pay.tds(), pay.net(), pay.lopDays(), pay.paidDays(), pay.daysInMonth()));
            }
        }
        Map<UUID, Map<String, Object>> employees = new LinkedHashMap<>();
        for (PayrollLine line : lines) employees.put(line.employeeId(), repo.exportDetails(line.employeeId()));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("type", type);
        after.put("rows", lines.size());
        after.put("closed", closed);
        audit.log(user.id(), "payroll.export", "payroll_run", month, null, after, ipAddress);
        String suffix = closed ? "" : "-draft";
        java.util.function.Function<String, String> decryptOrBlank = v -> { String decrypted = piiCrypto.decrypt(v); return decrypted == null ? "" : decrypted; };
        switch (type) {
            case "bank": {
                List<List<Object>> rows = new ArrayList<>();
                for (PayrollLine payrollLine : lines) {
                    if (payrollLine.net() <= 0) continue;
                    Map<String, Object> employee = employees.get(payrollLine.employeeId());
                    rows.add(Arrays.asList(payrollLine.code(), payrollLine.name(), decryptOrBlank.apply((String) employee.get("bank_account_enc")), emptyIfNull(employee.get("bank_ifsc")), emptyIfNull(employee.get("bank_name")), rupees(payrollLine.net()), "Salary " + month));
                }
                return new Export("bank-transfer-" + month + suffix + ".csv", Csv.toCsv(List.of("Employee ID", "Beneficiary name", "Account number", "IFSC", "Bank", "Amount (INR)", "Narration"), rows));
            }
            case "ecr": {
                // EPF ECR: wages capped at ₹15,000; employer EPS 8.33% of EPS wages, the rest of 12% to EPF.
                long cap = 1_500_000;
                List<List<Object>> rows = new ArrayList<>();
                for (PayrollLine payrollLine : lines) {
                    long wage = Math.min(payrollLine.basicEarned(), cap);
                    long eps = Math.round(wage * 0.0833);
                    rows.add(Arrays.asList(decryptOrBlank.apply((String) employees.get(payrollLine.employeeId()).get("uan_enc")), payrollLine.name(), Math.round(payrollLine.gross() / 100.0), Math.round(wage / 100.0),
                            Math.round(wage / 100.0), Math.round(wage / 100.0), Math.round(payrollLine.pf() / 100.0), Math.round(eps / 100.0), Math.round((payrollLine.pf() - eps) / 100.0), payrollLine.lop(), 0));
                }
                return new Export("pf-ecr-" + month + suffix + ".csv", Csv.toCsv(List.of("UAN", "Member name", "Gross wages", "EPF wages", "EPS wages", "EDLI wages",
                        "EPF contribution (EE)", "EPS contribution (ER)", "EPF-EPS difference (ER)", "NCP days", "Refund of advances"), rows));
            }
            case "pt": {
                List<List<Object>> rows = new ArrayList<>();
                for (PayrollLine line : lines) rows.add(Arrays.asList(line.code(), line.name(), rupees(line.gross()), rupees(line.pt())));
                return new Export("professional-tax-" + month + suffix + ".csv", Csv.toCsv(List.of("Employee ID", "Name", "Gross earned", "Professional tax"), rows));
            }
            case "tds": {
                List<List<Object>> rows = new ArrayList<>();
                for (PayrollLine line : lines) rows.add(Arrays.asList(line.code(), line.name(), decryptOrBlank.apply((String) employees.get(line.employeeId()).get("pan_enc")), rupees(line.gross()), rupees(line.tds())));
                return new Export("tds-" + month + suffix + ".csv", Csv.toCsv(List.of("Employee ID", "Name", "PAN", "Gross earned", "TDS deducted"), rows));
            }
            default: {
                List<List<Object>> rows = new ArrayList<>();
                for (PayrollLine line : lines) {
                    rows.add(Arrays.asList(line.code(), line.name(), line.days(), line.paid(), line.lop(), rupees(line.gross()), rupees(line.pf()), rupees(line.pt()), rupees(line.tds()), rupees(line.net())));
                }
                return new Export("payroll-register-" + month + suffix + ".csv", Csv.toCsv(List.of("Employee ID", "Name", "Days in month", "Paid days", "LOP days", "Gross earned",
                        "PF", "PT", "TDS", "Net pay"), rows));
            }
        }
    }

    static String emptyIfNull(Object value) {
        return value == null ? "" : value.toString();
    }
}
