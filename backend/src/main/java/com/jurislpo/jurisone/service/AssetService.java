package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.AssetRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asset tracking: HR / the owner ({@code assets.manage}) keep the register and assign / take back assets; every employee
 * sees what is assigned to them. Assigning and returning are audited, and the employee is told about an assignment.
 */
@Service
@Transactional
public class AssetService {

    public static final List<String> TYPES = List.of("LAPTOP", "DESKTOP", "PHONE", "TABLET", "MONITOR", "ACCESSORY", "OTHER");
    /** Statuses a manager may set directly; ASSIGNED only happens by assigning. */
    static final List<String> SETTABLE = List.of("AVAILABLE", "IN_REPAIR", "RETIRED");
    static final List<String> CONDITIONS = List.of("GOOD", "FAIR", "DAMAGED", "LOST");

    private final AssetRepository repo;
    private final NotificationService notifications;
    private final AuditService audit;

    public AssetService(AssetRepository repo, NotificationService notifications, AuditService audit) {
        this.repo = repo;
        this.notifications = notifications;
        this.audit = audit;
    }

    public record AssetInput(String tag, String type, String make, String model, String serial, String purchaseDate, String status, String notes) {}

    public record AssignInput(String employeeId, String assignedOn, String notes) {}

    public record ReturnInput(String returnedOn, String condition, String notes) {}

    record Valid(String tag, String type, String make, String model, String serial, LocalDate purchaseDate, String status, String notes) {}

    // ───────────────────────── employees: my assets ─────────────────────────

    /** {@code {current: [...], past: [...]}} — what is assigned to this user now, and what they have returned. */
    public Map<String, Object> mine(CurrentUser u) {
        List<Map<String, Object>> all = repo.findForEmployee(u.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", all.stream().filter(r -> r.get("returnedOn") == null).toList());
        out.put("past", all.stream().filter(r -> r.get("returnedOn") != null).toList());
        return out;
    }

    // ───────────────────────── managers ─────────────────────────

    public List<Map<String, Object>> list() {
        return repo.findAll();
    }

    public List<Map<String, Object>> assignees() {
        return repo.findAssignees();
    }

    Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = repo.findById(id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Asset not found.");
        return rows.get(0);
    }

    public Map<String, Object> detail(String idRaw) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> out = new LinkedHashMap<>(row(id));
        out.put("history", repo.findHistory(id));
        return out;
    }

