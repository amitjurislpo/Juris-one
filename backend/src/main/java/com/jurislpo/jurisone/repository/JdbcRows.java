package com.jurislpo.jurisone.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns the database values {@code queryForList} hands back that JSON cannot carry into plain values: SQL arrays become lists, and
 * columns selected as {@code json_column::text} can be parsed into JSON trees.
 */
public final class JdbcRows {
    private JdbcRows() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The same rows, changed in place: every SQL array becomes a list (UUIDs as text). */
    public static List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getValue() instanceof Array array) entry.setValue(list(array));
            }
        }
        return rows;
    }

    /** The same rows, changed in place: the named text columns holding JSON become JSON trees. */
    public static List<Map<String, Object>> parseJson(List<Map<String, Object>> rows, String... keys) {
        for (Map<String, Object> row : rows) {
            for (String key : keys) {
                Object value = row.get(key);
                if (value instanceof String text) {
                    try {
                        row.put(key, JSON.readTree(text));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return rows;
    }

    static List<Object> list(Array array) {
        try {
            Object[] items = (Object[]) array.getArray();
            List<Object> list = new ArrayList<>(items.length);
            for (Object item : items) list.add(item == null || item instanceof Number || item instanceof Boolean ? item : item.toString());
            return list;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
