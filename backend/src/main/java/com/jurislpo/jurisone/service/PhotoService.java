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
    static String checkedType(String declared, byte[] b) {
        String d = declared == null ? "" : declared.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("image/jpeg", "image/png", "image/webp").contains(d)) throw new UserException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Use a JPEG, PNG or WebP image.");
        if (b == null || b.length == 0) throw new UserException("Choose a photo to upload.");
        if (b.length > MAX_BYTES) throw new UserException(HttpStatus.PAYLOAD_TOO_LARGE, "The photo is too large. Keep it under 400 KB.");
        String real = b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF ? "image/jpeg"
                : b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A ? "image/png"
                : b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P' ? "image/webp" : null;
        if (real == null || !real.equals(d)) throw new UserException("That file isn't a valid " + d.substring(6).toUpperCase(java.util.Locale.ROOT) + " image.");
        return real;
    }

    /** Own photo ({@code /api/profile/photo}). Returns the new version stamp (epoch millis). */
    public long setOwn(CurrentUser user, String declaredType, byte[] data, String ip) {
        return store(user.id(), user.id(), declaredType, data, ip);
    }

    public void removeOwn(CurrentUser user, String ip) {
        drop(user.id(), user.id(), ip);
    }

    /** Someone else's photo: the same rule as editing that employee (people.add, role in reach); never an exited employee. */
    public long setFor(CurrentUser user, String idRaw, String declaredType, byte[] data, String ip) {
        UUID id = mayEdit(user, idRaw);
        return store(user.id(), id, declaredType, data, ip);
    }

    public void removeFor(CurrentUser user, String idRaw, String ip) {
        drop(user.id(), mayEdit(user, idRaw), ip);
    }

    private UUID mayEdit(CurrentUser user, String idRaw) {
        UUID id = Check.uuid(idRaw);
        Target target = actors.target(id);
        if (target == null || !PeopleRules.canEditEmployee(actors.actor(user), target) || target.status() == EmployeeStatus.EXITED) throw new UserException(HttpStatus.FORBIDDEN, "You can't change this person's photo.");
        return id;
    }

    private long store(UUID actor, UUID target, String declaredType, byte[] data, String ip) {
        String type = checkedType(declaredType, data);
        Timestamp at = repo.savePhoto(target, type, data);
        audit.log(actor, "employee.photo_set", "employee", target.toString(), null, Map.of("contentType", type, "bytes", data.length), ip);
        return at.getTime();
    }

    private void drop(UUID actor, UUID target, String ip) {
        if (repo.deletePhoto(target)) audit.log(actor, "employee.photo_remove", "employee", target.toString(), null, null, ip);
    }

    /** The photo of any employee of the signed-in user's business (tenant row-level security scopes the lookup); null when none. */
    public Photo read(String idRaw) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> r = repo.findPhoto(id);
        if (r.isEmpty()) return null;
        Map<String, Object> m = r.get(0);
        return new Photo((String) m.get("content_type"), (byte[]) m.get("data"), ((Timestamp) m.get("updated_at")).getTime());
    }

    /** Epoch millis of a {@code photo_updated_at} column value (null stays null) — the {@code photo} field of every payload. */
    public static Long version(Object timestamp) {
        return timestamp instanceof Timestamp t ? Long.valueOf(t.getTime()) : null;
    }
}
