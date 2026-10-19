package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.crypto.Masks;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.engine.Csv;
import com.jurislpo.jurisone.engine.Form16Engine;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.TaxEngine;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.BusinessRepository;
import com.jurislpo.jurisone.repository.Form16Repository;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Form 16 (India): Finance publishes it per financial year once all 12 payroll months are closed; each published Form 16
 * is an immutable snapshot (JSON with its SHA-256, and the PDF). Employees read their own; Finance (tax.view) reads all;
 * HR never. Also the working file of a quarter's Form 24Q data.
 */
@Service
@Transactional
public class Form16Service {

    static final java.util.regex.Pattern TAN = java.util.regex.Pattern.compile("^[A-Z]{4}[0-9]{5}[A-Z]$"), PAN = java.util.regex.Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    // ── the snapshot (stored as JSON text) ──
    public record Employer(String name, String address, String gstin, String tan, String pan) {}

    public record EmployeeInfo(String code, String name, String designation, String department, String doj, String exitDate, String panLast4, String panEnc) {}

    public record Snapshot(int version, String fy, String assessmentYear, String generatedOn, Employer employer, EmployeeInfo employee, Form16Engine.Result computed) {}

    private final Form16Repository repo;
    private final BusinessRepository business;
    private final TaxService tax;
    private final SettingsService settings;
    private final PiiCrypto pii;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ObjectMapper json;

    public Form16Service(Form16Repository repo, BusinessRepository business, TaxService tax, SettingsService settings, PiiCrypto pii,
                         NotificationService notifications, AuditService audit, ObjectMapper json) {
        this.repo = repo;
        this.business = business;
        this.tax = tax;
        this.settings = settings;
        this.pii = pii;
        this.notifications = notifications;
        this.audit = audit;
        this.json = json;
    }

    /** Finance: tax.view and pay.close together (generate, publish, 24Q, deductor numbers). */
    public static void requireFinance(CurrentUser u) {
        if (!u.can("tax.view") || !u.can("pay.close")) throw new ForbiddenException();
    }

    static String mon(String ym) {
        return Format.MON3.get(Integer.parseInt(ym.substring(5, 7)) - 1) + " " + ym.substring(0, 4);
    }

    static String iso(Object ts) {
        return ts == null ? null : Format.jsIso(((Timestamp) ts).toInstant());
    }

    // ───────────────────────── Finance: overview ─────────────────────────

