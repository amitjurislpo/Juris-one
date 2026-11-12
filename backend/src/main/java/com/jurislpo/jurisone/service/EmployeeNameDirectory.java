package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.crypto.EmployeeNameReader;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.EmployeeNameRepository;
import com.jurislpo.jurisone.repository.PeopleQuery;
import com.jurislpo.jurisone.repository.EmployeeNameRepository.NameRow;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Search and sort on employee names and designations once they are encrypted: the database cannot compare them, so the business's
 * decrypted names are held in memory for a short time (60 seconds, dropped on every employee write, at most 5,000 people per business)
 * and the lists ask this class for the matching or ranked ids, which the SQL then uses ({@code id = ANY (?)}, {@code array_position}).
 * With the switch off ({@code read-encrypted=false}) the same methods return the old plaintext SQL (ILIKE / ORDER BY), so a list has one code path.
 * The held names are never logged or returned by an endpoint. In system mode (no business) nothing is held.
 */
@Component
public class EmployeeNameDirectory {

    public static final int MAX_PEOPLE_PER_BUSINESS = 5000;
    private static final long TIME_TO_LIVE_SECONDS = 60;

    /** Which field a search or a sort looks at. */
    public enum Field { FULL_NAME, DESIGNATION }

    private record Entry(UUID id, String fullName, String fullNameLower, String designation, String designationLower) {}

    private record Snapshot(List<Entry> entries, Instant loadedAt) {}

    private final EmployeeNameRepository repository;
    private final EmployeeNameReader nameReader;
    private final Clock clock;
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private volatile long loadCount;

    @Autowired
    public EmployeeNameDirectory(EmployeeNameRepository repository, EmployeeNameReader nameReader) {
        this(repository, nameReader, Clock.systemUTC());
    }

    EmployeeNameDirectory(EmployeeNameRepository repository, EmployeeNameReader nameReader, Clock clock) {
        this.repository = repository;
        this.nameReader = nameReader;
        this.clock = clock;
    }

    public boolean isInMemory() {
        return nameReader.isReadEncrypted();
    }

    /** How many times the names were loaded from the database since start-up (for tests and monitoring). */
    public long loadCount() {
        return loadCount;
    }

    // ── SQL pieces ──

    /**
     * A WHERE condition that matches rows whose {@code field} contains {@code search} (case-insensitive, the text taken literally).
     * Appends its bound value to {@code args}. {@code idColumn} is the employee's id column in the query, {@code plainColumn} its plaintext twin.
     */
    public String contains(Field field, String idColumn, String plainColumn, String search, List<Object> args) {
        if (!isInMemory()) {
            args.add(PeopleQuery.like(search));
            return plainColumn + " ILIKE ?";
        }
        String needle = search.toLowerCase(Locale.ROOT);
        List<UUID> ids = new ArrayList<>();
        for (Entry entry : snapshot()) {
            String haystack = field == Field.FULL_NAME ? entry.fullNameLower() : entry.designationLower();
            if (haystack != null && haystack.contains(needle)) ids.add(entry.id());
        }
        args.add(uuidArray(ids));
        return idColumn + " = ANY (?::uuid[])";
    }

    /** An ORDER BY term that sorts rows by {@code field}, ascending or descending, with the value it needs appended to {@code args}. */
    public String orderBy(Field field, String idColumn, String plainColumn, boolean isAscending, List<Object> args) {
        if (!isInMemory()) return plainColumn + " " + (isAscending ? "ASC" : "DESC");
        List<Entry> ordered = new ArrayList<>(snapshot());
        Comparator<Entry> byField = Comparator.comparing(field == Field.FULL_NAME ? Entry::fullName : Entry::designation, EmployeeNameReader.NAME_ORDER);
        // The direction is applied here, not by the database, so that ties keep their own order (name ascending, then id) as the plaintext SQL does with its tie-break.
        // The list position is then always read ascending; a row that is not in the list (no manager) is placed like the database places a NULL: last when ascending, first when descending.
        ordered.sort((isAscending ? byField : byField.reversed()).thenComparing(Entry::fullName, EmployeeNameReader.NAME_ORDER).thenComparing(Entry::id));
        args.add(uuidArray(ordered.stream().map(Entry::id).toList()));
        return "array_position(?::uuid[], " + idColumn + ") ASC NULLS " + (isAscending ? "LAST" : "FIRST");
    }

    // ── cache ──

    /** Forgets the held names of the current business (all businesses in system mode); called after any change to a name or designation, once the change is committed. */
    public void invalidate() {
        UUID business = TenantContext.current();
        Runnable drop = () -> {
            if (business == null) snapshots.clear();
            else snapshots.remove(business);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    drop.run();
                }
            });
        } else {
            drop.run();
        }
    }

    private List<Entry> snapshot() {
        UUID business = TenantContext.current();
        Instant now = clock.instant();
        Snapshot held = business == null ? null : snapshots.get(business);
        if (held != null && now.isBefore(held.loadedAt().plusSeconds(TIME_TO_LIVE_SECONDS))) return held.entries();
        List<NameRow> rows = repository.findAll(MAX_PEOPLE_PER_BUSINESS + 1);
        loadCount++;
        if (rows.size() > MAX_PEOPLE_PER_BUSINESS) throw new UserException("There are too many people to search or sort by name at once. Narrow your search with a filter and try again.");
        List<Entry> entries = new ArrayList<>(rows.size());
        for (NameRow row : rows) {
            entries.add(new Entry(row.id(), row.fullName(), row.fullName() == null ? null : row.fullName().toLowerCase(Locale.ROOT),
                    row.designation(), row.designation() == null ? null : row.designation().toLowerCase(Locale.ROOT)));
        }
        if (business != null) snapshots.put(business, new Snapshot(entries, now));
        return entries;
    }

    private static String uuidArray(List<UUID> ids) {
        StringBuilder text = new StringBuilder("{");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) text.append(',');
            text.append(ids.get(i));
        }
        return text.append('}').toString();
    }
}
