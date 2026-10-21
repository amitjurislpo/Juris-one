package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "roles")
public class Role {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "key", nullable = false, unique = true)
    private String key;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "is_builtin", nullable = false)
    private Boolean isBuiltin = false;

    @Column(name = "is_locked", nullable = false)
    private Boolean isLocked = false;  // Admin role: permissions cannot be edited

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getIsBuiltin() { return isBuiltin; }
    public void setIsBuiltin(Boolean isBuiltin) { this.isBuiltin = isBuiltin; }
    public Boolean getIsLocked() { return isLocked; }
    public void setIsLocked(Boolean isLocked) { this.isLocked = isLocked; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
