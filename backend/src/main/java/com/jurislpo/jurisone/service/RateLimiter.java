package com.jurislpo.jurisone.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Fixed-window in-memory counter keyed by e.g. IP. Good for a single instance; several
 * instances would need a shared store. Per-account lockout lives on {@code users} in the DB.
 */
@Component
public class RateLimiter {

    private static final class Window {
        int count;
        final long reset;

        Window(long reset) { this.count = 1; this.reset = reset; }
    }

    public record Result(boolean limited, long retryInMs) {}

    private final Map<String, Window> hits = new ConcurrentHashMap<>();

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
        if (window != null) window.count += 1;
        else hits.put(key, new Window(System.currentTimeMillis() + windowMs));
        if (hits.size() > 10_000) for (String windowKey : hits.keySet().toArray(new String[0])) live(windowKey);
    }

    /** Counts every call (used for endpoints like "forgot password"); {@code limited=false} means allowed. */
    public synchronized Result rateLimit(String key, int max, long windowMs) {
        Result result = limited(key, max);
        if (result.limited()) return result;
        hit(key, windowMs);
        return new Result(false, 0);
    }

    /** Test hook. */
    public void clear() {
        hits.clear();
    }
}
