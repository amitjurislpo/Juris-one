package com.jurislpo.jurisone.exception;

import org.springframework.http.HttpStatus;

/** A problem the user can fix (validation, business rule) — today's {@code UserError}. 422 by default. */
public class UserException extends ApiException {
    public UserException(String message) { super(HttpStatus.UNPROCESSABLE_ENTITY, message); }
    public UserException(HttpStatus status, String message) { super(status, message); }
}
