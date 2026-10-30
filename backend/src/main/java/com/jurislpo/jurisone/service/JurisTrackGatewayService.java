package com.jurislpo.jurisone.service;

import static com.jurislpo.jurisone.config.ApiPaths.PREFIX;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.config.JurisTrackProperties;
import com.jurislpo.jurisone.engine.JurisTrackShifts;
import com.jurislpo.jurisone.engine.Nav;
import com.jurislpo.jurisone.exception.ApiException;
import com.jurislpo.jurisone.security.AuditTrail;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.security.JurisTrackTokenIssuer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The gateway to JurisTrack: forwards one browser request to {@code <base-url>/api/<path>} with the same method, query string and
 * body, signed in as the JurisOne employee (a fresh token per request, see {@link JurisTrackTokenIssuer}), and returns JurisTrack's
 * status, body and content type unchanged. The browser's cookies and Authorization header are never forwarded, tokens are never
 * logged. Who may call it (page permission, plan or grant) is decided by the controller before this runs.
 */
@Service
public class JurisTrackGatewayService {

    static final int MAXIMUM_BODY_BYTES = 64 * 1024;
    private static final Logger log = LoggerFactory.getLogger(JurisTrackGatewayService.class);
    private static final String REQUEST_ID_HEADER = AuditTrail.REQUEST_ID_HEADER;
    /** The only browser headers passed on. Everything else (Cookie, Authorization, Origin, Host, ...) stops here. */
    private static final List<String> FORWARDED_REQUEST_HEADERS = List.of("Content-Type", "Accept", "Accept-Language", "If-Match", "If-None-Match", "Idempotency-Key");
    /** The only JurisTrack headers passed back. Set-Cookie and the like stop here. */
    private static final List<String> RETURNED_RESPONSE_HEADERS = List.of("Content-Type", "Content-Disposition", "Content-Language", "Cache-Control", "ETag", "Retry-After");

    private static final String TIME_EVENTS_PATH = "me/time-events";
    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(5);

    private final JurisTrackProperties properties;
    private final JurisTrackTokenIssuer tokenIssuer;
    private final JdbcTemplate jdbc;
    private final SubscriptionService subscriptions;
    private final MyAttendanceService attendance;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public JurisTrackGatewayService(JurisTrackProperties properties, JurisTrackTokenIssuer tokenIssuer, JdbcTemplate jdbc, SubscriptionService subscriptions, MyAttendanceService attendance) {
        this.properties = properties;
        this.subscriptions = subscriptions;
        this.attendance = attendance;
        this.tokenIssuer = tokenIssuer;
        this.jdbc = jdbc;
    }

