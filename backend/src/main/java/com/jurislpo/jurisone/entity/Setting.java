package com.jurislpo.jurisone.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "settings")
public class Setting {

    @Id
    @Column(name = "key", nullable = false)
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value", nullable = false)
    private JsonNode value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public JsonNode getValue() { return value; }
    public void setValue(JsonNode value) { this.value = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
