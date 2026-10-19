package com.jurislpo.jurisone.engine;

import com.jurislpo.jurisone.exception.UserException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

/**
 * The merge engine of HR letters: {@code {{field}}} placeholders filled from a fixed catalogue. One pass only, so a value
 * that itself contains "{{x}}" is never merged again. Unknown fields are rejected (when a template is saved and again
 * when it is rendered), a known field without a value is an error naming the field, and in HTML mode both the template
 * text and every value are escaped.
 */
public final class LetterTemplateEngine {
    private LetterTemplateEngine() {}

    /** @param salary the field exposes a salary figure: a template using it can only be issued with struct.manage */
    public record Field(String name, String label, boolean salary) {}

    public static final List<Field> FIELDS = List.of(
            new Field("employee.name", "Employee's full name", false),
            new Field("employee.code", "Employee ID", false),
            new Field("employee.email", "Work email", false),
            new Field("designation", "Designation", false),
            new Field("department", "Department", false),
            new Field("joiningDate", "Date of joining", false),
            new Field("lastWorkingDate", "Last working day (from the employee's record, or typed when issuing)", false),
            new Field("business.name", "Company name", false),
            new Field("business.legalName", "Company legal name", false),
            new Field("business.address", "Company address", false),
            new Field("today", "Date of issue", false),
            new Field("reference", "Letter reference number", false),
            new Field("issuedBy.name", "Name of the person issuing the letter", false),
            new Field("ctc", "Annual CTC, e.g. Rs. 12,00,000 (salary)", true),
            new Field("ctcInWords", "Annual CTC in words (salary)", true));

    public static final Set<String> NAMES = FIELDS.stream().map(Field::name).collect(Collectors.toUnmodifiableSet());
    public static final Set<String> SALARY_NAMES = FIELDS.stream().filter(Field::salary).map(Field::name).collect(Collectors.toUnmodifiableSet());

    private static final Pattern TOKEN = Pattern.compile("\\{\\{(.*?)\\}\\}", Pattern.DOTALL);

    public enum Mode { TEXT, HTML }

    /** A known field has no value for this letter. */
    public static final class MissingFieldsException extends UserException {
        private final List<String> fields;

        MissingFieldsException(List<String> fields) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, "No value for " + fields.stream().map(f -> "{{" + f + "}}").collect(Collectors.joining(", "))
                    + ". Fill it in on the employee's record or when issuing the letter.");
            this.fields = fields;
        }

        public List<String> fields() {
            return fields;
        }
    }

    /** The field names used, trimmed, distinct, in order of first use (known or not). */
    public static List<String> fieldsIn(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(text == null ? "" : text);
        while (m.find()) out.add(m.group(1).strip());
        return new ArrayList<>(out);
    }

    /** Rejects unknown fields and stray braces in any of the texts; the message names every offender. */
    public static void validate(String... texts) {
        Set<String> unknown = new LinkedHashSet<>();
        for (String t : texts) {
            String text = t == null ? "" : t;
            for (String f : fieldsIn(text)) if (!NAMES.contains(f)) unknown.add(f);
            String rest = TOKEN.matcher(text).replaceAll("");
            if (rest.contains("{{") || rest.contains("}}")) {
                throw new UserException(HttpStatus.BAD_REQUEST, "The text has a \"{{\" or \"}}\" without its pair. Merge fields look like {{employee.name}}.");
            }
        }
        if (!unknown.isEmpty()) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Unknown merge field" + (unknown.size() == 1 ? " " : "s ")
                    + unknown.stream().map(f -> "{{" + f + "}}").collect(Collectors.joining(", ")) + ". Use only the fields listed beside the editor.");
        }
    }

    /** Whether any of the texts uses a salary field. */
    public static boolean usesSalary(String... texts) {
        for (String t : texts) for (String f : fieldsIn(t)) if (SALARY_NAMES.contains(f)) return true;
        return false;
    }

    /** Fills the placeholders. {@code values} maps field name to text; null / blank means "no value". */
    public static String render(String text, Map<String, String> values, Mode mode) {
        validate(text);
        List<String> missing = new ArrayList<>();
        for (String f : fieldsIn(text)) {
            String v = values.get(f);
            if (v == null || v.isBlank()) missing.add(f);
        }
        if (!missing.isEmpty()) throw new MissingFieldsException(missing);
        StringBuilder out = new StringBuilder();
        Matcher m = TOKEN.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(lit(text.substring(last, m.start()), mode));
            out.append(lit(clean(values.get(m.group(1).strip())), mode));
            last = m.end();
        }
        out.append(lit(text.substring(last), mode));
        return out.toString();
    }

    /** Values are single-line: control characters (new lines included) become spaces. */
    static String clean(String v) {
        return v.replaceAll("\\p{Cntrl}", " ").strip();
    }

    private static String lit(String s, Mode mode) {
        return mode == Mode.HTML ? escapeHtml(s) : s;
    }

    public static String escapeHtml(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
