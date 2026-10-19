package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.LetterTemplateEngine;
import com.jurislpo.jurisone.engine.LetterTemplateEngine.Mode;
import com.jurislpo.jurisone.engine.PayrollEngine;
import com.jurislpo.jurisone.engine.TaxEngine;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.BusinessRepository;
import com.jurislpo.jurisone.repository.LettersRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HR letters. HR (letters.manage) keeps the business's letter templates and issues a letter to an employee: the template's
 * merge fields are filled, the result is stored as an immutable record (text, SHA-256, PDF, who and when, reference number
 * HRL/&lt;FY&gt;/&lt;nnnn&gt;) and the employee is told. A template that uses a salary field (CTC) can only be issued, previewed
 * or opened by someone with struct.manage — HR never sees salary figures.
 */
@Service
@Transactional
public class LettersService {

    public static final List<String> TYPES = List.of("OFFER", "APPOINTMENT", "EXPERIENCE", "RELIEVING", "EMPLOYMENT_VERIFICATION");

    private final LettersRepository repo;
    private final BusinessRepository business;
    private final NotificationService notifications;
    private final AuditService audit;

    public LettersService(LettersRepository repo, BusinessRepository business, NotificationService notifications, AuditService audit) {
        this.repo = repo;
        this.business = business;
        this.notifications = notifications;
        this.audit = audit;
    }

    static String iso(Object ts) {
        return ts == null ? null : Format.jsIso(((Timestamp) ts).toInstant());
    }

    // ───────────────────────── templates ─────────────────────────

    public record TemplateInput(String type, String name, String subject, String body, Boolean active) {}

    public Map<String, Object> fields() {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (LetterTemplateEngine.Field f : LetterTemplateEngine.FIELDS) {
            fields.add(Map.of("name", f.name(), "label", f.label(), "salary", f.salary()));
        }
        return Map.of("fields", fields, "types", TYPES);
    }

