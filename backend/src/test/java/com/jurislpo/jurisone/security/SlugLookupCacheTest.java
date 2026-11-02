package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The slug cache: 60 s for found, 10 s for unknown, bounded, errors not cached. */
class SlugLookupCacheTest {

    static final long SECOND = 1_000_000_000L;
    final AtomicLong now = new AtomicLong(1_000 * SECOND);
    final AtomicInteger reads = new AtomicInteger();
    final UUID acmeId = UUID.randomUUID();

    SlugLookupCache cache(int maxEntries) {
        return new SlugLookupCache(slug -> {
            reads.incrementAndGet();
            return slug.startsWith("acme") ? acmeId : null;
        }, now::get, maxEntries);
    }

    @Test
    void aFoundSlugIsRememberedForSixtySeconds() {
        SlugLookupCache cache = cache(10);
        assertThat(cache.businessId("acme")).isEqualTo(acmeId);
        now.addAndGet(59 * SECOND);
        assertThat(cache.businessId("acme")).isEqualTo(acmeId);
        assertThat(reads).hasValue(1);
        now.addAndGet(2 * SECOND);
        assertThat(cache.businessId("acme")).isEqualTo(acmeId);
        assertThat(reads).hasValue(2);
    }

    @Test
    void anUnknownSlugIsRememberedForTenSeconds() {
        SlugLookupCache cache = cache(10);
        assertThat(cache.businessId("nobody")).isNull();
        now.addAndGet(9 * SECOND);
        assertThat(cache.businessId("nobody")).isNull();
        assertThat(reads).hasValue(1);
        now.addAndGet(2 * SECOND);
        assertThat(cache.businessId("nobody")).isNull();
        assertThat(reads).hasValue(2);
    }

    @Test
    void theCacheNeverHoldsMoreThanItsBound() {
        SlugLookupCache cache = cache(5);
        for (int i = 0; i < 1000; i++) cache.businessId("random-" + i);
        assertThat(cache.size()).isEqualTo(5);
        cache.businessId("random-999"); // the newest is still there
        assertThat(reads).hasValue(1000);
        cache.businessId("random-0"); // the oldest is gone
        assertThat(reads).hasValue(1001);
    }

    @Test
    void evictForgetsASlug() {
        SlugLookupCache cache = cache(10);
        cache.businessId("acme");
        cache.evict("acme");
        assertThat(cache.size()).isZero();
        cache.businessId("acme");
        assertThat(reads).hasValue(2);
        cache.evict("never-seen"); // harmless
    }

    @Test
    void aFailingLookupIsNotRemembered() {
        AtomicInteger calls = new AtomicInteger();
        SlugLookupCache cache = new SlugLookupCache(slug -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("database down");
            return acmeId;
        }, now::get, 10);
        assertThatThrownBy(() -> cache.businessId("acme")).isInstanceOf(IllegalStateException.class);
        assertThat(cache.size()).isZero();
        assertThat(cache.businessId("acme")).isEqualTo(acmeId);
    }
}
