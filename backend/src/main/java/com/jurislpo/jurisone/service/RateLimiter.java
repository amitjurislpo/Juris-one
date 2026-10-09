package com.jurislpo.jurisone.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Fixed-window in-memory counter keyed by e.g. IP (src/lib/rate-limit.ts). Good for a single instance; several
 * instances would need a shared store. Per-account lockout lives on {@code users} in the DB.
 */
@Component
public class RateLimiter {

    private static final class Window {
        int n;
        final long reset;

        Window(long reset) { this.n = 1; this.reset = reset; }
    }

    public record Result(boolean limited, long retryInMs) {}

    private final Map<String, Window> hits = new ConcurrentHashMap<>();

    private Window live(String key) {
        Window cur = hits.get(key);
        if (cur != null && cur.reset <= System.currentTimeMillis()) {
            hits.remove(key);
            return null;
        }
        return cur;
    }

    /** True when {@code key} has used up {@code max} hits in its current window. */
    public synchronized Result limited(String key, int max) {
        Window cur = live(key);
        return cur != null && cur.n >= max ? new Result(true, cur.reset - System.currentTimeMillis()) : new Result(false, 0);
    }

    public synchronized void hit(String key, long windowMs) {
        Window cur = live(key);
        if (cur != null) cur.n += 1;
        else hits.put(key, new Window(System.currentTimeMillis() + windowMs));
        if (hits.size() > 10_000) for (String k : hits.keySet().toArray(new String[0])) live(k);
    }

    /** Counts every call (used for endpoints like "forgot password"); {@code limited=false} means allowed. */
    public synchronized Result rateLimit(String key, int max, long windowMs) {
        Result l = limited(key, max);
        if (l.limited()) return l;
        hit(key, windowMs);
        return new Result(false, 0);
    }

    /** Test hook. */
    public void clear() {
        hits.clear();
    }
}
