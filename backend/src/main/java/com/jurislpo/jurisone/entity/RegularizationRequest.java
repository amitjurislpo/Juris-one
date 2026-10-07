package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "regularization_requests")
public class RegularizationRequest {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "kind", nullable = false, columnDefinition = "\"RegularizationKind\"")
    private RegularizationKind kind;

    @Column(name = "proposed_in")
    private Instant proposedIn;

    @Column(name = "proposed_out")
    private Instant proposedOut;

    @Column(name = "reason")
    private String reason;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "\"RequestStatus\"")
    private RequestStatus status = RequestStatus.PENDING;

    @Column(name = "decided_by")
    private UUID decidedById;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_role")
    private String decidedRole;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by", insertable = false, updatable = false)
    private Employee decidedBy;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public RegularizationKind getKind() { return kind; }
    public void setKind(RegularizationKind kind) { this.kind = kind; }
    public Instant getProposedIn() { return proposedIn; }
    public void setProposedIn(Instant proposedIn) { this.proposedIn = proposedIn; }
    public Instant getProposedOut() { return proposedOut; }
    public void setProposedOut(Instant proposedOut) { this.proposedOut = proposedOut; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public UUID getDecidedById() { return decidedById; }
    public void setDecidedById(UUID decidedById) { this.decidedById = decidedById; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public String getDecidedRole() { return decidedRole; }
    public void setDecidedRole(String decidedRole) { this.decidedRole = decidedRole; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    /** Read-only navigation; set employeeId to change it. */
    public Employee getEmployee() { return employee; }
    /** Read-only navigation; set decidedById to change it. */
    public Employee getDecidedBy() { return decidedBy; }
}
