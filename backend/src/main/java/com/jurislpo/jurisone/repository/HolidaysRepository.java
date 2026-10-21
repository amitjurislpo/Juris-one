package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.service.HolidaysService.Item;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The holiday calendar. */
@Repository
public class HolidaysRepository {

    private final JdbcTemplate jdbc;

    public HolidaysRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every holiday with its creator, by date. */
    public List<Item> findAll() {
        return jdbc.query("""
            SELECT h.id, h.date, h.name, h.region::text AS region, c.full_name AS by
            FROM holidays h LEFT JOIN employees c ON c.id = h.created_by ORDER BY h.date ASC""",
                (rs, i) -> new Item(rs.getString("id"), rs.getDate("date").toLocalDate().toString(), rs.getString("name"), rs.getString("region"),
                        rs.getString("by") == null ? "—" : rs.getString("by")));
    }

    /** The name of a holiday already on this date for one of the three regions (at most one). */
    public List<String> findClashingName(Date date, String region1, String region2, String region3) {
        return jdbc.queryForList("SELECT name FROM holidays WHERE date = ? AND region::text IN (?, ?, ?) LIMIT 1", String.class,
                date, region1, region2, region3);
    }

    public void insert(UUID id, Date date, String name, String region, UUID createdBy) {
        jdbc.update("INSERT INTO holidays (id, date, name, region, created_by, created_at) VALUES (?, ?, ?, ?::\"HolidayRegion\", ?, now())",
                id, date, name, region, createdBy);
    }

    /** date, name and region of a holiday (empty when not found). */
    public List<Map<String, Object>> findById(UUID id) {
        return jdbc.queryForList("SELECT date, name, region::text AS region FROM holidays WHERE id = ?", id);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM holidays WHERE id = ?", id);
    }
}
