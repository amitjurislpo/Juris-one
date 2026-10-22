package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "declaration_items")
public class DeclarationItem {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "fy", nullable = false)
    private String financialYear;

    @Column(name = "section", nullable = false)
    private String section;

    @Column(name = "item_key", nullable = false)
    private String itemKey;

    @Column(name = "amount", nullable = false)
    private Long amount = 0L;  // paise

    @Column(name = "proof_path")
    private String proofPath;

    @Column(name = "proof_name")
    private String proofName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "\"DeclarationStatus\"")
    private DeclarationStatus status = DeclarationStatus.DRAFT;

    @Column(name = "decided_by")
    private UUID decidedById;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by", insertable = false, updatable = false)
    private Employee decidedBy;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public String getFinancialYear() { return financialYear; }
    public void setFinancialYear(String financialYear) { this.financialYear = financialYear; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public String getItemKey() { return itemKey; }
    public void setItemKey(String itemKey) { this.itemKey = itemKey; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getProofPath() { return proofPath; }
    public void setProofPath(String proofPath) { this.proofPath = proofPath; }
    public String getProofName() { return proofName; }
    public void setProofName(String proofName) { this.proofName = proofName; }
    public DeclarationStatus getStatus() { return status; }
    public void setStatus(DeclarationStatus status) { this.status = status; }
    public UUID getDecidedById() { return decidedById; }
    public void setDecidedById(UUID decidedById) { this.decidedById = decidedById; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    /** Read-only navigation; set employeeId to change it. */
    public Employee getEmployee() { return employee; }
    /** Read-only navigation; set decidedById to change it. */
    public Employee getDecidedBy() { return decidedBy; }
}
