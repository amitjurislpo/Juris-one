package com.jurislpo.jurisone.exception;

import org.springframework.http.HttpStatus;

/** Missing permission — today's {@code ForbiddenError}. */
public class ForbiddenException extends ApiException {
    public static final String DEFAULT = "You don't have permission to do that.";

    public ForbiddenException() { super(HttpStatus.FORBIDDEN, DEFAULT); }
    public ForbiddenException(String message) { super(HttpStatus.FORBIDDEN, message); }
}
