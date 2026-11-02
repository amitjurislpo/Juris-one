package com.jurislpo.jurisone.security;

import static com.jurislpo.jurisone.security.TenantHostMismatchReporter.Mismatch.ANONYMOUS_ON_UNKNOWN_HOST;
import static com.jurislpo.jurisone.security.TenantHostMismatchReporter.Mismatch.BUSINESS_USER_ON_OTHER_BUSINESS_HOST;
import static com.jurislpo.jurisone.security.TenantHostMismatchReporter.Mismatch.BUSINESS_USER_ON_PLATFORM_HOST;
import static com.jurislpo.jurisone.security.TenantHostMismatchReporter.Mismatch.HOST_UNKNOWN;
import static com.jurislpo.jurisone.security.TenantHostMismatchReporter.Mismatch.PLATFORM_USER_ON_BUSINESS_HOST;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TenantHostMismatchReporterTest {

    static final long SECOND = 1_000_000_000L;
    static final UUID ACME = UUID.randomUUID();
    static final UUID GLOBEX = UUID.randomUUID();

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final AtomicLong now = new AtomicLong(0);
    final TenantHostMismatchReporter reporter = new TenantHostMismatchReporter(meters, now::get);
    final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    final Logger logger = (Logger) LoggerFactory.getLogger(TenantHostMismatchReporter.class);

    @BeforeEach
    void capture() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    static CurrentUser user(UUID businessId) {
        return new CurrentUser(UUID.randomUUID(), "E1", "Secret Name", "secret.person@example.test", "Associate", UUID.randomUUID(), "employee", "Employee", Set.of("self"),
                businessId == null, "IN", "Asia/Kolkata", false, "session-secret", businessId, null);
    }

    static HostBusiness acmeHost() {
        return HostBusiness.business(ACME, "acme");
    }

    double count(TenantHostMismatchReporter.Mismatch mismatch) {
        var counter = meters.find(TenantHostMismatchReporter.COUNTER_NAME).tag("kind", mismatch.label).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void classifiesEveryCombination() {
        assertThat(TenantHostMismatchReporter.classify(acmeHost(), user(ACME))).isEmpty();
        assertThat(TenantHostMismatchReporter.classify(HostBusiness.PLATFORM, user(null))).isEmpty();
        assertThat(TenantHostMismatchReporter.classify(acmeHost(), null)).isEmpty();
        assertThat(TenantHostMismatchReporter.classify(HostBusiness.PLATFORM, null)).isEmpty();

        assertThat(TenantHostMismatchReporter.classify(HostBusiness.UNKNOWN, user(ACME))).contains(HOST_UNKNOWN);
        assertThat(TenantHostMismatchReporter.classify(HostBusiness.UNKNOWN, user(null))).contains(HOST_UNKNOWN);
        assertThat(TenantHostMismatchReporter.classify(HostBusiness.UNKNOWN, null)).contains(ANONYMOUS_ON_UNKNOWN_HOST);
        assertThat(TenantHostMismatchReporter.classify(acmeHost(), user(GLOBEX))).contains(BUSINESS_USER_ON_OTHER_BUSINESS_HOST);
        assertThat(TenantHostMismatchReporter.classify(HostBusiness.PLATFORM, user(ACME))).contains(BUSINESS_USER_ON_PLATFORM_HOST);
        assertThat(TenantHostMismatchReporter.classify(acmeHost(), user(null))).contains(PLATFORM_USER_ON_BUSINESS_HOST);
    }

    @Test
    void everyMismatchIsCountedButTheLogIsLimitedToOneLinePerKindPerTenSeconds() {
        for (int i = 0; i < 50; i++) reporter.observe(acmeHost(), user(GLOBEX));
        assertThat(count(BUSINESS_USER_ON_OTHER_BUSINESS_HOST)).isEqualTo(50);
        assertThat(logged.list).hasSize(1);

        reporter.observe(HostBusiness.UNKNOWN, null); // another kind has its own allowance
        assertThat(logged.list).hasSize(2);

        now.addAndGet(9 * SECOND);
        reporter.observe(acmeHost(), user(GLOBEX));
        assertThat(logged.list).hasSize(2);

        now.addAndGet(2 * SECOND);
        reporter.observe(acmeHost(), user(GLOBEX));
        assertThat(logged.list).hasSize(3);
        assertThat(logged.list.get(2).getFormattedMessage()).contains("kind=business_user_on_other_business_host", "alsoSince=50");
        assertThat(count(BUSINESS_USER_ON_OTHER_BUSINESS_HOST)).isEqualTo(52);
        assertThat(count(ANONYMOUS_ON_UNKNOWN_HOST)).isEqualTo(1);
    }

    @Test
    void agreementIsNotCountedOrLogged() {
        reporter.observe(acmeHost(), user(ACME));
        reporter.observe(HostBusiness.PLATFORM, user(null));
        reporter.observe(acmeHost(), null);
        assertThat(meters.find(TenantHostMismatchReporter.COUNTER_NAME).counters()).isEmpty();
        assertThat(logged.list).isEmpty();
    }

    @Test
    void theLogHoldsNoPersonalData() {
        CurrentUser person = user(GLOBEX);
        reporter.observe(acmeHost(), person);
        String line = logged.list.get(0).getFormattedMessage();
        assertThat(line).contains("acme", ACME.toString(), GLOBEX.toString());
        assertThat(line).doesNotContain(person.email(), person.name(), person.sessionId(), person.id().toString());
        assertThat(logged.list.get(0).getLevel().toString()).isEqualTo("WARN");
    }
}
