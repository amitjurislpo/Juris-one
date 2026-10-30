package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.InboxService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.security.Auth;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's notification box (always reachable, even before policies are accepted or after expiry). */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    public record ReadBody(Boolean read) {}

    private final Auth auth;
    private final InboxService inbox;

    public NotificationController(Auth auth, InboxService inbox) {
        this.auth = auth;
        this.inbox = inbox;
    }

    /** {@code ?unread=true} only unread; {@code ?limit=} for a card; {@code ?page=} from 1. Unread first, then newest. */
    @GetMapping
    public ApiResult<InboxService.Page> list(@RequestParam(name = "unread", required = false) Boolean unread,
                                            @RequestParam(name = "limit", required = false) Integer limit,
                                            @RequestParam(name = "page", required = false) Integer page) {
        return ApiResult.success(inbox.list(auth.assertPerm(), Boolean.TRUE.equals(unread), limit, page));
    }

    /** {@code {"read": true | false}} */
    @PatchMapping("/{id}")
    public ApiResult<Void> mark(@PathVariable String id, @RequestBody ReadBody body) {
        return ApiResult.message(inbox.mark(auth.assertPerm(), id, body.read()));
    }

    /** {@code {"read": true}} — everything read, except policies still to accept. */
    @PatchMapping
    public ApiResult<Map<String, Integer>> markAll(@RequestBody ReadBody body) {
        if (!Boolean.TRUE.equals(body.read())) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected true");
        int updatedCount = inbox.markAll(auth.assertPerm());
        return ApiResult.success(updatedCount == 0 ? "Nothing new" : "All caught up", Map.of("count", updatedCount));
    }
}
