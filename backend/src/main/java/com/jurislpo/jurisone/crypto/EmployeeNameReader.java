package com.jurislpo.jurisone.crypto;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stage 2 of encrypting employee names and designations: the one place that turns a stored name or designation into text.
 * Switch: {@code jurisone.security.encrypt-names.read-encrypted} (off by default in the code and in every profile, dev included; sqe, uat and prd set it from
 * ENCRYPT_NAMES_READ_ENCRYPTED). When it is on the value comes from the encrypted column ({@code *_enc}); a row whose encrypted copy is still
 * empty (the backfill has not reached it) falls back to the plaintext column and is counted. Values are never logged, only counts.
 * <p>
 * Convention for SQL: next to every selected name column the query selects its encrypted twin under the same alias plus {@code _enc}
 * ({@code e.full_name, e.full_name_enc} or {@code m.full_name AS manager, m.full_name_enc AS manager_enc}); this class reads the pair.
 */
@Component
public class EmployeeNameReader {

    private static final Logger log = LoggerFactory.getLogger(EmployeeNameReader.class);
    private static final String ENCRYPTED_SUFFIX = "_enc";
    private static final long LOG_EVERY = 1000;

    /**
     * How names are ordered in lists: by character code, which is what {@code ORDER BY} did on the database the app runs on (the "C" collation of the
     * postgres:16-alpine image), so switching the encrypted reads on does not reorder a list.
     */
    public static final Comparator<String> NAME_ORDER = Comparator.nullsFirst(Comparator.<String>naturalOrder());

    private final PiiCrypto piiCrypto;
    private final boolean isReadEncrypted;
    private final AtomicLong fallbackCount = new AtomicLong();
    private final AtomicLong undecryptableCount = new AtomicLong();

    public EmployeeNameReader(PiiCrypto piiCrypto, @Value("${jurisone.security.encrypt-names.read-encrypted:false}") boolean isReadEncrypted) {
        this.piiCrypto = piiCrypto;
        this.isReadEncrypted = isReadEncrypted;
    }

    public boolean isReadEncrypted() {
        return isReadEncrypted;
    }

    /** The text to show: the decrypted copy when the switch is on and the copy exists, else the plaintext column. */
    public String read(String plain, String encrypted) {
        if (!isReadEncrypted) return plain;
        if (encrypted == null || encrypted.isEmpty()) {
            if (plain != null && !plain.isEmpty()) {
                long count = fallbackCount.incrementAndGet();
                if (count % LOG_EVERY == 1) log.warn("Employee name read fell back to the plaintext column because the encrypted copy is missing ({} so far); run the encrypt-existing command", count);
            }
            return plain;
        }
        try {
            return piiCrypto.decrypt(encrypted);
        } catch (RuntimeException e) {
            long count = undecryptableCount.incrementAndGet();
            if (count % LOG_EVERY == 1) log.error("Employee name read: an encrypted copy could not be decrypted, the plaintext column was used ({} so far)", count);
            return plain;
        }
    }

    /** The full name of an employee loaded through JPA. */
    public String fullName(com.jurislpo.jurisone.entity.Employee employee) {
        return read(employee.getFullName(), employee.getFullNameEnc());
    }

    /** {@code column} and {@code column_enc} of the current row. */
    public String read(ResultSet resultSet, String column) throws SQLException {
        return read(resultSet.getString(column), isReadEncrypted ? resultSet.getString(column + ENCRYPTED_SUFFIX) : null);
    }

    /** Replaces each listed column of a row map by its resolved text and removes the {@code _enc} twin, so the map looks as it always did. */
    public Map<String, Object> resolve(Map<String, Object> row, String... columns) {
        for (String column : columns) {
            Object encrypted = row.remove(column + ENCRYPTED_SUFFIX);
            if (row.containsKey(column)) row.put(column, read((String) row.get(column), (String) encrypted));
        }
        return row;
    }

    /**
     * For a column holding a list of names ({@code array_agg}), with its {@code _enc} twin list in the same order: replaces it by the resolved
     * names, ordered by name, and removes the twin.
     */
    public List<Map<String, Object>> resolveNameLists(List<Map<String, Object>> rows, String... columns) {
        for (Map<String, Object> row : rows) {
            for (String column : columns) {
                List<?> encrypted = (List<?>) row.remove(column + ENCRYPTED_SUFFIX);
                if (!(row.get(column) instanceof List<?> plain)) continue;
                List<String> names = new java.util.ArrayList<>(plain.size());
                for (int i = 0; i < plain.size(); i++) names.add(read((String) plain.get(i), encrypted == null ? null : (String) encrypted.get(i)));
                names.sort(NAME_ORDER);
                row.put(column, names);
            }
        }
        return rows;
    }

    public List<Map<String, Object>> resolveAll(List<Map<String, Object>> rows, String... columns) {
        for (Map<String, Object> row : rows) resolve(row, columns);
        return rows;
    }

    /** How many reads used the plaintext column because the encrypted copy was missing since start-up. */
    public long fallbackCount() {
        return fallbackCount.get();
    }

    public long undecryptableCount() {
        return undecryptableCount.get();
    }
}
