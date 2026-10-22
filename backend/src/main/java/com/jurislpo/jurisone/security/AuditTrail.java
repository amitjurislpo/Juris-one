package com.jurislpo.jurisone.security;

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
            Pattern.compile("^/api/.*/export(/.*)?$"),
            Pattern.compile("^/api/.*/pdf$"),
            Pattern.compile("^/api/.*/download(/.*)?$"),
            Pattern.compile("^/api/people/[^/]+/contact$"),
            Pattern.compile("^/api/people/[^/]+$"),            // an employee's profile (masked identity numbers, salary for finance)
            Pattern.compile("^/api/letters/[^/]+$"),           // one issued letter's text
            Pattern.compile("^/api/letters/mine$"),
            Pattern.compile("^/api/payslips/.*$"),
            Pattern.compile("^/api/payroll/.*/(files|payslips)(/.*)?$"),
            Pattern.compile("^/api/profile$"),
            Pattern.compile("^/api/proofs/.*/(file|document)$"),
            Pattern.compile("^/api/tax/.*/(file|document)$"),
            Pattern.compile("^/api/platform/audit(/.*)?$"),
            Pattern.compile("^/api/audit(/.*)?$"),
    };

    /** Written through its own endpoint (the page-view row replaces the request row). */
    private static final String[] SKIP = {"/api/audit/page-views"};

    /** A candidate's one-time link: that path segment is a credential and is stored as {token}. */
    private static final Pattern TOKEN_PATH = Pattern.compile("^(/api/public/onboardings/)[^/]+(.*)$");

    public static boolean isRecorded(String method, String path) {
        if (!path.startsWith("/api/")) return false;
        for (String skipPath : SKIP) if (path.equals(skipPath)) return false;
        if ("HEAD".equals(method) || "OPTIONS".equals(method)) return false;
        if (!"GET".equals(method)) return true;
        for (Pattern pattern : SENSITIVE_GETS) if (pattern.matcher(path).matches()) return true;
        return false;
    }

    /** The path as stored: secrets in it replaced, length capped. */
    public static String storedPath(String path) {
        String maskedPath = TOKEN_PATH.matcher(path).replaceFirst("$1{token}$2");
        return maskedPath.length() > 300 ? maskedPath.substring(0, 300) : maskedPath;
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
