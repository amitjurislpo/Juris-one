package com.jurislpo.jurisone.common;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Error bodies in RFC 7807 / 9457 form ({@code application/problem+json}):
 * {@code {"type":"about:blank","title","status","detail","instance","code","errors"?}}. {@code detail} is the message
 * the user sees (same texts as before); {@code code} is stable for programs.
 */
public final class Problems {
    private Problems() {}

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String CONFLICT = "CONFLICT";
    public static final String GONE = "GONE";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String BUSINESS_RULE = "BUSINESS_RULE";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String INTERNAL = "INTERNAL_ERROR";

    public record FieldError(String field, String message) {}

    static String defaultCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> VALIDATION_FAILED;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 410 -> GONE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> BUSINESS_RULE;
            case 429 -> RATE_LIMITED;
            default -> status.is5xxServerError() ? INTERNAL : "ERROR";
        };
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail, String instance) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        HttpStatus known = HttpStatus.resolve(status.value());
        p.setTitle(known != null ? known.getReasonPhrase() : "Error");
        if (instance != null) p.setInstance(URI.create(instance));
        p.setProperty("code", code != null ? code : defaultCode(status));
        return p;
    }

    public static ProblemDetail of(HttpStatusCode status, String detail, String instance) {
        return of(status, defaultCode(status), detail, instance);
    }

    public static ProblemDetail withErrors(ProblemDetail p, List<FieldError> errors) {
        if (errors != null && !errors.isEmpty()) p.setProperty("errors", errors);
        return p;
    }
}
