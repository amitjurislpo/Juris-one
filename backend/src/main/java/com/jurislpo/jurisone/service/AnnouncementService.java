package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.AnnouncementRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Announcements: HR / the owner ({@code announcements.manage}) post them for everyone or one region; employees see the
 * active ones (published, not expired, for them). Posting rings the notification bell of everyone it is for.
 */
@Service
@Transactional
public class AnnouncementService {

    static final List<String> AUDIENCES = List.of("ALL", "IN", "US");

    private final AnnouncementRepository repo;
    private final NotificationService notifications;
    private final AuditService audit;

    public AnnouncementService(AnnouncementRepository repo, NotificationService notifications, AuditService audit) {
        this.repo = repo;
        this.notifications = notifications;
        this.audit = audit;
    }

    public record AnnouncementInput(String title, String body, String audience, Boolean pinned, String expiresOn) {}

    record ValidatedAnnouncement(String title, String body, String audience, boolean pinned, LocalDate expiresOn) {}

    /** The announcements shown to this user now (pinned first, newest first). */
    public List<Map<String, Object>> active(CurrentUser user, Integer limit) {
        int n = limit == null ? 50 : Math.max(1, Math.min(100, limit));
        return repo.findActive(user.region(), n);
    }

    /** Everything ever posted, with whether it is showing now (managers). */
    public List<Map<String, Object>> all() {
        return repo.findAll();
    }

    Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = repo.findById(id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Announcement not found.");
        return rows.get(0);
    }

    static ValidatedAnnouncement validate(AnnouncementInput input) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Announcement details are missing.");
        String title = Check.trimmed(input.title(), 3, "Enter the announcement title.", 160, "Keep the title under 160 characters.");
        String body = input.body() == null ? "" : input.body().replace("\r\n", "\n").strip();
        if (body.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Write the announcement text.");
        if (body.length() > 10_000) throw new UserException(HttpStatus.BAD_REQUEST, "The announcement is too long (10,000 characters at most).");
        String audience = Check.oneOf(input.audience() == null ? "ALL" : input.audience(), AUDIENCES);
        LocalDate expires = input.expiresOn() == null || input.expiresOn().isBlank() ? null : AssetService.date(input.expiresOn(), "Enter the expiry date as YYYY-MM-DD.");
        if (expires != null && expires.isBefore(LocalDate.now())) throw new UserException("The expiry date can't be in the past.");
        return new ValidatedAnnouncement(title, body, audience, Boolean.TRUE.equals(input.pinned()), expires);
    }

    /** Publishes the announcement straight away and notifies everyone it is for. */
    public String create(CurrentUser user, AnnouncementInput input, String ip) {
        ValidatedAnnouncement validated = validate(input);
        UUID id = UUID.randomUUID();
        repo.insert(id, validated.title(), validated.body(), validated.audience(), validated.pinned(), validated.expiresOn(), user.id());
        audit.log(user.id(), "announcement.create", "announcement", id.toString(), null, Map.of("title", validated.title(), "audience", validated.audience(), "pinned", validated.pinned()), ip);
        notifyAll(id, validated.title(), validated.audience());
        return id.toString();
    }

    /** Edits it in place (nobody is notified again). */
    public void update(CurrentUser user, String idRaw, AnnouncementInput input, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> announcement = row(id);
        ValidatedAnnouncement validated = validate(input);
        repo.update(id, validated.title(), validated.body(), validated.audience(), validated.pinned(), validated.expiresOn());
        audit.log(user.id(), "announcement.update", "announcement", id.toString(), Map.of("title", announcement.get("title")), Map.of("title", validated.title(), "audience", validated.audience()), ip);
    }

    /** PUBLISHED (again — notifies again) or UNPUBLISHED (hidden from everyone). */
    public String setStatus(CurrentUser user, String idRaw, String statusRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        String status = Check.oneOf(statusRaw, List.of("PUBLISHED", "UNPUBLISHED"));
        Map<String, Object> announcement = row(id);
        if (status.equals(announcement.get("status"))) throw new UserException("It is already " + status.toLowerCase() + ".");
        repo.setStatus(id, status);
        audit.log(user.id(), "announcement." + (status.equals("PUBLISHED") ? "publish" : "unpublish"), "announcement", id.toString(), null, Map.of("title", announcement.get("title")), ip);
        if (status.equals("PUBLISHED")) notifyAll(id, (String) announcement.get("title"), (String) announcement.get("audience"));
        return status.equals("PUBLISHED") ? "Announcement published" : "Announcement unpublished";
    }

    private void notifyAll(UUID id, String title, String audience) {
        for (UUID e : repo.findRecipients(audience)) notifications.notify(e, "Announcement: " + title, "/announcements");
    }
}
