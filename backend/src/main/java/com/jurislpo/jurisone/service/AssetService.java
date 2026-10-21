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

    record ValidatedAsset(String tag, String type, String make, String model, String serial, LocalDate purchaseDate, String status, String notes) {}

    // ───────────────────────── employees: my assets ─────────────────────────

    /** {@code {current: [...], past: [...]}} — what is assigned to this user now, and what they have returned. */
    public Map<String, Object> mine(CurrentUser user) {
        List<Map<String, Object>> all = repo.findForEmployee(user.id());
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

    static String optional(String value, int max, String what) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        if (text.length() > max) throw new UserException(HttpStatus.BAD_REQUEST, "Keep " + what + " under " + max + " characters.");
        return text;
    }

    static LocalDate date(String value, String message) {
        try {
            return LocalDate.parse(Check.isoDate(value, message));
        } catch (DateTimeException e) {
            throw new UserException(HttpStatus.BAD_REQUEST, message);
        }
    }

    static ValidatedAsset validate(AssetInput input) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Asset details are missing.");
        String tag = Check.trimmed(input.tag(), 1, "Enter the asset tag.", 40, "Keep the asset tag under 40 characters.");
        String type = Check.oneOf(input.type() == null ? "OTHER" : input.type(), TYPES);
        LocalDate purchased = input.purchaseDate() == null || input.purchaseDate().isBlank() ? null : date(input.purchaseDate(), "Enter the purchase date as YYYY-MM-DD.");
        String status = input.status() == null ? null : Check.oneOf(input.status(), SETTABLE);
        return new ValidatedAsset(tag, type, optional(input.make(), 80, "the make"), optional(input.model(), 80, "the model"), optional(input.serial(), 80, "the serial number"),
                purchased, status, optional(input.notes(), 500, "the notes"));
    }

    public String create(CurrentUser user, AssetInput input, String ip) {
        ValidatedAsset validated = validate(input);
        if (repo.tagTaken(validated.tag(), null)) throw new UserException(HttpStatus.CONFLICT, "An asset with the tag \"" + validated.tag() + "\" already exists.");
        UUID id = UUID.randomUUID();
        repo.insert(id, validated.tag(), validated.type(), validated.make(), validated.model(), validated.serial(), validated.purchaseDate(), validated.status() == null ? "AVAILABLE" : validated.status(), validated.notes(), user.id());
        audit.log(user.id(), "asset.create", "asset", id.toString(), null, Map.of("tag", validated.tag(), "type", validated.type()), ip);
        return id.toString();
    }

    public void update(CurrentUser user, String idRaw, AssetInput input, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> asset = row(id);
        ValidatedAsset validated = validate(input);
        if (repo.tagTaken(validated.tag(), id)) throw new UserException(HttpStatus.CONFLICT, "An asset with the tag \"" + validated.tag() + "\" already exists.");
        String current = (String) asset.get("status");
        String status = validated.status() == null ? current : validated.status();
        if ("ASSIGNED".equals(current) && !"ASSIGNED".equals(status)) throw new UserException("Return the asset before changing its status.");
        repo.update(id, validated.tag(), validated.type(), validated.make(), validated.model(), validated.serial(), validated.purchaseDate(), status, validated.notes());
        audit.log(user.id(), "asset.update", "asset", id.toString(), Map.of("tag", asset.get("tag"), "status", current), Map.of("tag", validated.tag(), "status", status), ip);
    }

    /** Deletes an asset that was never assigned (otherwise retire it: the history stays). */
    public void delete(CurrentUser user, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> asset = row(id);
        if (repo.everAssigned(id)) throw new UserException("An asset with assignment history can't be deleted — mark it retired instead.");
        repo.delete(id);
        audit.log(user.id(), "asset.delete", "asset", id.toString(), Map.of("tag", asset.get("tag")), null, ip);
    }

    /** Hands an available asset to an employee. */
    public String assign(CurrentUser user, String idRaw, AssignInput input, String ip) {
        UUID id = Check.uuid(idRaw);
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Assignment details are missing.");
        UUID employeeId = Check.uuid(input.employeeId(), "Choose the employee.");
        LocalDate on = input.assignedOn() == null || input.assignedOn().isBlank() ? LocalDate.now() : date(input.assignedOn(), "Enter the date as YYYY-MM-DD.");
        String notes = optional(input.notes(), 500, "the notes");
        Map<String, Object> asset = row(id);
        if (!"AVAILABLE".equals(asset.get("status"))) throw new UserException("Only an available asset can be assigned (this one is " + asset.get("status") + ").");
        if (!repo.assignable(employeeId)) throw new UserException(HttpStatus.NOT_FOUND, "Employee not found.");
        repo.insertAssignment(UUID.randomUUID(), id, employeeId, on, user.id(), notes);
        repo.setStatus(id, "ASSIGNED");
        audit.log(user.id(), "asset.assign", "asset", id.toString(), null, Map.of("tag", asset.get("tag"), "employeeId", employeeId.toString(), "assignedOn", on.toString()), ip);
        notifications.notify(employeeId, "Asset assigned to you: " + asset.get("tag") + " (" + label(asset) + ")", "/my-assets");
        return "Assigned " + asset.get("tag");
    }

    /** Takes the asset back with the condition it came back in (damaged → in repair, lost → retired). */
    public String giveBack(CurrentUser user, String idRaw, ReturnInput input, String ip) {
        UUID id = Check.uuid(idRaw);
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Return details are missing.");
        String condition = Check.oneOf(input.condition(), CONDITIONS);
        LocalDate on = input.returnedOn() == null || input.returnedOn().isBlank() ? LocalDate.now() : date(input.returnedOn(), "Enter the date as YYYY-MM-DD.");
        String notes = optional(input.notes(), 500, "the notes");
        Map<String, Object> asset = row(id);
        List<Map<String, Object>> open = repo.findOpenAssignment(id);
        if (open.isEmpty()) throw new UserException("This asset isn't assigned to anyone.");
        Map<String, Object> row = open.get(0);
        if (on.isBefore(LocalDate.parse((String) row.get("assignedOn")))) throw new UserException("The return date can't be before the assignment date (" + row.get("assignedOn") + ").");
        repo.closeAssignment(UUID.fromString((String) row.get("id")), on, user.id(), condition, notes);
        String next = switch (condition) {
            case "DAMAGED" -> "IN_REPAIR";
            case "LOST" -> "RETIRED";
            default -> "AVAILABLE";
        };
        repo.setStatus(id, next);
        audit.log(user.id(), "asset.return", "asset", id.toString(), null,
                Map.of("tag", asset.get("tag"), "employeeId", row.get("employeeId"), "returnedOn", on.toString(), "condition", condition), ip);
        return "Returned " + asset.get("tag") + (next.equals("AVAILABLE") ? "" : " — marked " + next.toLowerCase().replace('_', ' '));
    }

    static String label(Map<String, Object> row) {
        String t = ((String) row.get("type")).toLowerCase();
        String mm = ((row.get("make") == null ? "" : row.get("make") + " ") + (row.get("model") == null ? "" : row.get("model"))).trim();
        return mm.isEmpty() ? t : mm;
    }
}
