package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.repository.TaskActivityRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The History tab of a task (plan §9): who changed what and when, written in the caller's transaction. The company audit log row
 * of the same request is written by the calling service through {@link AuditService} (one per request).
 */
@Service
@Transactional
public class TaskActivityService {

    public static final int PAGE_SIZE = 50;

    private final TaskActivityRepository repo;
    private final ObjectMapper json;

    public TaskActivityService(TaskActivityRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    /** One activity row; {@code before} / {@code after} carry only the changed fields (long text as {@code {"length": n}}). */
    public void record(UUID actorId, UUID taskId, UUID projectId, String kind, Object before, Object after) {
        repo.insert(UUID.randomUUID(), taskId, projectId, actorId, kind, toJson(before), toJson(after));
    }

    /** {@code {page, take, total, rows}}, newest first. */
    public Map<String, Object> page(UUID taskId, int page) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", page);
        out.put("take", PAGE_SIZE);
        out.put("total", repo.count(taskId));
        out.put("rows", repo.findPage(taskId, PAGE_SIZE, (page - 1) * PAGE_SIZE));
        return out;
    }

    /** {@code {"length": n}} for long text (descriptions, comments): the audit trail never keeps the text itself. */
    public static Map<String, Object> length(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("length", text == null ? 0 : text.length());
        return out;
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
