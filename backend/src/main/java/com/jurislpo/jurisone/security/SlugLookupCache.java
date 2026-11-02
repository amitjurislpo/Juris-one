package com.jurislpo.jurisone.security;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Remembers which business a slug belongs to so that not every request reads the database: found slugs for 60 seconds, unknown
 * slugs for 10 seconds (so a newly created business is reachable quickly). The cache holds at most {@code maxEntries} slugs (oldest
 * entry out first), so random hosts can never use up memory. A failing lookup is not cached; the exception goes to the caller.
 */
final class SlugLookupCache {

    static final Duration FOUND_TTL = Duration.ofSeconds(60);
    static final Duration NOT_FOUND_TTL = Duration.ofSeconds(10);
    static final int DEFAULT_MAX_ENTRIES = 1000;

    private record Entry(UUID businessId, long expiresAtNanos) {}

    private final Function<String, UUID> lookup;
    private final LongSupplier nanoClock;
    private final Map<String, Entry> entries;

    SlugLookupCache(Function<String, UUID> lookup, LongSupplier nanoClock, int maxEntries) {
        this.lookup = lookup;
        this.nanoClock = nanoClock;
        this.entries = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    SlugLookupCache(Function<String, UUID> lookup) {
        this(lookup, System::nanoTime, DEFAULT_MAX_ENTRIES);
    }

    /** The business id of the slug, or null when no business has it. */
    UUID businessId(String slug) {
        long now = nanoClock.getAsLong();
        synchronized (entries) {
            Entry entry = entries.get(slug);
            if (entry != null && now - entry.expiresAtNanos() < 0) return entry.businessId();
        }
        UUID found = lookup.apply(slug); // outside the lock: a slow database must not block every request
        Duration timeToLive = found == null ? NOT_FOUND_TTL : FOUND_TTL;
        synchronized (entries) {
            entries.remove(slug); // re-inserting moves it to the newest position
            entries.put(slug, new Entry(found, now + timeToLive.toNanos()));
        }
        return found;
    }

    /** Forgets the slug (a later step calls this when a business is renamed or suspended). */
    void evict(String slug) {
        synchronized (entries) {
            entries.remove(slug);
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