    /** Forwards {@code request} ({@code /api/juris-track/<path>}) for {@code user} and writes JurisTrack's answer to {@code response}. */
    public void forward(CurrentUser user, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!properties.enabled() || properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "JURISTRACK_DISABLED", "Juris Track isn't available right now.");
        }
        String path = targetPath(request.getRequestURI());
        String query = request.getQueryString();
        URI target = URI.create(properties.baseUrl() + "/api/v1/" + path + (query == null || query.isEmpty() ? "" : "?" + query));
        String requestId = response.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        response.setHeader(REQUEST_ID_HEADER, requestId);
        AuditTrail.hint(request, "target", "juristrack");

        // JurisTrack refuses more than 64 KB anyway: never buffer more than that for a browser (a huge body would fill this server's memory)
        if (request.getContentLengthLong() > MAXIMUM_BODY_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "That request is too large.");
        byte[] body = request.getInputStream().readNBytes(MAXIMUM_BODY_BYTES + 1);
        if (body.length > MAXIMUM_BODY_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "That request is too large.");
        HttpRequest.Builder outgoing = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30))
                .method(request.getMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", "Bearer " + tokenIssuer.issue(subjectOf(user)))
                .header(REQUEST_ID_HEADER, requestId)
                .header("X-Forwarded-For", forwardedFor(request));
        for (String name : FORWARDED_REQUEST_HEADERS) {
            String value = request.getHeader(name);
            if (value != null) outgoing.header(name, value);
        }

        HttpResponse<byte[]> answer;
        try {
            answer = client.send(outgoing.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            log.warn("[juristrack] no answer in time for {} /api/{} (request {})", request.getMethod(), path, requestId);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "JURISTRACK_UNAVAILABLE", "Juris Track didn't answer in time. Please try again.");
        } catch (IOException e) {
            log.warn("[juristrack] unreachable for {} /api/{} (request {}): {}", request.getMethod(), path, requestId, e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "JURISTRACK_UNAVAILABLE", "Juris Track isn't reachable right now. Please try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "JURISTRACK_UNAVAILABLE", "Juris Track didn't answer. Please try again.");
        }
        if (answer.statusCode() == 401) {
            // JurisTrack refused OUR token (clock skew, key or issuer mismatch, a repeated jti): that is not the browser's session ending, so it must not sign the user out
            log.warn("[juristrack] JurisTrack refused the token for {} /api/{} (request {})", request.getMethod(), path, requestId);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "JURISTRACK_AUTH_FAILED", "Juris Track couldn't verify this request. Please try again in a moment.");
        }
        response.setStatus(answer.statusCode());
        for (String name : RETURNED_RESPONSE_HEADERS) answer.headers().firstValue(name).ifPresent(value -> response.setHeader(name, value));
        if (!"HEAD".equals(request.getMethod())) response.getOutputStream().write(answer.body());
        if (answer.statusCode() / 100 == 2 && "POST".equals(request.getMethod()) && TIME_EVENTS_PATH.equals(path)) mirrorTimeEventAsPunch(user, body, RequestMeta.of(request).ipAddress());
    }

    /**
     * JurisTrack accepted a time event from the browser: START checks the employee in, END checks them out (idempotent, best effort).
     * Never changes the answer already sent to the browser and never fails it; BREAK and RESUME have no punch.
     */
    private void mirrorTimeEventAsPunch(CurrentUser user, byte[] requestBody, String ipAddress) {
        try {
            JsonNode type = json.readTree(requestBody).get("type");
            String eventType = type == null || !type.isTextual() ? "" : type.asText().toUpperCase(Locale.ROOT);
            if ("START".equals(eventType)) attendance.recordPunchFromJurisTrack(user, "IN", ipAddress);
            else if ("END".equals(eventType)) attendance.recordPunchFromJurisTrack(user, "OUT", ipAddress);
        } catch (IOException | RuntimeException e) {
            log.warn("[juristrack] could not record the attendance punch for a time event of employee {}: {}", user.id(), e.getClass().getSimpleName());
        }
    }

    /**
     * After a successful check-in ({@code "IN"}) or check-out ({@code "OUT"}) on Home: starts or ends the employee's JurisTrack workday,
     * when the business has the module and the gateway is on. Best effort with a short timeout: a JurisTrack problem is logged
     * (never with a token) and never reaches the punch. A check-out while on a break sends RESUME first, then END.
     */
    public void mirrorPunchAsTimeEvent(CurrentUser user, String punchKind, String ipAddress) {
        if (!properties.enabled() || properties.baseUrl() == null || properties.baseUrl().isBlank() || !tokenIssuer.isEnabled()) return;
        try {
            if (!subscriptions.activeModules(user.businessId()).contains(Nav.JURIS_TRACK)) return;
            if ("IN".equals(punchKind)) {
                sendTimeEvent(user, "START", ipAddress);
            } else if ("OUT".equals(punchKind)) {
                HttpResponse<byte[]> ending = sendTimeEvent(user, "END", ipAddress);
                if (ending.statusCode() == 409 && new String(ending.body(), StandardCharsets.UTF_8).contains("BREAK_IN_PROGRESS")) {
                    sendTimeEvent(user, "RESUME", ipAddress);
                    sendTimeEvent(user, "END", ipAddress);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[juristrack] interrupted while syncing the {} punch of employee {}", punchKind, user.id());
        } catch (IOException | RuntimeException e) {
            log.warn("[juristrack] could not sync the {} punch of employee {}: {}", punchKind, user.id(), e.getClass().getSimpleName());
        }
    }

    private HttpResponse<byte[]> sendTimeEvent(CurrentUser user, String type, String ipAddress) throws IOException, InterruptedException {
        HttpRequest.Builder outgoing = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/api/v1/" + TIME_EVENTS_PATH)).timeout(SYNC_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"" + type + "\"}"))
                .header("Authorization", "Bearer " + tokenIssuer.issue(subjectOf(user)))
                .header("Content-Type", "application/json")
                .header(REQUEST_ID_HEADER, UUID.randomUUID().toString());
        if (ipAddress != null) outgoing.header("X-Forwarded-For", ipAddress);
        HttpResponse<byte[]> answer = client.send(outgoing.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (answer.statusCode() / 100 != 2) log.warn("[juristrack] time event {} for employee {} answered {}", type, user.id(), answer.statusCode());
        return answer;
    }

    /** The part after {@code /api/juris-track/}, still percent-encoded as the browser sent it; refused when it could climb out of {@code /api/}. */
    static String targetPath(String requestUri) {
        // JurisTrack's paths are plain words and ids: any percent-encoding (a way to hide "integration", ".." or "/") is refused
        String prefix = PREFIX + "/juris-track/";
        String path = requestUri.startsWith(prefix) ? requestUri.substring(prefix.length()) : "";
        String lower = path.toLowerCase(Locale.ROOT);
        if (path.isEmpty() || lower.contains("..") || lower.contains("%") || lower.contains(";") || lower.contains("\\") || lower.contains("//")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That address isn't valid.");
        }
        if (lower.equals("integration") || lower.startsWith("integration/")) throw new ApiException(HttpStatus.NOT_FOUND, "Not found."); // JurisOne's own service endpoints, never for a browser
        return path;
    }

    private static String forwardedFor(HttpServletRequest request) {
        String earlier = request.getHeader("X-Forwarded-For");
        return earlier == null || earlier.isBlank() ? request.getRemoteAddr() : earlier + ", " + request.getRemoteAddr();
    }

    /**
     * The claims about the signed-in employee. Track role: MANAGER when the employee has at least one active direct report, else EMPLOYEE.
     * The manager is the employee's assigned reporting manager in JurisOne.
     * The shift code is the employee's JurisOne shift as JurisTrack knows it (see {@link JurisTrackShifts#trackCode}), null without a shift.
     */
    private JurisTrackTokenIssuer.Subject subjectOf(CurrentUser user) {
        return jdbc.query("""
                SELECT e.reporting_manager_id, b.name AS business_name, s.code AS shift_code, s.region::text AS shift_region, e.time_zone
                FROM employees e JOIN businesses b ON b.id = e.business_id LEFT JOIN shifts s ON s.id = e.shift_id WHERE e.id = ?""",
                resultSet -> {
                    resultSet.next();
                    return new JurisTrackTokenIssuer.Subject(user.id(), user.email(), user.name(), user.businessId(), resultSet.getString("business_name"),
                            (UUID) resultSet.getObject("reporting_manager_id"), user.hasReports() ? JurisTrackTokenIssuer.TrackRole.MANAGER : JurisTrackTokenIssuer.TrackRole.EMPLOYEE,
                            resultSet.getString("shift_code") == null ? null
                                    : JurisTrackShifts.trackCode(resultSet.getString("shift_code"), resultSet.getString("shift_region"), resultSet.getString("time_zone")));
                }, user.id());
    }
}
