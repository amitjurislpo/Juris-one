package com.jurislpo.jurisone.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One row per job execution key (e.g. attendance.lock:2026-09) — makes scheduled jobs idempotent. */
@Entity
@Table(name = "job_runs")
public class JobRun {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "key", nullable = false)
    private String key;

    @Column(name = "ran_at", nullable = false)
    private Instant ranAt;

    @Column(name = "ok", nullable = false)
    private Boolean isSuccessful = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail")
    private JsonNode detail;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (ranAt == null) ranAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public Instant getRanAt() { return ranAt; }
    public void setRanAt(Instant ranAt) { this.ranAt = ranAt; }
    public Boolean isSuccessful() { return isSuccessful; }
    public void setSuccessful(Boolean isSuccessful) { this.isSuccessful = isSuccessful; }
    public JsonNode getDetail() { return detail; }
    public void setDetail(JsonNode detail) { this.detail = detail; }
}
