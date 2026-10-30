package com.jurislpo.jurisone.security;

import static com.jurislpo.jurisone.config.ApiPaths.PREFIX;
import static com.jurislpo.jurisone.config.ApiPaths.PREFIX_SLASH;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the request-level audit trail records, in one place: which requests are logged, how a path is stored (secrets masked) and
 * the hints a controller can attach to its own request row.
 */
public final class AuditTrail {
    private AuditTrail() {}

    /** Request attribute: the signed-in user (set by the JWT filter) so the trail knows the actor after the security chain has ended. */
    public static final String USER_ATTR = "jurisone.audit.user";
    /** Request attribute: a {@code Map<String, Object>} of extra facts stored in the request row's {@code after} (never secrets). */
    public static final String HINT_ATTR = "jurisone.audit.hint";
    /** Response header carrying this request's id (the same id is stored in the audit row). */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Reads that are logged although they change nothing: exports, downloads and anything that reveals personal data. */
    private static final Pattern[] SENSITIVE_GETS = {
            Pattern.compile("^" + PREFIX + "/.*/export(/.*)?$"),
            Pattern.compile("^" + PREFIX + "/.*/pdf$"),
            Pattern.compile("^" + PREFIX + "/.*/download(/.*)?$"),
            Pattern.compile("^" + PREFIX + "/people/[^/]+/contact$"),
            Pattern.compile("^" + PREFIX + "/people/[^/]+/onboarding(/.*)?$"),   // an employee's onboarding data and files, read by HR
            Pattern.compile("^" + PREFIX + "/people/[^/]+/documents/[^/]+/file$"),
            Pattern.compile("^" + PREFIX + "/people/[^/]+$"),            // an employee's profile (masked identity numbers, salary for finance)
            Pattern.compile("^" + PREFIX + "/letters/[^/]+$"),           // one issued letter's text
            Pattern.compile("^" + PREFIX + "/letters/mine$"),
            Pattern.compile("^" + PREFIX + "/payslips/.*$"),
            Pattern.compile("^" + PREFIX + "/payroll/.*/(files|payslips)(/.*)?$"),
            Pattern.compile("^" + PREFIX + "/profile$"),
            Pattern.compile("^" + PREFIX + "/proofs/.*/(file|document)$"),
            Pattern.compile("^" + PREFIX + "/tax/.*/(file|document)$"),
            Pattern.compile("^" + PREFIX + "/tasks/[^/]+/attachments/[^/]+/file$"),
            Pattern.compile("^" + PREFIX + "/platform/audit(/.*)?$"),
            Pattern.compile("^" + PREFIX + "/audit(/.*)?$"),
    };

    /** Written through its own endpoint (the page-view row replaces the request row). */
    private static final String[] SKIP = {PREFIX + "/audit/page-views"};

    public static boolean isRecorded(String method, String path) {
        if (!path.startsWith(PREFIX_SLASH)) return false;
        for (String skipPath : SKIP) if (path.equals(skipPath)) return false;
        if ("HEAD".equals(method) || "OPTIONS".equals(method)) return false;
        if (!"GET".equals(method)) return true;
        for (Pattern pattern : SENSITIVE_GETS) if (pattern.matcher(path).matches()) return true;
        return false;
    }

    /** The path as stored, length capped. */
    public static String storedPath(String path) {
        return path.length() > 300 ? path.substring(0, 300) : path;
    }

    /** a***@example.com: enough to spot who tried, not enough to harvest addresses. */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "";
        String trimmedEmail = email.trim();
        int atIndex = trimmedEmail.indexOf('@');
        if (atIndex < 1) return "***";
        return trimmedEmail.charAt(0) + "***" + trimmedEmail.substring(atIndex);
    }

    /** Adds a fact to this request's audit row. */
    @SuppressWarnings("unchecked")
    public static void hint(HttpServletRequest request, String key, Object value) {
        Object hint = request.getAttribute(HINT_ATTR);
        Map<String, Object> view = hint instanceof Map<?, ?> ? (Map<String, Object>) hint : new LinkedHashMap<>();
        view.put(key, value);
        request.setAttribute(HINT_ATTR, view);
    }
}
