package com.jurislpo.jurisone.common;

import org.springframework.http.HttpStatus;

/** An error whose message is shown to the user as-is. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() { return status; }
}
