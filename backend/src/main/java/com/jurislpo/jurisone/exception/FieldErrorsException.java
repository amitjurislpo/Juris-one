package com.jurislpo.jurisone.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 422 with the problem-details {@code errors} map: JSON path of the field to its message (the onboarding wizard's validation). */
public class FieldErrorsException extends ApiException {
    public static final String MESSAGE = "Some information is missing or invalid.";

    private final Map<String, String> errors;

    public FieldErrorsException(Map<String, String> errors) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, Problems.VALIDATION_FAILED, MESSAGE);
        this.errors = new LinkedHashMap<>(errors);
    }

    public Map<String, String> errors() { return errors; }
}
