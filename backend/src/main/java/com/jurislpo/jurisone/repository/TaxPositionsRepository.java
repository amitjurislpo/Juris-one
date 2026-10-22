package com.jurislpo.jurisone.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for {@code TaxPositions}. */
@Repository
public class TaxPositionsRepository {

    private final JdbcTemplate jdbc;

    public TaxPositionsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The latest closed payroll month (empty when no run is closed). */
    public List<String> lastClosedMonth() {
        return jdbc.queryForList("SELECT month FROM payroll_runs WHERE status = 'CLOSED' ORDER BY month DESC LIMIT 1", String.class);
    }

    /** Pending declaration items per employee for a financial year. */
    public Map<UUID, Integer> pendingDeclarationCounts(String financialYear) {
        Map<UUID, Integer> pending = new HashMap<>();
        jdbc.query("SELECT employee_id, count(*) FROM declaration_items WHERE fy = ? AND status = 'PENDING' GROUP BY employee_id",
                resultSet -> { pending.put((UUID) resultSet.getObject(1), resultSet.getInt(2)); }, financialYear);
        return pending;
    }
}