    public Map<String, Object> overview(CurrentUser u, String fyParam) {
        String fy = fyParam == null || fyParam.isBlank() ? settings.tax().fy() : fyParam;
        List<String> months = Form16Engine.fyMonths(fy);
        Set<String> closed = Set.copyOf(repo.closedMonths(months.get(0), months.get(11)));
        List<String> missing = months.stream().filter(m -> !closed.contains(m)).toList();
        TreeSet<String> fys = new TreeSet<>(java.util.Comparator.reverseOrder());
        fys.add(settings.tax().fy());
        fys.add(fy);
        fys.addAll(repo.publishedYears());
        for (String m : repo.runMonths()) fys.add(TaxEngine.fyOf(m).fy());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : repo.overviewRows(fy, months.get(0), months.get(11))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("employeeId", r.get("employee_id").toString());
            m.put("code", r.get("code"));
            m.put("name", r.get("full_name"));
            m.put("payslips", ((Number) r.get("slips")).intValue());
            m.put("formId", r.get("form_id") == null ? null : r.get("form_id").toString());
            m.put("publishedAt", iso(r.get("published_at")));
            m.put("sha256", r.get("snapshot_sha256"));
            rows.add(m);
        }
        List<Map<String, Object>> monthViews = new ArrayList<>();
        for (String ym : months) monthViews.add(Map.of("ym", ym, "label", mon(ym), "closed", closed.contains(ym)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fy", fy);
        out.put("fys", new ArrayList<>(fys));
        out.put("months", monthViews);
        out.put("missing", missing.stream().map(Form16Service::mon).toList());
        out.put("ready", missing.isEmpty());
        out.put("canPublish", u.can("pay.close"));
        out.put("deductor", deductor());
        out.put("rows", rows);
        return out;
    }

    // ───────────────────────── deductor numbers ─────────────────────────

    /** Name, address, GSTIN from the business profile; TAN and PAN from the optional business settings. */
    public Map<String, Object> deductor() {
        Map<String, Object> biz = business.findProfile(TenantContext.current());
        SettingsService.Company c = settings.company();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", biz.get("legal_name") != null ? biz.get("legal_name") : biz.get("name"));
        m.put("address", biz.get("address"));
        m.put("gstin", biz.get("gstin"));
        m.put("tan", c.tan());
        m.put("pan", c.pan());
        return m;
    }

    public record DeductorInput(String tan, String pan) {}

    public void saveDeductor(CurrentUser u, DeductorInput in, String ip) {
        requireFinance(u);
        String tan = in == null || in.tan() == null ? "" : in.tan().strip().toUpperCase(java.util.Locale.ROOT);
        String pan = in == null || in.pan() == null ? "" : in.pan().strip().toUpperCase(java.util.Locale.ROOT);
        if (!tan.isEmpty() && !TAN.matcher(tan).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "TAN looks like ABCD12345E (4 letters, 5 digits, 1 letter).");
        if (!pan.isEmpty() && !PAN.matcher(pan).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "PAN looks like ABCDE1234F (5 letters, 4 digits, 1 letter).");
        ObjectNode before = settings.get("company");
        ObjectNode value = before.deepCopy();
        if (tan.isEmpty()) value.remove("tan"); else value.put("tan", tan);
        if (pan.isEmpty()) value.remove("pan"); else value.put("pan", pan);
        settings.save("company", value);
        audit.log(u.id(), "form16.deductor_change", "setting", "company", Map.of("tan", String.valueOf(before.path("tan").asText("")), "pan", String.valueOf(before.path("pan").asText(""))),
                Map.of("tan", tan, "pan", pan), ip);
    }

    // ───────────────────────── publish ─────────────────────────

    public record Publication(String fy, int published, int alreadyPublished) {}

    /**
     * Generates and publishes Form 16 for every employee with payslips in the year who doesn't have one yet. Published
     * forms are never replaced. All 12 months must be closed.
     */
    public Publication publish(CurrentUser u, String fyParam, String ip) {
        requireFinance(u);
        String fy = fyParam;
        List<String> months = Form16Engine.fyMonths(fy);
        Set<String> closed = Set.copyOf(repo.closedMonths(months.get(0), months.get(11)));
        List<String> missing = months.stream().filter(m -> !closed.contains(m)).map(Form16Service::mon).toList();
        if (!missing.isEmpty()) {
            throw new UserException(HttpStatus.UNPROCESSABLE_ENTITY, "Form 16 for FY " + fy + " needs all 12 months of payroll closed. Still open or missing: "
                    + String.join(", ", missing) + ".");
        }
        // payslips by employee, oldest month first
        Map<UUID, List<JsonNode>> byEmployee = new LinkedHashMap<>();
        for (Map<String, Object> r : repo.closedPayslips(months.get(0), months.get(11))) {
            try {
                byEmployee.computeIfAbsent((UUID) r.get("employee_id"), k -> new ArrayList<>()).add(((ObjectNode) json.readTree((String) r.get("snapshot"))));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        if (byEmployee.isEmpty()) throw new UserException("There are no payslips in FY " + fy + " to build Form 16 from.");

        Set<UUID> have = repo.employeesWithForm(fy);
        Map<UUID, Map<String, Object>> emps = new LinkedHashMap<>();
        for (Map<String, Object> e : repo.employees(new ArrayList<>(byEmployee.keySet()))) emps.put((UUID) e.get("id"), e);
        TaxService.Config cfg;
        try {
            cfg = tax.taxConfig(fy);
        } catch (IllegalStateException e) {
            throw new UserException(e.getMessage());
        }
        Map<String, Object> d = deductor();
        Employer employer = new Employer((String) d.get("name"), (String) d.get("address"), (String) d.get("gstin"), (String) d.get("tan"), (String) d.get("pan"));
        String today = Format.todayIn("Asia/Kolkata").toString();

        int published = 0, skipped = 0;
        for (Map.Entry<UUID, List<JsonNode>> en : byEmployee.entrySet()) {
            UUID empId = en.getKey();
            if (have.contains(empId)) {
                skipped++;
                continue;
            }
            Map<String, Object> e = emps.get(empId);
            if (e == null) continue;
            Form16Engine.Result res = Form16Engine.compute(inputFor(empId, fy, en.getValue(), cfg));
            String panEnc = (String) e.get("pan_enc");
            EmployeeInfo info = new EmployeeInfo((String) e.get("code"), (String) e.get("full_name"), (String) e.get("designation"), e.get("dept") == null ? null : (String) e.get("dept"),
                    ((Date) e.get("date_of_joining")).toLocalDate().toString(), e.get("exit_date") == null ? null : ((Date) e.get("exit_date")).toLocalDate().toString(),
                    (String) e.get("pan_last4"), panEnc);
            Snapshot snap = new Snapshot(1, fy, Form16Engine.assessmentYear(fy), today, employer, info, res);
            String text;
            try {
                text = json.writeValueAsString(snap);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException(ex);
            }
            byte[] pdf = Form16Pdf.render(parse(text), panEnc == null ? "" : pii.decrypt(panEnc));
            int n = repo.insert(UUID.randomUUID(), fy, empId, info.code(), info.name(), text, PolicyService.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)), pdf,
                    PolicyService.sha256(pdf), u.id());
            if (n == 0) {
                skipped++;
                continue;
            }
            published++;
            notifications.notify(empId, "Your Form 16 for FY " + fy + " is ready", "/form16");
        }
        audit.log(u.id(), "form16.publish", "form16", fy, null, Map.of("fy", fy, "published", published, "alreadyPublished", skipped), ip);
        return new Publication(fy, published, skipped);
    }

    private Form16Engine.Input inputFor(UUID empId, String fy, List<JsonNode> slips, TaxService.Config cfg) {
        List<Form16Engine.MonthIn> months = new ArrayList<>();
        for (JsonNode s : slips) {
            long basic = 0, hra = 0;
            for (JsonNode c : s.get("components")) {
                if ("Basic".equals(c.get("label").asText()) && basic == 0) basic = c.get("earned").asLong();
                if ("House rent allowance".equals(c.get("label").asText()) && hra == 0) hra = c.get("earned").asLong();
            }
            months.add(new Form16Engine.MonthIn(s.get("ym").asText(), s.hasNonNull("payDate") ? s.get("payDate").asText() : null, s.get("grossEarned").asLong(), basic, hra,
                    s.get("pf").asLong(), s.get("pt").asLong(), s.get("tds").asLong()));
        }
        TaxService.ProfileFigures prof = tax.profile(empId, fy);
        String regime = prof != null ? prof.regime() : slips.get(slips.size() - 1).path("regime").asText("NEW");
        TaxEngine.TaxYearConfig yc = "OLD".equals(regime) ? cfg.OLD() : cfg.NEW();
        return new Form16Engine.Input(fy, regime, months, prof == null ? 0 : prof.prevIncome(), prof == null ? 0 : prof.prevTds(),
                tax.declarations(empId, fy, TaxService.DeclMode.approved).declared(), yc, cfg.caps(), cfg.labels());
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ───────────────────────── read ─────────────────────────

    /** The signed-in employee's own published Form 16s. */
    public List<Map<String, Object>> mine(CurrentUser u) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : repo.findByEmployee(u.id())) {
            JsonNode b = parse((String) r.get("snapshot")).at("/computed/partB");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id").toString());
            m.put("fy", r.get("fy"));
            m.put("publishedAt", iso(r.get("published_at")));
            m.put("grossTotalIncome", b.get("grossTotalIncome").asLong());
            m.put("taxableIncome", b.get("taxableIncome").asLong());
            m.put("totalTax", b.get("totalTax").asLong());
            m.put("tdsTotal", b.get("tdsTotal").asLong());
            m.put("sha256", r.get("snapshot_sha256"));
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> accessible(CurrentUser u, String idRaw) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = repo.findById(id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Form 16 not found.");
        boolean own = u.id().equals(rows.get(0).get("employee_id")) && u.can("self");
        if (!own && !u.can("tax.view")) throw new ForbiddenException();
        return rows.get(0);
    }

    /** The snapshot as published (the PAN shown masked), with its fingerprints. */
    public Map<String, Object> view(CurrentUser u, String idRaw) {
        Map<String, Object> r = accessible(u, idRaw);
        ObjectNode snap = (ObjectNode) parse((String) r.get("snapshot"));
        ObjectNode emp = (ObjectNode) snap.get("employee");
        emp.remove("panEnc");
        emp.put("pan", Masks.pan(emp.path("panLast4").asText(null)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id").toString());
        out.put("fy", r.get("fy"));
        out.put("publishedAt", iso(r.get("published_at")));
        out.put("snapshotSha256", r.get("snapshot_sha256"));
        out.put("pdfSha256", r.get("pdf_sha256"));
        out.put("snapshot", snap);
        return out;
    }

    public record PdfFile(String filename, byte[] bytes) {}

    public PdfFile pdf(CurrentUser u, String idRaw) {
        Map<String, Object> r = accessible(u, idRaw);
        byte[] pdf = repo.findPdf((UUID) r.get("id"));
        return new PdfFile("Form16-" + r.get("employee_code") + "-" + r.get("fy") + ".pdf", pdf);
    }

    // ───────────────────────── Form 24Q working file ─────────────────────────

    public record Export(String filename, String body) {}

    static final List<String> HEADER_24Q = List.of("Quarter", "Month", "Employee ID", "Employee name", "PAN of employee", "Section", "Date of payment / credit",
            "Amount paid / credited (Rs.)", "Income-tax deducted incl. cess (Rs.)", "Amount deposited (Rs.)", "BSR code of bank branch", "Challan serial number", "Date of deposit");

    static String rupees(long paise) {
        return String.format(java.util.Locale.ROOT, "%.2f", paise / 100.0);
    }

    /**
     * The data a quarter's Form 24Q needs, one row per employee per month, from the closed payslips. Challan columns are
     * left blank for Finance. A working file, not a filed return. Reading PANs in full is audited.
     */
    public Export form24q(CurrentUser u, String fy, String quarter, String ip) {
        requireFinance(u);
        List<String> months = Form16Engine.quarterMonths(fy, quarter);
        Set<String> closed = Set.copyOf(repo.closedMonths(months.get(0), months.get(2)));
        List<String> missing = months.stream().filter(m -> !closed.contains(m)).map(Form16Service::mon).toList();
        if (!missing.isEmpty()) {
            throw new UserException(HttpStatus.UNPROCESSABLE_ENTITY, "Quarter " + quarter + " of FY " + fy + " needs its payroll months closed. Still open or missing: "
                    + String.join(", ", missing) + ".");
        }
        List<Map<String, Object>> slips = repo.closedPayslips(months.get(0), months.get(2));
        Map<UUID, Map<String, Object>> emps = new LinkedHashMap<>();
        if (!slips.isEmpty()) {
            for (Map<String, Object> e : repo.employees(slips.stream().map(s -> (UUID) s.get("employee_id")).distinct().toList())) emps.put((UUID) e.get("id"), e);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> s : slips) {
            JsonNode snap = parse((String) s.get("snapshot"));
            Map<String, Object> e = emps.get((UUID) s.get("employee_id"));
            if (e == null) continue;
            String pan = e.get("pan_enc") == null ? "PANNOTAVBL" : pii.decrypt((String) e.get("pan_enc"));
            rows.add(new ArrayList<>(List.of(quarter, s.get("month"), e.get("code"), e.get("full_name"), pan, "192", snap.hasNonNull("payDate") ? snap.get("payDate").asText() : "",
                    rupees(snap.get("grossEarned").asLong()), rupees(snap.get("tds").asLong()), "", "", "", "")));
        }
        rows.sort(java.util.Comparator.<List<Object>, String>comparing(r -> (String) r.get(1)).thenComparing(r -> (String) r.get(3)));
        audit.log(u.id(), "form16.24q_export", "form16", fy + "/" + quarter, null, Map.of("rows", rows.size(), "identityNumbers", "full"), ip);
        String note = "WORKING FILE FOR FORM 24Q, FY " + fy + " " + quarter + " - NOT A FILED RETURN. Challan columns (deposited amount, BSR code, serial number, date) are blank for Finance to fill; "
                + "check everything against the TRACES / FVU utility before filing.";
        String csv = Csv.toCsv(HEADER_24Q, rows);
        // the note is the first line, above the header row (the BOM stays first)
        String body = csv.charAt(0) + Csv.cell(note) + "\r\n" + csv.substring(1);
        return new Export("form24q-working-file-" + fy + "-" + quarter + ".csv", body);
    }
}
