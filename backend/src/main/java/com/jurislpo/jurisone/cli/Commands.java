package com.jurislpo.jurisone.cli;

import com.jurislpo.jurisone.service.SeedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.service.JobsService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One-shot commands: {@code migrate} (npm run db:migrate — Flyway runs while the app starts), {@code seed} (npm run db:seed),
 * {@code reset} (npm run db:reset — development only: empty database, migrate, seed) and {@code jobs} (npm run jobs, for cron).
 */
@Component
public class Commands {

    private static final Logger log = LoggerFactory.getLogger(Commands.class);

    private final SeedService seed;
    private final JobsService jobs;
    private final ObjectMapper json;

    public Commands(SeedService seed, JobsService jobs, ObjectMapper json) {
        this.seed = seed;
        this.jobs = jobs;
        this.json = json;
    }

    static final java.util.Set<String> NAMES = java.util.Set.of("migrate", "seed", "reset", "jobs");

    /** The command named in the arguments, or null to run the API. */
    public static String of(String[] args) {
        for (String a : args) if (NAMES.contains(a)) return a;
        return null;
    }

    public int run(String command) {
        try {
            if ("migrate".equals(command)) log.info("Database schema is up to date");
            else if ("seed".equals(command)) seed.run();
            else if ("reset".equals(command)) {
                seed.clearStoredFiles(); // the schema was emptied and re-migrated while the app started
                seed.run();
            }
            else System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(jobs.runJobs(Instant.now())));
            return 0;
        } catch (Exception e) {
            log.error("{} failed", command, e);
            return 1;
        }
    }
}
