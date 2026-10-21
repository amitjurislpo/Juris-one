package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "salary_structures")
public class SalaryStructure {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "basic", nullable = false)
    private Integer basic;  // monthly, paise

    @Column(name = "hra", nullable = false)
    private Integer hra;

    @Column(name = "lta", nullable = false)
    private Integer lta;

    @Column(name = "special", nullable = false)
    private Integer special;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "reason", nullable = false, columnDefinition = "\"StructureReason\"")
    private StructureReason reason;

    @Column(name = "created_by")
    private UUID createdById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    private Employee employee;

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
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public Integer getBasic() { return basic; }
    public void setBasic(Integer basic) { this.basic = basic; }
    public Integer getHra() { return hra; }
    public void setHra(Integer hra) { this.hra = hra; }
    public Integer getLta() { return lta; }
    public void setLta(Integer lta) { this.lta = lta; }
    public Integer getSpecial() { return special; }
    public void setSpecial(Integer special) { this.special = special; }
    public StructureReason getReason() { return reason; }
    public void setReason(StructureReason reason) { this.reason = reason; }
    public UUID getCreatedById() { return createdById; }
    public void setCreatedById(UUID createdById) { this.createdById = createdById; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    /** Read-only navigation; set employeeId to change it. */
    public Employee getEmployee() { return employee; }
    /** Read-only navigation; set createdById to change it. */
    public Employee getCreatedBy() { return createdBy; }
}
