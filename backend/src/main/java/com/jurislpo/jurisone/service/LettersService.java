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
    private final MailService mail;
    private final BusinessUrls businessUrls;

    public LettersService(LettersRepository repo, BusinessRepository business, NotificationService notifications, AuditService audit, MailService mail, BusinessUrls businessUrls) {
        this.mail = mail;
        this.businessUrls = businessUrls;
        this.repo = repo;
        this.business = business;
        this.notifications = notifications;
        this.audit = audit;
    }

    static String formatIsoTimestamp(Object timestamp) {
        return timestamp == null ? null : Format.isoTimestamp(((Timestamp) timestamp).toInstant());
    }

    // ───────────────────────── templates ─────────────────────────

    public record TemplateInput(String type, String name, String subject, String body, Boolean active) {}

    public Map<String, Object> fields() {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (LetterTemplateEngine.Field field : LetterTemplateEngine.FIELDS) {
            fields.add(Map.of("name", field.name(), "label", field.label(), "salary", field.salary()));
        }
        return Map.of("fields", fields, "types", TYPES);
    }

    public List<Map<String, Object>> templates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> template : repo.templates()) out.add(templateView(template, false));
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

    private static Map<String, Object> templateView(Map<String, Object> row, boolean withBody) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.get("id").toString());
        view.put("type", row.get("type"));
        view.put("name", row.get("name"));
        view.put("subject", row.get("subject"));
        if (withBody) view.put("body", row.get("body"));
        view.put("needsSalary", row.get("needs_salary"));
        view.put("active", row.get("active"));
        view.put("version", row.get("version"));
        view.put("updatedAt", formatIsoTimestamp(row.get("updated_at")));
        return view;
    }

    record ValidatedTemplate(String type, String name, String subject, String body, boolean needsSalary) {}

    static ValidatedTemplate validate(TemplateInput input, boolean typeRequired) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Template details are missing.");
        String type = typeRequired ? Check.oneOf(input.type(), TYPES) : input.type();
        String name = Check.trimmed(input.name(), 3, "Give the template a name.", 100, "Keep the name under 100 characters.");
        String subject = Check.trimmed(input.subject(), 3, "Enter the subject line.", 200, "Keep the subject under 200 characters.");
        String body = input.body() == null ? "" : input.body().replace("\r\n", "\n").strip();
        if (body.length() < 20) throw new UserException(HttpStatus.BAD_REQUEST, "Write the letter text.");
        if (body.length() > 20_000) throw new UserException(HttpStatus.BAD_REQUEST, "The letter text is too long (20,000 characters at most).");
        LetterTemplateEngine.validate(subject, body);
        return new ValidatedTemplate(type, name, subject, body, LetterTemplateEngine.usesSalary(subject, body));
    }

    public String createTemplate(CurrentUser user, TemplateInput input, String ipAddress) {
        ValidatedTemplate validated = validate(input, true);
        UUID id = UUID.randomUUID();
        try {
            repo.insertTemplate(id, validated.type(), validated.name(), validated.subject(), validated.body(), validated.needsSalary(), user.id());
        } catch (DuplicateKeyException e) {
            throw new UserException(HttpStatus.CONFLICT, "A template named \"" + validated.name() + "\" already exists.");
        }
        audit.log(user.id(), "letter_template.create", "letter_template", id.toString(), null, Map.of("name", validated.name(), "type", validated.type()), ipAddress);
        return id.toString();
    }

    public void updateTemplate(CurrentUser user, String idRaw, TemplateInput input, String ipAddress) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> before = templateRow(id);
        ValidatedTemplate validated = validate(input, false);
        boolean active = input.active() == null ? (Boolean) before.get("active") : input.active();
        try {
            repo.updateTemplate(id, validated.name(), validated.subject(), validated.body(), validated.needsSalary(), active, user.id());
        } catch (DuplicateKeyException e) {
            throw new UserException(HttpStatus.CONFLICT, "A template named \"" + validated.name() + "\" already exists.");
        }
        audit.log(user.id(), "letter_template.update", "letter_template", id.toString(), Map.of("name", before.get("name"), "version", before.get("version")),
                Map.of("name", validated.name(), "active", active), ipAddress);
    }

    // ───────────────────────── employees ─────────────────────────

    public List<Map<String, Object>> employees() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> employeeChoice : repo.employeeChoices()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", employeeChoice.get("id").toString());
            view.put("code", employeeChoice.get("code"));
            view.put("name", employeeChoice.get("full_name"));
            view.put("designation", employeeChoice.get("designation"));
            view.put("status", employeeChoice.get("status"));
            view.put("exitDate", employeeChoice.get("exit_date") == null ? null : ((Date) employeeChoice.get("exit_date")).toLocalDate().toString());
            out.add(view);
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

    private void checkMaySeeSalary(CurrentUser user, Map<String, Object> template) {
        if (Boolean.TRUE.equals(template.get("needs_salary")) && !user.can("struct.manage")) {
            throw new ForbiddenException("This letter states a salary, so only someone who manages salary structures can issue it.");
        }
    }

    private Map<String, String> values(CurrentUser user, Map<String, Object> template, Map<String, Object> employee, String lastWorkingDateIn, String reference, LocalDate today) {
        Map<String, Object> businessProfile = business.findProfile(TenantContext.current());
        Map<String, String> values = new HashMap<>();
        LocalDate dateOfJoining = ((Date) employee.get("date_of_joining")).toLocalDate();
        values.put("employee.name", (String) employee.get("full_name"));
        values.put("employee.code", (String) employee.get("code"));
        values.put("employee.email", (String) employee.get("email"));
        values.put("designation", (String) employee.get("designation"));
        values.put("department", (String) employee.get("department_name"));
        values.put("joiningDate", Format.formatDayMonthYear(dateOfJoining));
        LocalDate last = null;
        if (lastWorkingDateIn != null && !lastWorkingDateIn.isBlank()) {
            Check.isoDate(lastWorkingDateIn, "Enter the last working day as a date.");
            try {
                last = LocalDate.parse(lastWorkingDateIn);
            } catch (DateTimeParseException e) {
                throw new UserException(HttpStatus.BAD_REQUEST, "Enter the last working day as a date.");
            }
        } else if (employee.get("exit_date") != null) {
            last = ((Date) employee.get("exit_date")).toLocalDate();
        }
        if (last != null && last.isBefore(dateOfJoining)) throw new UserException("The last working day can't be before the joining date.");
        values.put("lastWorkingDate", last == null ? null : Format.formatDayMonthYear(last));
        values.put("business.name", (String) businessProfile.get("name"));
        values.put("business.legalName", businessProfile.get("legal_name") != null ? (String) businessProfile.get("legal_name") : (String) businessProfile.get("name"));
        values.put("business.address", (String) businessProfile.get("address"));
        values.put("today", Format.formatDayMonthYear(today));
        values.put("reference", reference);
        values.put("issuedBy.name", user.name());
        if (Boolean.TRUE.equals(template.get("needs_salary"))) {
            List<Long> gross = repo.monthlyGrossOn((UUID) employee.get("id"), today);
            if (gross.isEmpty()) throw new UserException(employee.get("full_name") + " has no salary structure yet, so a letter with the CTC can't be issued.");
            long annual = gross.get(0) * 12;
            values.put("ctc", "Rs. " + Format.groupIndian(Math.round(annual / 100.0)));
            values.put("ctcInWords", PayrollEngine.rupeesInWords(annual / 100.0));
        }
        return values;
    }

    private Rendered render(Map<String, Object> template, Map<String, String> values) {
        String subject = (String) template.get("subject"), body = (String) template.get("body");
        return new Rendered(LetterTemplateEngine.render(subject, values, Mode.TEXT), LetterTemplateEngine.render(body, values, Mode.TEXT),
                LetterTemplateEngine.render(body, values, Mode.HTML));
    }

    private LocalDate today() {
        return Format.todayIn((String) business.findProfile(TenantContext.current()).get("time_zone"));
    }

    static String financialYearOf(LocalDate date) {
        return TaxEngine.financialYearOf(date.toString().substring(0, 7)).financialYear();
    }

    /** What the letter would say; nothing is saved. A salary letter needs struct.manage even for the preview. */
    public Map<String, Object> preview(CurrentUser user, String templateId, String employeeId, String lastWorkingDate) {
        Map<String, Object> template = templateRow(Check.uuid(templateId, "Choose a template."));
        checkMaySeeSalary(user, template);
        Map<String, Object> employee = employeeRow(employeeId);
        LocalDate today = today();
        Rendered rendered = render(template, values(user, template, employee, lastWorkingDate, "HRL/" + financialYearOf(today) + "/(assigned on issue)", today));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subject", rendered.subject());
        out.put("text", rendered.text());
        out.put("html", rendered.html());
        out.put("needsSalary", template.get("needs_salary"));
        return out;
    }

    /** Issues the letter: renders it, takes the next reference number, stores the immutable record and PDF, tells the employee. */
    public Map<String, Object> issue(CurrentUser user, IssueInput input, String ipAddress) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Letter details are missing.");
        Map<String, Object> template = templateRow(Check.uuid(input.templateId(), "Choose a template."));
        if (!Boolean.TRUE.equals(template.get("active"))) throw new UserException("This template is switched off. Turn it on or choose another.");
        checkMaySeeSalary(user, template);
        Map<String, Object> employee = employeeRow(input.employeeId());
        LocalDate today = today();
        String financialYear = financialYearOf(today);
        String reference = "HRL/" + financialYear + "/" + String.format(java.util.Locale.ROOT, "%04d", repo.nextNumber(financialYear)); // rolls back if rendering fails
        Rendered rendered = render(template, values(user, template, employee, input.lastWorkingDate(), reference, today));
        String hash = PolicyService.sha256((rendered.subject() + "\n\n" + rendered.text()).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> businessProfile = business.findProfile(TenantContext.current());
        byte[] pdfBytes = LetterPdf.render((String) businessProfile.get("name"), (String) businessProfile.get("address"), reference, Format.formatDayMonthYear(today), rendered.subject(), rendered.text(), hash);
        UUID id = UUID.randomUUID();
        repo.insertIssued(id, reference, (UUID) template.get("id"), (String) template.get("name"), ((Number) template.get("version")).intValue(), (String) template.get("type"),
                Boolean.TRUE.equals(template.get("needs_salary")), (UUID) employee.get("id"), (String) employee.get("code"), (String) employee.get("full_name"), rendered.subject(), rendered.text(), hash, pdfBytes,
                PolicyService.sha256(pdfBytes), user.id(), user.name());
        notifications.notify((UUID) employee.get("id"), "A letter was issued to you: " + rendered.subject() + " (" + reference + ")", "/my-letters");
        // by email too (a PDF attached); without mail settings it is only logged, and a failed send never fails the issue
        String to = (String) employee.get("email");
        if (to != null && !to.isBlank()) {
            mail.send(to, MailTemplates.letter((String) employee.get("full_name"), (String) businessProfile.get("name"), rendered.subject(), reference, businessUrls.forBusiness(TenantContext.current(), "/my-letters")),
                    new MailService.Attachment(reference.replace('/', '-') + ".pdf", pdfBytes, "application/pdf"));
        }
        audit.log(user.id(), "letter.issue", "issued_letter", id.toString(), null,
                Map.of("reference", reference, "type", template.get("type"), "employee", employee.get("code"), "sha256", hash), ipAddress);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("reference", reference);
        return out;
    }

    // ───────────────────────── issued letters ─────────────────────────

    private Map<String, Object> view(Map<String, Object> row, CurrentUser user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.get("id").toString());
        view.put("reference", row.get("reference_no"));
        view.put("type", row.get("letter_type"));
        view.put("templateName", row.get("template_name"));
        view.put("subject", row.get("subject"));
        view.put("employeeId", row.get("employee_id").toString());
        view.put("employeeCode", row.get("employee_code"));
        view.put("employeeName", row.get("employee_name"));
        view.put("issuedBy", row.get("issued_by_name"));
        view.put("issuedAt", formatIsoTimestamp(row.get("issued_at")));
        view.put("needsSalary", row.get("needs_salary"));
        view.put("canOpen", canOpen(user, row));
        return view;
    }

    private static boolean canOpen(CurrentUser user, Map<String, Object> row) {
        if (user.id().equals(row.get("employee_id")) && user.can("self")) return true;
        return user.can("letters.manage") && (!Boolean.TRUE.equals(row.get("needs_salary")) || user.can("struct.manage"));
    }

    /** HR: letters issued, newest first (optionally of one employee). Letters that state a salary can only be opened with struct.manage. */
    public List<Map<String, Object>> list(CurrentUser user, String employeeId) {
        UUID employee = employeeId == null || employeeId.isBlank() ? null : Check.uuid(employeeId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> letter : repo.issued(employee)) out.add(view(letter, user));
        return out;
    }

    /** The signed-in employee's own letters. */
    public List<Map<String, Object>> mine(CurrentUser user) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> letter : repo.issuedTo(user.id())) out.add(view(letter, user));
        return out;
    }

    private Map<String, Object> accessible(CurrentUser user, String idRaw) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = repo.issuedById(id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Letter not found.");
        if (!canOpen(user, rows.get(0))) throw new ForbiddenException();
        return rows.get(0);
    }

    public Map<String, Object> detail(CurrentUser user, String idRaw) {
        Map<String, Object> letter = accessible(user, idRaw);
        Map<String, Object> row = view(letter, user);
        row.put("body", letter.get("body"));
        row.put("bodySha256", letter.get("body_sha256"));
        row.put("pdfSha256", letter.get("pdf_sha256"));
        return row;
    }

    public record PdfFile(String filename, byte[] bytes) {}

    public PdfFile pdf(CurrentUser user, String idRaw) {
        Map<String, Object> letter = accessible(user, idRaw);
        return new PdfFile("Letter-" + ((String) letter.get("reference_no")).replace('/', '-') + ".pdf", repo.issuedPdf((UUID) letter.get("id")));
    }
}
