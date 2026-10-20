package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.repository.InboxRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The signed-in user's notification box. Unread first, then newest; items stay until read one by one (or all at once).
 * A policy notification ({@code sticky}) stays unread until the policy is accepted — it can't be marked read by hand.
 */
@Service
public class InboxService {

    public static final int PAGE = 30;

    public record Item(String id, String text, String link, String createdAt, String readAt, boolean sticky) {}

    public record Page(List<Item> items, int unread, int total, int page, int pages) {}

    private final InboxRepository repo;

    public InboxService(InboxRepository repo) {
        this.repo = repo;
    }

    public int unread(UUID employeeId) {
        return repo.countUnread(employeeId);
    }

    /** {@code unreadOnly}: just the unread ones; {@code limit} (≤ 100) overrides the page size for cards; {@code page} from 1. */
    public Page list(CurrentUser u, boolean unreadOnly, Integer limit, Integer page) {
        int size = limit == null ? PAGE : Math.max(1, Math.min(100, limit));
        int p = page == null || page < 1 ? 1 : page;
        int total = repo.count(u.id(), unreadOnly);
        List<Item> items = repo.page(u.id(), unreadOnly, size, (p - 1) * size);
        return new Page(items, unread(u.id()), total, p, Math.max(1, (total + size - 1) / size));
    }

    public static Item item(String id, String text, String link, Timestamp created, Timestamp read, String kind) {
        return new Item(id, text, link, Format.jsIso(created.toInstant()), read == null ? null : Format.jsIso(read.toInstant()),
                read == null && NotificationService.KIND_POLICY.equals(kind));
    }

    /** Marks one of the user's notifications read or unread. A pending policy notification can't be marked read. */
    public String mark(CurrentUser u, String idRaw, Boolean read) {
        UUID id = Check.uuid(idRaw);
        if (read == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean");
        List<Map<String, Object>> rows = repo.findKindAndReadAt(id, u.id());
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Notification not found.");
        boolean sticky = NotificationService.KIND_POLICY.equals(rows.get(0).get("kind")) && rows.get(0).get("read_at") == null;
        if (read && sticky) throw new UserException("This stays until you accept the policy.");
        if (!read && NotificationService.KIND_POLICY.equals(rows.get(0).get("kind"))) throw new UserException("Policy notifications can't be marked unread.");
        repo.setRead(id, read);
        return read ? "Marked as read" : "Marked as unread";
    }

    /** Marks all of the user's notifications read, except policies still to accept. Returns how many. */
    public int markAll(CurrentUser u) {
        return repo.markAllRead(u.id());
    }

    /** Newest notifications for the bell: unread first. */
    public List<Item> recent(UUID employeeId, int n) {
        return repo.recent(employeeId, n);
    }
}
