package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.config.JurisTrackRosterProperties;
import com.jurislpo.jurisone.exception.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * When the roster goes to JurisTrack, always best effort and never on the request's thread: a JurisTrack problem is logged (no tokens) and
 * never fails or rolls back what the user did.
 * <ul>
 *   <li>{@link #rosterChanged}: an employee was added, edited, moved, deactivated or exited, or a business gained the module. Runs after the
 *       transaction commits, and bursts are coalesced per business (the first change starts a short timer, later ones within it join it), so adding
 *       20 people causes one call.</li>
 *   <li>Once shortly after startup and then every {@code jurisone.juristrack.roster-sync-minutes} (0 = never): the roster of every business that has
 *       the module, so a missed push heals. Off in the one-shot commands ({@code jurisone.jobs-enabled=false}).</li>
 * </ul>
 */
@Component
public class JurisTrackRosterTrigger {

    private static final Logger log = LoggerFactory.getLogger(JurisTrackRosterTrigger.class);
    private static final long STARTUP_DELAY_SECONDS = 10;

    private final JurisTrackRosterService roster;
    private final JurisTrackRosterProperties properties;
    private final boolean jobsEnabled;
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread thread = new Thread(runnable, "juristrack-roster");
        thread.setDaemon(true);
        return thread;
    });

    public JurisTrackRosterTrigger(JurisTrackRosterService roster, JurisTrackRosterProperties properties, @Value("${jurisone.jobs-enabled:true}") boolean jobsEnabled) {
        this.roster = roster;
        this.properties = properties;
        this.jobsEnabled = jobsEnabled;
    }

    @PostConstruct
    void schedulePeriodicResync() {
        if (!jobsEnabled || !roster.isGatewayOn() || properties.rosterSyncMinutes() <= 0) return;
        executor.scheduleWithFixedDelay(this::syncAllBusinesses, properties.rosterSyncMinutes(), properties.rosterSyncMinutes(), TimeUnit.MINUTES);
    }

    @EventListener(ApplicationReadyEvent.class)
    void syncAfterStartup() {
        if (!jobsEnabled || !roster.isGatewayOn() || properties.rosterSyncMinutes() <= 0) return;
        executor.schedule(this::syncAllBusinesses, STARTUP_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    /**
     * The roster of {@code businessId} changed: push it soon. Inside a transaction the push is scheduled only once it has committed (and not at
     * all when it rolls back). Does nothing when the gateway is off; whether the business has the module is checked when the push runs.
     */
    public void rosterChanged(UUID businessId) {
        if (businessId == null || !roster.isGatewayOn()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule(businessId);
                }
            });
        } else {
            schedule(businessId);
        }
    }

    private void schedule(UUID businessId) {
        try {
            pending.computeIfAbsent(businessId, id -> executor.schedule(() -> {
                pending.remove(id);
                pushIfActive(id);
            }, properties.rosterDebounceMillis(), TimeUnit.MILLISECONDS));
        } catch (RuntimeException e) { // the executor is shutting down
            log.warn("[juristrack] roster push for business {} not scheduled: {}", businessId, e.getClass().getSimpleName());
        }
    }

    private void pushIfActive(UUID businessId) {
        try {
            if (roster.isModuleActive(businessId)) roster.syncBusiness(businessId);
        } catch (ApiException e) {
            log.warn("[juristrack] roster push for business {} failed: {} {}", businessId, e.status().value(), e.code());
        } catch (RuntimeException e) {
            log.warn("[juristrack] roster push for business {} failed: {}", businessId, e.getClass().getSimpleName());
        }
    }

    /** The full roster of every business that has the module. Stops at the first "unreachable" (the rest would only wait too). Returns the businesses synced. */
    public int syncAllBusinesses() {
        int synced = 0;
        try {
            List<UUID> businesses = roster.businessesWithModule();
            for (UUID businessId : businesses) {
                try {
                    roster.syncBusiness(businessId);
                    synced++;
                } catch (ApiException e) {
                    log.warn("[juristrack] roster resync of business {} failed: {} {}", businessId, e.status().value(), e.code());
                    if ("JURISTRACK_UNAVAILABLE".equals(e.code())) break;
                } catch (RuntimeException e) {
                    log.warn("[juristrack] roster resync of business {} failed: {}", businessId, e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            log.warn("[juristrack] roster resync failed: {}", e.getClass().getSimpleName());
        }
        return synced;
    }
}
