package com.jurislpo.jurisone.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void countsPerKeyAndLimitsAtTheMaximum() {
        RateLimiter limiter = new RateLimiter();
        assertThat(limiter.rateLimit("a", 2, 60_000).limited()).isFalse();
        assertThat(limiter.rateLimit("a", 2, 60_000).limited()).isFalse();
        assertThat(limiter.rateLimit("a", 2, 60_000).limited()).isTrue();
        assertThat(limiter.rateLimit("b", 2, 60_000).limited()).isFalse();
    }

    @Test
    void memoryStaysBoundedEvenWhenEveryWindowIsStillLive() throws Exception {
        RateLimiter limiter = new RateLimiter();
        for (int i = 0; i < 25_000; i++) limiter.hit("ip:" + i, 600_000);
        java.lang.reflect.Field field = RateLimiter.class.getDeclaredField("hits");
        field.setAccessible(true);
        assertThat(((java.util.Map<?, ?>) field.get(limiter)).size()).isLessThanOrEqualTo(10_000);
        // the newest key is still counted; the oldest one was given up
        assertThat(limiter.limited("ip:24999", 1).limited()).isTrue();
        assertThat(limiter.limited("ip:0", 1).limited()).isFalse();
    }

    @Test
    void endedWindowsStartFresh() throws Exception {
        RateLimiter limiter = new RateLimiter();
        limiter.hit("x", 1);
        Thread.sleep(5);
        assertThat(limiter.limited("x", 1).limited()).isFalse();
    }
}
