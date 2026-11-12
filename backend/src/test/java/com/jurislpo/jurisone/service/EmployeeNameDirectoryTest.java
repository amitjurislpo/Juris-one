package com.jurislpo.jurisone.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jurislpo.jurisone.crypto.EmployeeNameReader;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.EmployeeNameRepository;
import com.jurislpo.jurisone.repository.EmployeeNameRepository.NameRow;
import com.jurislpo.jurisone.service.EmployeeNameDirectory.Field;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The held names: matching, ranking, the 60-second life, the invalidation and the size limit. */
class EmployeeNameDirectoryTest {

    static final PiiCrypto CRYPTO = new PiiCrypto("BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=");
    static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");

    final EmployeeNameRepository repository = mock(EmployeeNameRepository.class);
    final MutableClock clock = new MutableClock();

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-05T10:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    EmployeeNameDirectory directory(boolean isReadEncrypted) {
        when(repository.findAll(EmployeeNameDirectory.MAX_PEOPLE_PER_BUSINESS + 1))
                .thenAnswer(call -> List.of(new NameRow(SECOND, "Zoya Khan", "engineer"), new NameRow(FIRST, "aarav Mehta", "Senior Engineer"), new NameRow(THIRD, "Meera 100%_Joshi", null)));
        return new EmployeeNameDirectory(repository, new EmployeeNameReader(CRYPTO, isReadEncrypted), clock);
    }

    @Test
    void withTheSwitchOffTheSqlIsTheOldPlaintextSqlAndNothingIsLoaded() {
        EmployeeNameDirectory directory = directory(false);
        List<Object> args = new ArrayList<>();
        assertThat(directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "100%_", args)).isEqualTo("e.full_name ILIKE ?");
        assertThat(args).containsExactly("%100\\%\\_%");
        List<Object> orderArgs = new ArrayList<>();
        assertThat(directory.orderBy(Field.DESIGNATION, "e.id", "e.designation", false, orderArgs)).isEqualTo("e.designation DESC");
        assertThat(orderArgs).isEmpty();
        assertThat(directory.loadCount()).isZero();
    }

    @Test
    void searchIsAnInsensitiveLiteralSubstringMatchOnTheHeldNames() {
        TenantContext.set(UUID.randomUUID());
        EmployeeNameDirectory directory = directory(true);
        List<Object> args = new ArrayList<>();
        assertThat(directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "ENGINEER", args)).isEqualTo("e.id = ANY (?::uuid[])");
        assertThat(args).containsExactly("{}"); // "engineer" is a designation, not a name
        args.clear();
        directory.contains(Field.DESIGNATION, "e.id", "e.designation", "ENGINEER", args);
        assertThat(args).containsExactly("{" + SECOND + "," + FIRST + "}");
        args.clear();
        directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "%_", args);
        assertThat(args).containsExactly("{" + THIRD + "}"); // % and _ are plain characters
        args.clear();
        directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "'; drop table employees; --\u0000", args);
        assertThat(args).containsExactly("{}");
    }

    @Test
    void rankingIsByCharacterCodeThenIdWithNullsLast() {
        TenantContext.set(UUID.randomUUID());
        EmployeeNameDirectory directory = directory(true);
        List<Object> args = new ArrayList<>();
        assertThat(directory.orderBy(Field.FULL_NAME, "e.id", "e.full_name", true, args)).isEqualTo("array_position(?::uuid[], e.id) ASC NULLS LAST");
        assertThat(args).containsExactly("{" + THIRD + "," + SECOND + "," + FIRST + "}"); // "Meera", "Zoya", "aarav": the order the C collation gave
        args.clear();
        assertThat(directory.orderBy(Field.DESIGNATION, "e.id", "e.designation", false, args)).endsWith("ASC NULLS FIRST");
        assertThat(args).containsExactly("{" + SECOND + "," + FIRST + "," + THIRD + "}"); // descending: "engineer" > "Senior Engineer" > no designation (which the database would put first)
    }

    @Test
    void theNamesAreHeldForSixtySecondsAndDroppedByInvalidate() {
        TenantContext.set(UUID.randomUUID());
        EmployeeNameDirectory directory = directory(true);
        List<Object> args = new ArrayList<>();
        directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "a", args);
        directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "b", args);
        assertThat(directory.loadCount()).isEqualTo(1);
        clock.now = clock.now.plus(Duration.ofSeconds(59));
        directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "c", args);
        assertThat(directory.loadCount()).isEqualTo(1);
        clock.now = clock.now.plus(Duration.ofSeconds(2));
        directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "d", args);
        assertThat(directory.loadCount()).isEqualTo(2);
        directory.invalidate();
        directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "e", args);
        assertThat(directory.loadCount()).isEqualTo(3);
    }

    @Test
    void eachBusinessHasItsOwnNamesAndSystemModeHoldsNothing() {
        EmployeeNameDirectory directory = directory(true);
        List<Object> args = new ArrayList<>();
        TenantContext.set(UUID.randomUUID());
        directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "a", args);
        TenantContext.set(UUID.randomUUID());
        directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "a", args);
        assertThat(directory.loadCount()).isEqualTo(2);
        TenantContext.clear();
        directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "a", args);
        directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "a", args);
        assertThat(directory.loadCount()).isEqualTo(4);
    }

    @Test
    void moreThanFiveThousandPeopleAreRefusedWithAHelpfulMessage() {
        TenantContext.set(UUID.randomUUID());
        List<NameRow> tooMany = new ArrayList<>();
        for (int i = 0; i <= EmployeeNameDirectory.MAX_PEOPLE_PER_BUSINESS; i++) tooMany.add(new NameRow(UUID.randomUUID(), "Person " + i, "x"));
        when(repository.findAll(EmployeeNameDirectory.MAX_PEOPLE_PER_BUSINESS + 1)).thenReturn(tooMany);
        EmployeeNameDirectory directory = new EmployeeNameDirectory(repository, new EmployeeNameReader(CRYPTO, true), clock);
        assertThatThrownBy(() -> directory.contains(Field.FULL_NAME, "e.id", "e.full_name", "a", new ArrayList<>())).isInstanceOf(UserException.class)
                .hasMessageContaining("Narrow your search").hasMessageNotContaining("Person 1");
    }
}
