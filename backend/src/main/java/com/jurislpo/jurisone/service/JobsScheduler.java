package com.jurislpo.jurisone.service;

import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Every 10 minutes, plus once 15 s after boot (src/lib/scheduler.ts). On only with jurisone.jobs-enabled=true. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "jurisone.jobs-enabled", havingValue = "true")
public class JobsScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobsScheduler.class);
    private final JobsService jobs;

    public JobsScheduler(JobsService jobs) {
        this.jobs = jobs;
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 600_000)
    public void tick() {
        try {
            Map<String, Object> r = jobs.runJobs(Instant.now());
            jobs.logSummary(r);
        } catch (RuntimeException e) {
            log.error("[jobs] failed", e);
        }
    }
}
