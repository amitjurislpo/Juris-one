package com.jurislpo.jurisone.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The scheduled run's result is keyed by business code; logging it must not fail (it once threw a NullPointerException every tick). */
class JobsServiceLogSummaryTest {

    private final JobsService jobs = mock(JobsService.class, CALLS_REAL_METHODS);

    @Test
    void logsOneLinePerBusiness() {
        Map<String, Object> business = Map.of("status", Map.of("changed", 2), "lock", Map.of("locked", List.of("2026-08")), "finalize", Map.of("written", 5));
        assertThatCode(() -> jobs.logSummary(Map.of("juris-consultants", business))).doesNotThrowAnyException();
    }

    @Test
    void acceptsASkippedRun() {
        assertThatCode(() -> jobs.logSummary(Map.of("skipped", true))).doesNotThrowAnyException();
    }
}
