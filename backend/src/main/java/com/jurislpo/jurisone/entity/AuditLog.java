package com.jurislpo.jurisone.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@org.hibernate.annotations.Immutable
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "action", nullable = false)
    private String action;  // e.g. auth.login, employee.access_toggle

    @Column(name = "entity", nullable = false)
    private String entity;

    @Column(name = "entity_id")
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before")
    private JsonNode before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after")
    private JsonNode after;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(name = "ip")
    private String ip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", insertable = false, updatable = false)
    private Employee actor;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (at == null) at = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getEntity() { return entity; }
    public void setEntity(String entity) { this.entity = entity; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public JsonNode getBefore() { return before; }
    public void setBefore(JsonNode before) { this.before = before; }
    public JsonNode getAfter() { return after; }
    public void setAfter(JsonNode after) { this.after = after; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    /** Read-only navigation; set actorId to change it. */
    public Employee getActor() { return actor; }
}