    static String optional(String v, int max, String what) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        if (s.length() > max) throw new UserException(HttpStatus.BAD_REQUEST, "Keep " + what + " under " + max + " characters.");
        return s;
    }

    static LocalDate date(String v, String message) {
        try {
            return LocalDate.parse(Check.isoDate(v, message));
        } catch (DateTimeException e) {
            throw new UserException(HttpStatus.BAD_REQUEST, message);
        }
    }

    static Valid validate(AssetInput in) {
        if (in == null) throw new UserException(HttpStatus.BAD_REQUEST, "Asset details are missing.");
        String tag = Check.trimmed(in.tag(), 1, "Enter the asset tag.", 40, "Keep the asset tag under 40 characters.");
        String type = Check.oneOf(in.type() == null ? "OTHER" : in.type(), TYPES);
        LocalDate purchased = in.purchaseDate() == null || in.purchaseDate().isBlank() ? null : date(in.purchaseDate(), "Enter the purchase date as YYYY-MM-DD.");
        String status = in.status() == null ? null : Check.oneOf(in.status(), SETTABLE);
        return new Valid(tag, type, optional(in.make(), 80, "the make"), optional(in.model(), 80, "the model"), optional(in.serial(), 80, "the serial number"),
                purchased, status, optional(in.notes(), 500, "the notes"));
    }

    public String create(CurrentUser u, AssetInput in, String ip) {
        Valid v = validate(in);
        if (repo.tagTaken(v.tag(), null)) throw new UserException(HttpStatus.CONFLICT, "An asset with the tag \"" + v.tag() + "\" already exists.");
        UUID id = UUID.randomUUID();
        repo.insert(id, v.tag(), v.type(), v.make(), v.model(), v.serial(), v.purchaseDate(), v.status() == null ? "AVAILABLE" : v.status(), v.notes(), u.id());
        audit.log(u.id(), "asset.create", "asset", id.toString(), null, Map.of("tag", v.tag(), "type", v.type()), ip);
        return id.toString();
    }

    public void update(CurrentUser u, String idRaw, AssetInput in, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> a = row(id);
        Valid v = validate(in);
        if (repo.tagTaken(v.tag(), id)) throw new UserException(HttpStatus.CONFLICT, "An asset with the tag \"" + v.tag() + "\" already exists.");
        String current = (String) a.get("status");
        String status = v.status() == null ? current : v.status();
        if ("ASSIGNED".equals(current) && !"ASSIGNED".equals(status)) throw new UserException("Return the asset before changing its status.");
        repo.update(id, v.tag(), v.type(), v.make(), v.model(), v.serial(), v.purchaseDate(), status, v.notes());
        audit.log(u.id(), "asset.update", "asset", id.toString(), Map.of("tag", a.get("tag"), "status", current), Map.of("tag", v.tag(), "status", status), ip);
    }

    /** Deletes an asset that was never assigned (otherwise retire it: the history stays). */
    public void delete(CurrentUser u, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> a = row(id);
        if (repo.everAssigned(id)) throw new UserException("An asset with assignment history can't be deleted — mark it retired instead.");
        repo.delete(id);
        audit.log(u.id(), "asset.delete", "asset", id.toString(), Map.of("tag", a.get("tag")), null, ip);
    }

    /** Hands an available asset to an employee. */
    public String assign(CurrentUser u, String idRaw, AssignInput in, String ip) {
        UUID id = Check.uuid(idRaw);
        if (in == null) throw new UserException(HttpStatus.BAD_REQUEST, "Assignment details are missing.");
        UUID employeeId = Check.uuid(in.employeeId(), "Choose the employee.");
        LocalDate on = in.assignedOn() == null || in.assignedOn().isBlank() ? LocalDate.now() : date(in.assignedOn(), "Enter the date as YYYY-MM-DD.");
        String notes = optional(in.notes(), 500, "the notes");
        Map<String, Object> a = row(id);
        if (!"AVAILABLE".equals(a.get("status"))) throw new UserException("Only an available asset can be assigned (this one is " + a.get("status") + ").");
        if (!repo.assignable(employeeId)) throw new UserException(HttpStatus.NOT_FOUND, "Employee not found.");
        repo.insertAssignment(UUID.randomUUID(), id, employeeId, on, u.id(), notes);
        repo.setStatus(id, "ASSIGNED");
        audit.log(u.id(), "asset.assign", "asset", id.toString(), null, Map.of("tag", a.get("tag"), "employeeId", employeeId.toString(), "assignedOn", on.toString()), ip);
        notifications.notify(employeeId, "Asset assigned to you: " + a.get("tag") + " (" + label(a) + ")", "/my-assets");
        return "Assigned " + a.get("tag");
    }

    /** Takes the asset back with the condition it came back in (damaged → in repair, lost → retired). */
    public String giveBack(CurrentUser u, String idRaw, ReturnInput in, String ip) {
        UUID id = Check.uuid(idRaw);
        if (in == null) throw new UserException(HttpStatus.BAD_REQUEST, "Return details are missing.");
        String condition = Check.oneOf(in.condition(), CONDITIONS);
        LocalDate on = in.returnedOn() == null || in.returnedOn().isBlank() ? LocalDate.now() : date(in.returnedOn(), "Enter the date as YYYY-MM-DD.");
        String notes = optional(in.notes(), 500, "the notes");
        Map<String, Object> a = row(id);
        List<Map<String, Object>> open = repo.findOpenAssignment(id);
        if (open.isEmpty()) throw new UserException("This asset isn't assigned to anyone.");
        Map<String, Object> s = open.get(0);
        if (on.isBefore(LocalDate.parse((String) s.get("assignedOn")))) throw new UserException("The return date can't be before the assignment date (" + s.get("assignedOn") + ").");
        repo.closeAssignment(UUID.fromString((String) s.get("id")), on, u.id(), condition, notes);
        String next = switch (condition) {
            case "DAMAGED" -> "IN_REPAIR";
            case "LOST" -> "RETIRED";
            default -> "AVAILABLE";
        };
        repo.setStatus(id, next);
        audit.log(u.id(), "asset.return", "asset", id.toString(), null,
                Map.of("tag", a.get("tag"), "employeeId", s.get("employeeId"), "returnedOn", on.toString(), "condition", condition), ip);
        return "Returned " + a.get("tag") + (next.equals("AVAILABLE") ? "" : " — marked " + next.toLowerCase().replace('_', ' '));
    }

    static String label(Map<String, Object> a) {
        String t = ((String) a.get("type")).toLowerCase();
        String mm = ((a.get("make") == null ? "" : a.get("make") + " ") + (a.get("model") == null ? "" : a.get("model"))).trim();
        return mm.isEmpty() ? t : mm;
    }
}
