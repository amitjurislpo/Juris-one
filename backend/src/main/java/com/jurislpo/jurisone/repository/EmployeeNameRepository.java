package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.crypto.EmployeeNameReader;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Every employee's name and designation of the current business (row-level security applies), for in-memory search and sort. */
@Repository
public class EmployeeNameRepository {

    public record NameRow(UUID id, String fullName, String designation) {}

    private final JdbcTemplate jdbc;
    private final EmployeeNameReader nameReader;

    public EmployeeNameRepository(JdbcTemplate jdbc, EmployeeNameReader nameReader) {
        this.jdbc = jdbc;
        this.nameReader = nameReader;
    }

    /** At most {@code limit} rows, so the caller can tell that there are more. */
    public List<NameRow> findAll(int limit) {
        return jdbc.query("SELECT id, full_name, full_name_enc, designation, designation_enc FROM employees LIMIT ?",
                (resultSet, i) -> new NameRow((UUID) resultSet.getObject("id"), nameReader.read(resultSet, "full_name"), nameReader.read(resultSet, "designation")), limit);
    }
}
