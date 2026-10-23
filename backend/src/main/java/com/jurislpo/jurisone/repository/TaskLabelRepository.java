package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Task labels: business-wide ({@code project_id IS NULL}) or of one project. */
@Repository
public class TaskLabelRepository {

    public record LabelRow(UUID id, UUID projectId, String name, String color) {}

    private static final String LABEL = "SELECT id::text AS \"id\", name AS \"name\", color AS \"color\", project_id::text AS \"projectId\" FROM task_labels";

    private final JdbcTemplate jdbc;

    public TaskLabelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Business labels, plus the project's when {@code projectId} is given. */
    public List<Map<String, Object>> findForProject(UUID projectId) {
        return jdbc.queryForList(LABEL + " WHERE project_id IS NULL OR project_id = ?::uuid ORDER BY project_id NULLS FIRST, lower(name)", projectId);
    }

    public List<Map<String, Object>> findBusinessLabels() {
        return jdbc.queryForList(LABEL + " WHERE project_id IS NULL ORDER BY lower(name)");
    }

    public List<LabelRow> findById(UUID labelId) {
        return jdbc.query("SELECT id, project_id, name, color FROM task_labels WHERE id = ?", (resultSet, rowNumber) -> new LabelRow((UUID) resultSet.getObject("id"),
                (UUID) resultSet.getObject("project_id"), resultSet.getString("name"), resultSet.getString("color")), labelId);
    }

    public boolean nameTaken(UUID projectId, String name, UUID exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM task_labels WHERE project_id IS NOT DISTINCT FROM ?::uuid AND lower(name) = lower(?) "
                + "AND (?::uuid IS NULL OR id <> ?::uuid))", Boolean.class, projectId, name, exceptId, exceptId));
    }

    public int count(UUID projectId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM task_labels WHERE project_id IS NOT DISTINCT FROM ?::uuid", Integer.class, projectId);
        return count == null ? 0 : count;
    }

    /** How many of {@code labelIds} a task of {@code projectId} (null = personal) may carry: business labels and that project's. */
    public int countUsable(List<UUID> labelIds, UUID projectId) {
        if (labelIds.isEmpty()) return 0;
        String marks = String.join(", ", labelIds.stream().map(id -> "?").toList());
        Object[] args = new Object[labelIds.size() + 1];
        for (int i = 0; i < labelIds.size(); i++) args[i] = labelIds.get(i);
        args[labelIds.size()] = projectId;
        Integer count = jdbc.queryForObject("SELECT count(*) FROM task_labels WHERE id IN (" + marks + ") AND (project_id IS NULL OR project_id = ?::uuid)", Integer.class, args);
        return count == null ? 0 : count;
    }

    public void insert(UUID id, UUID projectId, String name, String color) {
        jdbc.update("INSERT INTO task_labels (id, project_id, name, color) VALUES (?, ?, ?, ?)", id, projectId, name, color);
    }

    public void update(UUID id, String name, String color) {
        jdbc.update("UPDATE task_labels SET name = ?, color = ? WHERE id = ?", name, color, id);
    }

    /** Deletes a label; its links to tasks go with it. Returns the ids of the tasks that carried it. */
    public List<UUID> delete(UUID id) {
        List<UUID> taskIds = jdbc.queryForList("SELECT task_id FROM task_label_links WHERE label_id = ?", UUID.class, id);
        jdbc.update("DELETE FROM task_labels WHERE id = ?", id);
        return taskIds;
    }
}
