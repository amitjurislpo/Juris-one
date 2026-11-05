package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.repository.EncryptExistingColumnsRepository;
import com.jurislpo.jurisone.repository.EncryptExistingColumnsRepository.TextColumn;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Command {@code rotate-keys}: after a new key was put first in the keyring ({@code PII_ENCRYPTION_KEY} = new, {@code PII_ENCRYPTION_PREVIOUS_KEYS} = old),
 * moves every encrypted database value to the newest key, then does the same for the stored files ({@link EncryptStoredFilesService}).
 * Which columns: every text column whose name ends in {@code _enc} (found in the database catalog, so columns added later are covered)
 * plus the columns encrypted in place that {@link EncryptExistingColumnsRepository} lists. Table and column names never come from input: they come
 * from the catalog or that fixed list, are checked against a strict pattern and quoted. Values are matched by their exact stored text when written
 * back, so a value changed by someone else meanwhile is never overwritten. A dry run (the default) only counts; {@code --jurisone.encrypt.apply=true} writes.
 * Safe to repeat: values already on the newest key are skipped. Values are never logged.
 */
@Service
public class RotateKeysService {

    private static final Logger log = LoggerFactory.getLogger(RotateKeysService.class);
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final JdbcTemplate jdbc;
    private final PiiCrypto piiCrypto;
    private final EncryptStoredFilesService storedFiles;
    private final boolean isApplying;

    public RotateKeysService(JdbcTemplate jdbc, PiiCrypto piiCrypto, EncryptStoredFilesService storedFiles, @Value("${jurisone.encrypt.apply:false}") boolean isApplying) {
        this.jdbc = jdbc;
        this.piiCrypto = piiCrypto;
        this.storedFiles = storedFiles;
        this.isApplying = isApplying;
    }

    /** Per "table.column": values (or, in a dry run, would-be) moved to the newest key; "failed:table.column" when a column could not be finished; "files.*" from the file pass. */
    public Map<String, Integer> run() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String[] column : columns()) {
            String label = column[0] + "." + column[1];
            try {
                counts.put(label, TenantContext.call(null, () -> rotateColumn(column[0], column[1])));
            } catch (RuntimeException e) {
                counts.put("failed:" + label, 1);
                log.error("Key rotation failed for {}", label); // the column only, never a value
            }
        }
        storedFiles.run().forEach((name, count) -> counts.put("files." + name, count));
        log.info("Key rotation {}: {}", isApplying ? "applied" : "(dry run, nothing written)", counts);
        return counts;
    }

    /** {table, column} pairs to look at: the catalog's {@code *_enc} text columns, then the in-place encrypted ones. */
    List<String[]> columns() {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> out = new java.util.ArrayList<>();
        List<Map<String, Object>> discovered = jdbc.queryForList("SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND data_type IN ('text', 'character varying') AND column_name LIKE '%\\_enc' ORDER BY table_name, column_name");
        for (Map<String, Object> row : discovered) add(out, seen, (String) row.get("table_name"), (String) row.get("column_name"));
        for (TextColumn column : EncryptExistingColumnsRepository.TEXT_COLUMNS) add(out, seen, column.table(), column.column());
        return out;
    }

    private static void add(List<String[]> out, Set<String> seen, String table, String column) {
        if (!IDENTIFIER.matcher(table).matches() || !IDENTIFIER.matcher(column).matches()) return; // never build SQL from an odd name
        if (seen.add(table + "." + column)) out.add(new String[] {table, column});
    }

    private int rotateColumn(String table, String column) {
        String quotedTable = "\"" + table + "\"", quotedColumn = "\"" + column + "\"";
        List<String> values = jdbc.queryForList("SELECT DISTINCT " + quotedColumn + " FROM " + quotedTable + " WHERE " + quotedColumn + " LIKE 'v1.%'", String.class);
        int count = 0;
        for (String stored : values) {
            if (!PiiCrypto.isEncrypted(stored) || piiCrypto.isOnNewestKey(stored)) continue;
            String rotated = piiCrypto.rotate(stored);
            if (!piiCrypto.decrypt(stored).equals(piiCrypto.decrypt(rotated))) throw new IllegalStateException("Round trip failed");
            count += isApplying ? jdbc.update("UPDATE " + quotedTable + " SET " + quotedColumn + " = ? WHERE " + quotedColumn + " = ?", rotated, stored) : 1;
        }
        return count;
    }
}
