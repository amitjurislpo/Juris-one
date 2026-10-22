package com.jurislpo.jurisone.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The request-level audit trail: every write request (POST / PUT / PATCH / DELETE) to /api and every sensitive read (see
 * {@link AuditTrail}) leaves one row: who (null when not signed in), which business, method, path, how it ended (status), how long it
 * took, from where. It wraps the whole security chain, so requests turned away by a later filter (401, 402, 403, 428, blocked origin)
 * are recorded too, and it writes on its own connection in a {@code finally}, so a handler that throws or a transaction that rolls back
 * cannot take the row with it. A failed audit write is logged as an ERROR and counted ({@link #failures()}); it never breaks the request.
 * Request bodies, cookies and tokens are never stored.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50) // ahead of Spring Security's chain
public class AuditTrailFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditTrailFilter.class);
    private static final AtomicLong FAILURES = new AtomicLong();

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Counter failureCounter;

    public AuditTrailFilter(JdbcTemplate jdbc, ObjectMapper json, ObjectProvider<MeterRegistry> meters) {
        this.jdbc = jdbc;
        this.json = json;
        MeterRegistry meterRegistry = meters.getIfAvailable();
        this.failureCounter = meterRegistry == null ? null : Counter.builder("jurisone.audit.write.failures").description("Audit rows that could not be written").register(meterRegistry);
    }

    /** How many audit rows could not be written since start (should stay 0). */
    public static long failures() {
        return FAILURES.get();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AuditTrail.isRecorded(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        Instant instant = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String requestId = UUID.randomUUID().toString();
        response.setHeader(AuditTrail.REQUEST_ID_HEADER, requestId);
        int failedStatus = 0;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failedStatus = 500; // the container answers 500 for an exception nobody handled
            throw e;
        } finally {
            int status = failedStatus != 0 ? failedStatus : response.getStatus();
            record(request, instant, requestId, status, (int) ((System.nanoTime() - startNanos) / 1_000_000));
        }
    }

    private void record(HttpServletRequest request, Instant instant, String requestId, int status, int durationMillis) {
        try {
            CurrentUser user = request.getAttribute(AuditTrail.USER_ATTR) instanceof CurrentUser currentUser ? currentUser : null;
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", status);
            after.put("durationMs", durationMillis);
            if (request.getAttribute(AuditTrail.HINT_ATTR) instanceof Map<?, ?> hint) hint.forEach((k, v) -> after.put(String.valueOf(k), v));
            String userAgent = request.getHeader("user-agent");
            String forwardedFor = request.getHeader("x-forwarded-for");
            String ipAddress = forwardedFor != null && !forwardedFor.isBlank() ? forwardedFor.split(",")[0].trim() : request.getHeader("x-real-ip");
            String path = AuditTrail.storedPath(request.getRequestURI());
            jdbc.update("""
                    INSERT INTO audit_log (id, actor_id, business_id, action, entity, entity_id, "after", at, ip, method, path, status, duration_ms, user_agent, request_id)
                    VALUES (?, ?, ?, ?, 'api', ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    UUID.randomUUID(), user == null ? null : user.id(), user == null ? null : user.businessId(), "request." + request.getMethod(), path,
                    json.writeValueAsString(after), Timestamp.from(instant), ipAddress == null || ipAddress.isEmpty() ? null : ipAddress, request.getMethod(), path, status, durationMillis,
                    userAgent == null ? null : userAgent.substring(0, Math.min(400, userAgent.length())), requestId);
        } catch (Exception e) {
            FAILURES.incrementAndGet();
            if (failureCounter != null) failureCounter.increment();
            log.error("[audit] could not record {} {} (request {}): the audit trail has a gap", request.getMethod(), request.getRequestURI(), requestId, e);
        }
    }
}
