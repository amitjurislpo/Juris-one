package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.engine.PeopleRules.Target;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.ProfileRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.common.Check;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Employee profile photos: the employee sets their own, HR / the business admin can set or remove anyone they may edit.
 * The type is decided from the image's first bytes, never from the request header alone; at most {@link #MAX_BYTES}.
 */
@Service
@org.springframework.transaction.annotation.Transactional
public class PhotoService {

    public static final int MAX_BYTES = 400 * 1024;

    private final ProfileRepository repo;
    private final ActorService actors;
    private final AuditService audit;

    public PhotoService(ProfileRepository repo, ActorService actors, AuditService audit) {
        this.repo = repo;
        this.actors = actors;
        this.audit = audit;
    }

    public record Photo(String contentType, byte[] data, long version) {}

    /** The stored type for these bytes (JPEG, PNG or WebP by magic bytes), checked against the declared header. */
    static String checkedType(String declared, byte[] bytes) {
        String type = declared == null ? "" : declared.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("image/jpeg", "image/png", "image/webp").contains(type)) throw new UserException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Use a JPEG, PNG or WebP image.");
        if (bytes == null || bytes.length == 0) throw new UserException("Choose a photo to upload.");
        if (bytes.length > MAX_BYTES) throw new UserException(HttpStatus.PAYLOAD_TOO_LARGE, "The photo is too large. Keep it under 400 KB.");
        String real = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF ? "image/jpeg"
                : bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G' && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A ? "image/png"
                : bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P' ? "image/webp" : null;
        if (real == null || !real.equals(type)) throw new UserException("That file isn't a valid " + type.substring(6).toUpperCase(java.util.Locale.ROOT) + " image.");
        return real;
    }

    /** Own photo ({@code /api/profile/photo}). Returns the new version stamp (epoch millis). */
    public long setOwn(CurrentUser user, String declaredType, byte[] data, String ipAddress) {
        return store(user.id(), user.id(), declaredType, data, ipAddress);
    }

    public void removeOwn(CurrentUser user, String ipAddress) {
        drop(user.id(), user.id(), ipAddress);
    }

    /** Someone else's photo: the same rule as editing that employee (people.add, role in reach); never an exited employee. */
    public long setFor(CurrentUser user, String idRaw, String declaredType, byte[] data, String ipAddress) {
        UUID id = mayEdit(user, idRaw);
        return store(user.id(), id, declaredType, data, ipAddress);
    }

    public void removeFor(CurrentUser user, String idRaw, String ipAddress) {
        drop(user.id(), mayEdit(user, idRaw), ipAddress);
    }

    private UUID mayEdit(CurrentUser user, String idRaw) {
        UUID id = Check.uuid(idRaw);
        Target target = actors.target(id);
        if (target == null || !PeopleRules.canEditEmployee(actors.actor(user), target) || target.status() == EmployeeStatus.EXITED) throw new UserException(HttpStatus.FORBIDDEN, "You can't change this person's photo.");
        return id;
    }

    private long store(UUID actor, UUID target, String declaredType, byte[] data, String ipAddress) {
        String type = checkedType(declaredType, data);
        Timestamp timestamp = repo.savePhoto(target, type, data);
        audit.log(actor, "employee.photo_set", "employee", target.toString(), null, Map.of("contentType", type, "bytes", data.length), ipAddress);
        return timestamp.getTime();
    }

    private void drop(UUID actor, UUID target, String ipAddress) {
        if (repo.deletePhoto(target)) audit.log(actor, "employee.photo_remove", "employee", target.toString(), null, null, ipAddress);
    }

    /** The photo of any employee of the signed-in user's business (tenant row-level security scopes the lookup); null when none. */
    public Photo read(String idRaw) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = repo.findPhoto(id);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        return new Photo((String) row.get("content_type"), (byte[]) row.get("data"), ((Timestamp) row.get("updated_at")).getTime());
    }

    /** Epoch millis of a {@code photo_updated_at} column value (null stays null) — the {@code photo} field of every payload. */
    public static Long version(Object timestamp) {
        return timestamp instanceof Timestamp stamp ? Long.valueOf(stamp.getTime()) : null;
    }
}
