package com.jurislpo.jurisone.common;

import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.validation.ConstraintViolationException;

/**
 * Maps errors to the {@code {ok:false, error}} body with the messages the Next.js app uses today
 * (src/lib/action.ts {@code run}): user errors as-is, the first validation message, "That already exists."
 * for unique violations, and a generic message for anything unexpected.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    static final String INVALID = "Some details are invalid.";
    static final String DUPLICATE = "That already exists.";
    static final String GENERIC = "Something went wrong. Please try again.";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResult<Void>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(ApiResult.fail(e.getMessage()));
    }

    /** {@code @PreAuthorize} denial: 401 when signed out, 403 otherwise. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResult<Void>> denied(AccessDeniedException e) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a instanceof AnonymousAuthenticationToken || !a.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(UnauthenticatedException.MESSAGE));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(ForbiddenException.DEFAULT));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResult<Void>> invalid(MethodArgumentNotValidException e) {
        // first issue only, like zod's issues[0]
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe != null && fe.getDefaultMessage() != null ? fe.getDefaultMessage()
                : e.getBindingResult().getAllErrors().stream().findFirst().map(err -> err.getDefaultMessage()).orElse(INVALID);
        if (msg == null) msg = INVALID;
        return ResponseEntity.badRequest().body(ApiResult.fail(msg));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResult<Void>> invalid(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream().findFirst().map(v -> v.getMessage()).orElse(INVALID);
        return ResponseEntity.badRequest().body(ApiResult.fail(msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResult<Void>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiResult.fail(INVALID));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResult<Void>> integrity(DataIntegrityViolationException e) {
        if (e.getMostSpecificCause() instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResult.fail(DUPLICATE));
        }
        log.error("[api] data integrity", e);
        return ResponseEntity.internalServerError().body(ApiResult.fail(GENERIC));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResult<Void>> unexpected(Exception e) {
        // Spring's own 404 / 405 / 415 … keep their status
        if (e instanceof org.springframework.web.ErrorResponse er && !er.getStatusCode().is5xxServerError()) {
            String msg = er.getStatusCode().value() == 404 ? "Not found." : INVALID;
            return ResponseEntity.status(er.getStatusCode()).body(ApiResult.fail(msg));
        }
        log.error("[api]", e);
        return ResponseEntity.internalServerError().body(ApiResult.fail(GENERIC));
    }
}
