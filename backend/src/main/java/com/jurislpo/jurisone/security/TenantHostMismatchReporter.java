package com.jurislpo.jurisone.security;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Observe mode: compares the signed-in user's business with the business the host names, counts every mismatch in the Micrometer
 * counter {@code jurisone.tenant_host.mismatch} (tag {@code kind}) and logs a WARN line, at most one per kind every 10 seconds
 * (the line says how many were left out). Nothing here ever changes a response. The log holds the kind, the host's kind, the slug
 * and business ids only, never an email, token or the raw host.
 */
@Component
public class TenantHostMismatchReporter {

    public static final String COUNTER_NAME = "jurisone.tenant_host.mismatch";
    static final long LOG_INTERVAL_NANOS = 10_000_000_000L;

    /** The kinds of mismatch; {@link #label} is the counter tag and the text in the log. */
    public enum Mismatch {
        HOST_UNKNOWN("host_unknown"),
        BUSINESS_USER_ON_OTHER_BUSINESS_HOST("business_user_on_other_business_host"),
        BUSINESS_USER_ON_PLATFORM_HOST("business_user_on_platform_host"),
        PLATFORM_USER_ON_BUSINESS_HOST("platform_user_on_business_host"),
        ANONYMOUS_ON_UNKNOWN_HOST("anonymous_on_unknown_host");

        public final String label;

        Mismatch(String label) {
            this.label = label;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(TenantHostMismatchReporter.class);

    private final MeterRegistry meters;
    private final LongSupplier nanoClock;
    private final AtomicLongArray lastLoggedAt = new AtomicLongArray(Mismatch.values().length);
    private final AtomicLongArray leftOut = new AtomicLongArray(Mismatch.values().length);
    private final boolean[] hasLogged = new boolean[Mismatch.values().length];

    @Autowired
    public TenantHostMismatchReporter(MeterRegistry meters) {
        this(meters, System::nanoTime);
    }

    TenantHostMismatchReporter(MeterRegistry meters, LongSupplier nanoClock) {
        this.meters = meters;
        this.nanoClock = nanoClock;
    }

    /**
     * The mismatch between host and signed-in user, or empty when they agree. A person who is not signed in only counts on an unknown
     * host; a signed-in person on an unknown host is {@code HOST_UNKNOWN}. A platform user is one without a business (the super admin).
     */
    static Optional<Mismatch> classify(HostBusiness host, CurrentUser user) {
        UUID businessId = user == null || user.isSuperAdmin() ? null : user.businessId(); // super admins sit in the platform company but count as platform users
        if (user == null) return host.kind() == HostBusiness.Kind.UNKNOWN ? Optional.of(Mismatch.ANONYMOUS_ON_UNKNOWN_HOST) : Optional.empty();
        switch (host.kind()) {
            case UNKNOWN:
                return Optional.of(Mismatch.HOST_UNKNOWN);
            case PLATFORM:
                return businessId == null ? Optional.empty() : Optional.of(Mismatch.BUSINESS_USER_ON_PLATFORM_HOST);
            default:
                if (businessId == null) return Optional.of(Mismatch.PLATFORM_USER_ON_BUSINESS_HOST);
                return businessId.equals(host.businessId()) ? Optional.empty() : Optional.of(Mismatch.BUSINESS_USER_ON_OTHER_BUSINESS_HOST);
        }
    }

    /** Counts and logs the mismatch, if there is one. */
    public void observe(HostBusiness host, CurrentUser user) {
        classify(host, user).ifPresent(mismatch -> record(mismatch, host, user));
    }

    private void record(Mismatch mismatch, HostBusiness host, CurrentUser user) {
        meters.counter(COUNTER_NAME, "kind", mismatch.label).increment();
        int index = mismatch.ordinal();
        long now = nanoClock.getAsLong();
        boolean isDue;
        synchronized (hasLogged) {
            isDue = !hasLogged[index] || now - lastLoggedAt.get(index) >= LOG_INTERVAL_NANOS;
            if (isDue) {
                hasLogged[index] = true;
                lastLoggedAt.set(index, now);
            }
        }
        if (!isDue) {
            leftOut.incrementAndGet(index);
            return;
        }
        log.warn("tenant host mismatch (observe only): kind={} hostKind={} hostSlug={} hostBusinessId={} userBusinessId={} alsoSince={}",
                mismatch.label, host.kind(), host.slug(), host.businessId(), user == null ? null : user.businessId(), leftOut.getAndSet(index, 0));
    }
}
