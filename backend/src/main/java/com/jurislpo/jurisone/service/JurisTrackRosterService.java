package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.config.JurisTrackProperties;
import com.jurislpo.jurisone.engine.JurisTrackShifts;
import com.jurislpo.jurisone.engine.JurisTrackShifts.TrackShift;
import com.jurislpo.jurisone.engine.Nav;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.exception.ApiException;
import com.jurislpo.jurisone.repository.JurisTrackRosterRepository;
import com.jurislpo.jurisone.repository.JurisTrackRosterRepository.BusinessRow;
import com.jurislpo.jurisone.repository.JurisTrackRosterRepository.EmployeeRow;
import com.jurislpo.jurisone.security.AuditTrail;
import com.jurislpo.jurisone.security.JurisTrackTokenIssuer;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Pushes a business's roster (its shifts and employees) to JurisTrack: {@code PUT <base-url>/api/integration/employees} with a service token
 * ({@link JurisTrackTokenIssuer#issueService}). One business per call, the whole roster each time (JurisTrack's side is idempotent), so a manager
 * sees every report, a shift matches and a deactivation reaches JurisTrack. The dated shift assignments (V36) are not sent: only each employee's own shift goes, and {@code schedule} is never sent.
 *
 * <p>Employees of every status go, so that a past employee arrives as {@code isActive:false}; people without a usable email are left out.
 * {@code isActive} is "not a past employee" (probation and onboarding people work and track time). {@code role} is MANAGER only for a holder of the
 * JurisTrack manager permission (the user token's rule; having reports alone is not enough), else EMPLOYEE. The roster never sends ADMIN: the owner's own
 * token does (JurisTrack refuses to make anybody an owner through the roster).
 *
 * <p>Shifts: only those assigned to a sent employee, converted to the business time zone (see {@link JurisTrackShifts}). JurisOne's {@code grace_minutes}
 * is a late-arrival allowance, not a window around the shift, so no early/late minutes are sent and JurisTrack's default applies.
 *
 * <p>The module is active when the business's plan or a grant includes it and the gateway is on. An ended subscription keeps the module view-only
 * (see {@link SubscriptionService#activeModules}): the roster is still synced, so deactivations still arrive.
 */
@Service
public class JurisTrackRosterService {

    private static final Logger log = LoggerFactory.getLogger(JurisTrackRosterService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String UNAVAILABLE = "JURISTRACK_UNAVAILABLE";

    public record ShiftPayload(String code, String name, String startTime, String endTime, Integer earlyStartMinutes, Integer lateEndMinutes) {}

    public record EmployeePayload(String employeeId, String email, String firstName, String lastName, String employeeCode, String role, String managerEmployeeId,
                                  boolean isActive, String shiftCode) {}

    public record RosterPayload(List<ShiftPayload> shifts, List<EmployeePayload> employees) {}

    private final JurisTrackProperties properties;
    private final JurisTrackTokenIssuer tokenIssuer;
    private final JurisTrackRosterRepository repo;
    private final SubscriptionService subscriptions;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();

    public JurisTrackRosterService(JurisTrackProperties properties, JurisTrackTokenIssuer tokenIssuer, JurisTrackRosterRepository repo, SubscriptionService subscriptions, AuditService audit) {
        this.audit = audit;
        this.properties = properties;
        this.tokenIssuer = tokenIssuer;
        this.repo = repo;
        this.subscriptions = subscriptions;
    }

    /** The gateway is on and configured. */
    public boolean isGatewayOn() {
        return properties.enabled() && properties.baseUrl() != null && !properties.baseUrl().isBlank() && tokenIssuer.isEnabled();
    }

    /**
     * The gateway is on and the business (not the platform company) has the Juris Track module through its plan or a grant, and is not suspended
     * (a suspended business sends nothing to Juris Track). An ended subscription still syncs, so that deactivations arrive.
     */
    public boolean isModuleActive(UUID businessId) {
        if (!isGatewayOn() || businessId == null) return false;
        return TenantContext.call(null, () -> repo.findBusiness(businessId).isPresent() && !repo.isSuspended(businessId)
                && subscriptions.activeModules(businessId).contains(Nav.JURIS_TRACK));
    }

    /** Every customer business that has the module whatever its status, suspended ones included (they still have to be told their status). */
    public List<UUID> businessesWithModuleAnyStatus() {
        return TenantContext.call(null, () -> repo.findCustomerBusinessIds().stream()
                .filter(id -> isGatewayOn() && subscriptions.activeModules(id).contains(Nav.JURIS_TRACK)).toList());
    }

    /** Every customer business that has the module (for the startup and the scheduled full resync). */
    public List<UUID> businessesWithModule() {
        return TenantContext.call(null, () -> repo.findCustomerBusinessIds().stream().filter(this::isModuleActive).toList());
    }

    /** The body for the business, built from the database as it is now. */
    public RosterPayload buildPayload(UUID businessId) {
        return TenantContext.call(null, () -> {
            BusinessRow business = repo.findBusiness(businessId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Business not found."));
            return buildPayload(business, repo.findEmployees(businessId), Instant.now());
        });
    }

    static RosterPayload buildPayload(BusinessRow business, List<EmployeeRow> employees, Instant now) {
        LocalDate today = now.atZone(ZoneId.of(business.timeZone())).toLocalDate();
        Map<String, ShiftPayload> shifts = new LinkedHashMap<>();
        List<EmployeePayload> people = new ArrayList<>();
        for (EmployeeRow employee : employees) {
            if (employee.email() == null || !PeopleRules.EMAIL_RE.matcher(employee.email().trim()).matches()) continue;
            String shiftCode = null;
            if (employee.shiftCode() != null) {
                TrackShift shift = JurisTrackShifts.convert(employee.shiftCode(), employee.shiftName(), employee.shiftStart(), employee.shiftEnd(), employee.shiftRegion(),
                        employee.timeZone(), business.timeZone(), today);
                if (shift != null) {
                    shifts.putIfAbsent(shift.code(), new ShiftPayload(shift.code(), shift.name(), shift.startTime(), shift.endTime(), null, null));
                    shiftCode = shift.code();
                }
            }
            String fullName = employee.fullName() == null ? "" : employee.fullName().trim();
            boolean hasNameParts = employee.firstName() != null && !employee.firstName().isBlank();
            String[] words = fullName.split("\\s+");
            String firstName = hasNameParts ? employee.firstName().trim() : words[0];
            String lastName = hasNameParts ? (employee.lastName() == null ? "" : employee.lastName().trim()) : words.length > 1 ? words[words.length - 1] : "";
            people.add(new EmployeePayload(employee.id().toString(), employee.email().trim().toLowerCase(), firstName, lastName, employee.code(),
                    JurisTrackTokenIssuer.TrackRole.of(false, employee.isJurisTrackManager()).name(),
                    employee.managerId() == null ? null : employee.managerId().toString(), !"EXITED".equals(employee.status()), shiftCode));
        }
        return new RosterPayload(List.copyOf(shifts.values()), people);
    }

    /**
     * Sends the business's roster and returns JurisTrack's summary ({@code data} of its answer: shifts, employees, scheduleEntries, skipped).
     * Throws 404 for an unknown business, 409 {@code JURISTRACK_NOT_ACTIVE} when the gateway is off or the business has no module, 502 when
     * JurisTrack is unreachable, too slow or answers with an error. Never logs the token.
     */
    public Map<String, Object> syncBusiness(UUID businessId) {
        BusinessRow business = TenantContext.call(null, () -> repo.findBusiness(businessId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Business not found.")));
        if (!isModuleActive(businessId)) throw new ApiException(HttpStatus.CONFLICT, "JURISTRACK_NOT_ACTIVE", "This business doesn't have Juris Track, or Juris Track isn't connected.");
        RosterPayload payload = buildPayload(businessId);
        String requestId = UUID.randomUUID().toString();
        HttpResponse<byte[]> answer;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/api/v1/integration/employees")).timeout(TIMEOUT)
                    .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .header("Authorization", "Bearer " + tokenIssuer.issueService(business.id(), business.name()))
                    .header("Content-Type", "application/json").header("Accept", "application/json").header(AuditTrail.REQUEST_ID_HEADER, requestId).build();
            answer = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            log.warn("[juristrack] roster of business {} not accepted in time (request {})", businessId, requestId);
            throw new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE, "Juris Track didn't answer in time.");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not write the roster", e);
        } catch (IOException e) {
            log.warn("[juristrack] unreachable for the roster of business {} (request {}): {}", businessId, requestId, e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE, "Juris Track isn't reachable right now.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, UNAVAILABLE, "Juris Track didn't answer.");
        }
        if (answer.statusCode() / 100 != 2) {
            log.warn("[juristrack] roster of business {} refused with {} (request {})", businessId, answer.statusCode(), requestId);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "JURISTRACK_REFUSED", "Juris Track refused the roster (HTTP " + answer.statusCode() + ").");
        }
        log.info("[juristrack] roster of business {} synced: {} shifts, {} employees sent (request {})", businessId, payload.shifts().size(), payload.employees().size(), requestId);
        return summaryOf(answer.body());
    }

    /**
     * Tells JurisTrack whether the business is suspended, as it is now ({@code PUT /api/v1/integration/business/status}, a service token of its own
     * scope, one per call). Idempotent on both sides. A 404 means JurisTrack does not know the business yet (nothing to do: it starts active when
     * its first user arrives, and nobody of a suspended business can arrive). Throws 502 when JurisTrack is unreachable, too slow or refuses.
     * Audited on both ends; never logs the token.
     */
    public void pushStatus(UUID businessId) {
        BusinessRow business = TenantContext.call(null, () -> repo.findBusiness(businessId).orElse(null));
        if (business == null || !isGatewayOn()) return;
        boolean isSuspended = TenantContext.call(null, () -> repo.isSuspended(businessId));
        String requestId = UUID.randomUUID().toString();
        HttpResponse<byte[]> answer;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/api/v1/integration/business/status")).timeout(TIMEOUT)
                    .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("isSuspended", isSuspended, "reason", "jurisone"))))
                    .header("Authorization", "Bearer " + tokenIssuer.issueService(business.id(), business.name(), JurisTrackTokenIssuer.STATUS_SCOPE))
                    .header("Content-Type", "application/json").header("Accept", "application/json").header(AuditTrail.REQUEST_ID_HEADER, requestId).build();
            answer = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw failedStatusPush(businessId, isSuspended, "timeout", requestId);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not write the status", e);
        } catch (IOException e) {
            throw failedStatusPush(businessId, isSuspended, "unreachable", requestId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failedStatusPush(businessId, isSuspended, "interrupted", requestId);
        }
        int code = answer.statusCode();
        if (code / 100 != 2 && code != 404) throw failedStatusPush(businessId, isSuspended, "http_" + code, requestId);
        log.info("[juristrack] status of business {} sent: {} (answer {}, request {})", businessId, isSuspended ? "suspended" : "active", code, requestId);
        audit.log(null, "juristrack.status_synced", "business", businessId.toString(), null, Map.of("isSuspended", isSuspended, "answer", code, "requestId", requestId), null);
    }

    private ApiException failedStatusPush(UUID businessId, boolean isSuspended, String reason, String requestId) {
        log.warn("[juristrack] status of business {} not delivered: {} (request {})", businessId, reason, requestId);
        audit.log(null, "juristrack.status_sync_failed", "business", businessId.toString(), null, Map.of("isSuspended", isSuspended, "reason", reason, "requestId", requestId), null);
        return new ApiException(HttpStatus.BAD_GATEWAY, "JURISTRACK_UNAVAILABLE", "Juris Track didn't take the business status (" + reason + ").");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summaryOf(byte[] body) {
        try {
            JsonNode data = json.readTree(body).path("data");
            return data.isObject() ? json.convertValue(data, Map.class) : Map.of();
        } catch (IOException | IllegalArgumentException e) {
            return Map.of();
        }
    }
}
