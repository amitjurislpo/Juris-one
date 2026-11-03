package com.jurislpo.jurisone.service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Fixed-window in-memory counter keyed by e.g. IP. Good for a single instance; several
 * instances would need a shared store. Per-account lockout lives on {@code users} in the DB. At most {@value #MAXIMUM_KEYS} keys
 * are kept: ended windows are dropped from the oldest end as new keys come, and when the map is still full the oldest window goes,
 * so memory is bounded and no call scans the whole map.
 */
@Component
public class RateLimiter {

    private static final int MAXIMUM_KEYS = 10_000;

    private static final class Window {
        int count;
        final long reset;

        Window(long reset) { this.count = 1; this.reset = reset; }
    }

    public record Result(boolean limited, long retryInMs) {}

    /** In order of creation, so the oldest windows are at the front. */
    private final Map<String, Window> hits = new LinkedHashMap<>();

    private Window live(String key) {
        Window window = hits.get(key);
        if (window != null && window.reset <= System.currentTimeMillis()) {
            hits.remove(key);
            return null;
        }
        return window;
    }

    /** True when {@code key} has used up {@code max} hits in its current window. */
    public synchronized Result limited(String key, int max) {
        Window window = live(key);
        return window != null && window.count >= max ? new Result(true, window.reset - System.currentTimeMillis()) : new Result(false, 0);
    }

    public synchronized void hit(String key, long windowMs) {
        Window window = live(key);
        if (window != null) {
            window.count += 1;
            return;
        }
        makeRoom();
        hits.put(key, new Window(System.currentTimeMillis() + windowMs));
    }

    /** Before a new key is added: drop ended windows from the oldest end, and the oldest window if the map is still full. */
    private void makeRoom() {
        if (hits.size() < MAXIMUM_KEYS) return;
        long now = System.currentTimeMillis();
        for (Iterator<Window> oldest = hits.values().iterator(); oldest.hasNext() && hits.size() >= MAXIMUM_KEYS;) {
            if (oldest.next().reset > now) break;
            oldest.remove();
        }
        if (hits.size() >= MAXIMUM_KEYS) {
            Iterator<Window> oldest = hits.values().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Counts every call (used for endpoints like "forgot password"); {@code limited=false} means allowed. */
    public synchronized Result rateLimit(String key, int max, long windowMs) {
        Result result = limited(key, max);
        if (result.limited()) return result;
        hit(key, windowMs);
        return new Result(false, 0);
    }

    /** Test hook. */
    public synchronized void clear() {
        hits.clear();
    }
}