    public List<Map<String, Object>> templates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : repo.templates()) out.add(templateView(r, false));
        return out;
    }

    public Map<String, Object> template(String idRaw) {
        return templateView(templateRow(Check.uuid(idRaw)), true);
    }

    private Map<String, Object> templateRow(UUID id) {
        List<Map<String, Object>> rows = repo.template(id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Template not found.");
        return rows.get(0);
    }

    private static Map<String, Object> templateView(Map<String, Object> r, boolean withBody) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id").toString());
        m.put("type", r.get("type"));
        m.put("name", r.get("name"));
        m.put("subject", r.get("subject"));
        if (withBody) m.put("body", r.get("body"));
        m.put("needsSalary", r.get("needs_salary"));
        m.put("active", r.get("active"));
        m.put("version", r.get("version"));
        m.put("updatedAt", iso(r.get("updated_at")));
        return m;
    }

    record Valid(String type, String name, String subject, String body, boolean needsSalary) {}

    static Valid validate(TemplateInput in, boolean typeRequired) {
        if (in == null) throw new UserException(HttpStatus.BAD_REQUEST, "Template details are missing.");
        String type = typeRequired ? Check.oneOf(in.type(), TYPES) : in.type();
        String name = Check.trimmed(in.name(), 3, "Give the template a name.", 100, "Keep the name under 100 characters.");
        String subject = Check.trimmed(in.subject(), 3, "Enter the subject line.", 200, "Keep the subject under 200 characters.");
        String body = in.body() == null ? "" : in.body().replace("\r\n", "\n").strip();
        if (body.length() < 20) throw new UserException(HttpStatus.BAD_REQUEST, "Write the letter text.");
        if (body.length() > 20_000) throw new UserException(HttpStatus.BAD_REQUEST, "The letter text is too long (20,000 characters at most).");
        LetterTemplateEngine.validate(subject, body);
        return new Valid(type, name, subject, body, LetterTemplateEngine.usesSalary(subject, body));
    }

    public String createTemplate(CurrentUser u, TemplateInput in, String ip) {
        Valid v = validate(in, true);
        UUID id = UUID.randomUUID();
        try {
            repo.insertTemplate(id, v.type(), v.name(), v.subject(), v.body(), v.needsSalary(), u.id());
        } catch (DuplicateKeyException e) {
            throw new UserException(HttpStatus.CONFLICT, "A template named \"" + v.name() + "\" already exists.");
        }
        audit.log(u.id(), "letter_template.create", "letter_template", id.toString(), null, Map.of("name", v.name(), "type", v.type()), ip);
        return id.toString();
    }

    public void updateTemplate(CurrentUser u, String idRaw, TemplateInput in, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> before = templateRow(id);
        Valid v = validate(in, false);
        boolean active = in.active() == null ? (Boolean) before.get("active") : in.active();
        try {
            repo.updateTemplate(id, v.name(), v.subject(), v.body(), v.needsSalary(), active, u.id());
        } catch (DuplicateKeyException e) {
            throw new UserException(HttpStatus.CONFLICT, "A template named \"" + v.name() + "\" already exists.");
        }
        audit.log(u.id(), "letter_template.update", "letter_template", id.toString(), Map.of("name", before.get("name"), "version", before.get("version")),
                Map.of("name", v.name(), "active", active), ip);
    }

    // ───────────────────────── employees ─────────────────────────

    public List<Map<String, Object>> employees() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : repo.employeeChoices()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id").toString());
            m.put("code", r.get("code"));
            m.put("name", r.get("full_name"));
            m.put("designation", r.get("designation"));
            m.put("status", r.get("status"));
            m.put("exitDate", r.get("exit_date") == null ? null : ((Date) r.get("exit_date")).toLocalDate().toString());
            out.add(m);
        }
        return out;
    }

    // ───────────────────────── preview and issue ─────────────────────────

    public record IssueInput(String templateId, String employeeId, String lastWorkingDate) {}

    private record Rendered(String subject, String text, String html) {}

    private Map<String, Object> employeeRow(String idRaw) {
        List<Map<String, Object>> rows = repo.employee(Check.uuid(idRaw, "Choose an employee."));
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Employee not found.");
        return rows.get(0);
    }

    private void checkMaySeeSalary(CurrentUser u, Map<String, Object> tpl) {
        if (Boolean.TRUE.equals(tpl.get("needs_salary")) && !u.can("struct.manage")) {
            throw new ForbiddenException("This letter states a salary, so only someone who manages salary structures can issue it.");
        }
    }

    private Map<String, String> values(CurrentUser u, Map<String, Object> tpl, Map<String, Object> emp, String lastWorkingDateIn, String reference, LocalDate today) {
        Map<String, Object> biz = business.findProfile(TenantContext.current());
        Map<String, String> v = new HashMap<>();
        LocalDate doj = ((Date) emp.get("date_of_joining")).toLocalDate();
        v.put("employee.name", (String) emp.get("full_name"));
        v.put("employee.code", (String) emp.get("code"));
        v.put("employee.email", (String) emp.get("email"));
        v.put("designation", (String) emp.get("designation"));
        v.put("department", (String) emp.get("dept"));
        v.put("joiningDate", Format.fmtDY(doj));
        LocalDate last = null;
        if (lastWorkingDateIn != null && !lastWorkingDateIn.isBlank()) {
            Check.isoDate(lastWorkingDateIn, "Enter the last working day as a date.");
            try {
                last = LocalDate.parse(lastWorkingDateIn);
            } catch (DateTimeParseException e) {
                throw new UserException(HttpStatus.BAD_REQUEST, "Enter the last working day as a date.");
            }
        } else if (emp.get("exit_date") != null) {
            last = ((Date) emp.get("exit_date")).toLocalDate();
        }
        if (last != null && last.isBefore(doj)) throw new UserException("The last working day can't be before the joining date.");
        v.put("lastWorkingDate", last == null ? null : Format.fmtDY(last));
        v.put("business.name", (String) biz.get("name"));
        v.put("business.legalName", biz.get("legal_name") != null ? (String) biz.get("legal_name") : (String) biz.get("name"));
        v.put("business.address", (String) biz.get("address"));
        v.put("today", Format.fmtDY(today));
        v.put("reference", reference);
        v.put("issuedBy.name", u.name());
        if (Boolean.TRUE.equals(tpl.get("needs_salary"))) {
            List<Long> gross = repo.monthlyGrossOn((UUID) emp.get("id"), today);
            if (gross.isEmpty()) throw new UserException(emp.get("full_name") + " has no salary structure yet, so a letter with the CTC can't be issued.");
            long annual = gross.get(0) * 12;
            v.put("ctc", "Rs. " + Format.groupIndian(Math.round(annual / 100.0)));
            v.put("ctcInWords", PayrollEngine.rupeesInWords(annual / 100.0));
        }
        return v;
    }

    private Rendered render(Map<String, Object> tpl, Map<String, String> values) {
        String subject = (String) tpl.get("subject"), body = (String) tpl.get("body");
        return new Rendered(LetterTemplateEngine.render(subject, values, Mode.TEXT), LetterTemplateEngine.render(body, values, Mode.TEXT),
                LetterTemplateEngine.render(body, values, Mode.HTML));
    }

    private LocalDate today() {
        return Format.todayIn((String) business.findProfile(TenantContext.current()).get("time_zone"));
    }

    static String fyOf(LocalDate d) {
        return TaxEngine.fyOf(d.toString().substring(0, 7)).fy();
    }

    /** What the letter would say; nothing is saved. A salary letter needs struct.manage even for the preview. */
    public Map<String, Object> preview(CurrentUser u, String templateId, String employeeId, String lastWorkingDate) {
        Map<String, Object> tpl = templateRow(Check.uuid(templateId, "Choose a template."));
        checkMaySeeSalary(u, tpl);
        Map<String, Object> emp = employeeRow(employeeId);
        LocalDate today = today();
        Rendered r = render(tpl, values(u, tpl, emp, lastWorkingDate, "HRL/" + fyOf(today) + "/(assigned on issue)", today));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subject", r.subject());
        out.put("text", r.text());
        out.put("html", r.html());
        out.put("needsSalary", tpl.get("needs_salary"));
        return out;
    }

    /** Issues the letter: renders it, takes the next reference number, stores the immutable record and PDF, tells the employee. */
    public Map<String, Object> issue(CurrentUser u, IssueInput in, String ip) {
        if (in == null) throw new UserException(HttpStatus.BAD_REQUEST, "Letter details are missing.");
        Map<String, Object> tpl = templateRow(Check.uuid(in.templateId(), "Choose a template."));
        if (!Boolean.TRUE.equals(tpl.get("active"))) throw new UserException("This template is switched off. Turn it on or choose another.");
        checkMaySeeSalary(u, tpl);
        Map<String, Object> emp = employeeRow(in.employeeId());
        LocalDate today = today();
        String fy = fyOf(today);
        String reference = "HRL/" + fy + "/" + String.format(java.util.Locale.ROOT, "%04d", repo.nextNumber(fy)); // rolls back if rendering fails
        Rendered r = render(tpl, values(u, tpl, emp, in.lastWorkingDate(), reference, today));
        String hash = PolicyService.sha256((r.subject() + "\n\n" + r.text()).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> biz = business.findProfile(TenantContext.current());
        byte[] pdf = LetterPdf.render((String) biz.get("name"), (String) biz.get("address"), reference, Format.fmtDY(today), r.subject(), r.text(), hash);
        UUID id = UUID.randomUUID();
        repo.insertIssued(id, reference, (UUID) tpl.get("id"), (String) tpl.get("name"), ((Number) tpl.get("version")).intValue(), (String) tpl.get("type"),
                Boolean.TRUE.equals(tpl.get("needs_salary")), (UUID) emp.get("id"), (String) emp.get("code"), (String) emp.get("full_name"), r.subject(), r.text(), hash, pdf,
                PolicyService.sha256(pdf), u.id(), u.name());
        notifications.notify((UUID) emp.get("id"), "A letter was issued to you: " + r.subject() + " (" + reference + ")", "/my-letters");
        audit.log(u.id(), "letter.issue", "issued_letter", id.toString(), null,
                Map.of("reference", reference, "type", tpl.get("type"), "employee", emp.get("code"), "sha256", hash), ip);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("reference", reference);
        return out;
    }

    // ───────────────────────── issued letters ─────────────────────────

    private Map<String, Object> view(Map<String, Object> r, CurrentUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id").toString());
        m.put("reference", r.get("reference_no"));
        m.put("type", r.get("letter_type"));
        m.put("templateName", r.get("template_name"));
        m.put("subject", r.get("subject"));
        m.put("employeeId", r.get("employee_id").toString());
        m.put("employeeCode", r.get("employee_code"));
        m.put("employeeName", r.get("employee_name"));
        m.put("issuedBy", r.get("issued_by_name"));
        m.put("issuedAt", iso(r.get("issued_at")));
        m.put("needsSalary", r.get("needs_salary"));
        m.put("canOpen", canOpen(u, r));
        return m;
    }

    private static boolean canOpen(CurrentUser u, Map<String, Object> r) {
        if (u.id().equals(r.get("employee_id")) && u.can("self")) return true;
        return u.can("letters.manage") && (!Boolean.TRUE.equals(r.get("needs_salary")) || u.can("struct.manage"));
    }

    /** HR: letters issued, newest first (optionally of one employee). Letters that state a salary can only be opened with struct.manage. */
    public List<Map<String, Object>> list(CurrentUser u, String employeeId) {
        UUID emp = employeeId == null || employeeId.isBlank() ? null : Check.uuid(employeeId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : repo.issued(emp)) out.add(view(r, u));
        return out;
    }

    /** The signed-in employee's own letters. */
    public List<Map<String, Object>> mine(CurrentUser u) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : repo.issuedTo(u.id())) out.add(view(r, u));
        return out;
    }

    private Map<String, Object> accessible(CurrentUser u, String idRaw) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = repo.issuedById(id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Letter not found.");
        if (!canOpen(u, rows.get(0))) throw new ForbiddenException();
        return rows.get(0);
    }

    public Map<String, Object> detail(CurrentUser u, String idRaw) {
        Map<String, Object> r = accessible(u, idRaw);
        Map<String, Object> m = view(r, u);
        m.put("body", r.get("body"));
        m.put("bodySha256", r.get("body_sha256"));
        m.put("pdfSha256", r.get("pdf_sha256"));
        return m;
    }

    public record PdfFile(String filename, byte[] bytes) {}

    public PdfFile pdf(CurrentUser u, String idRaw) {
        Map<String, Object> r = accessible(u, idRaw);
        return new PdfFile("Letter-" + ((String) r.get("reference_no")).replace('/', '-') + ".pdf", repo.issuedPdf((UUID) r.get("id")));
    }
}
