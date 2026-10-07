package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "departments")
public class Department {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "head_employee_id")
    private UUID headEmployeeId;

    @Column(name = "description")
    private String description;

    @Column(name = "created_by")
    private UUID createdById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "head_employee_id", insertable = false, updatable = false)
    private Employee head;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", insertable = false, updatable = false)
    private Employee createdBy;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public UUID getHeadEmployeeId() { return headEmployeeId; }
    public void setHeadEmployeeId(UUID headEmployeeId) { this.headEmployeeId = headEmployeeId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getCreatedById() { return createdById; }
    public void setCreatedById(UUID createdById) { this.createdById = createdById; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
    /** Read-only navigation; set headEmployeeId to change it. */
    public Employee getHead() { return head; }
    /** Read-only navigation; set createdById to change it. */
    public Employee getCreatedBy() { return createdBy; }
}
