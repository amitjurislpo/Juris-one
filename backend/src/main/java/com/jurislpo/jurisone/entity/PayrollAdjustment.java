package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payroll_adjustments")
@IdClass(PayrollAdjustment.Key.class)
public class PayrollAdjustment {

    /** Composite primary key. */
    public static class Key implements Serializable {
        private UUID runId;
        private UUID employeeId;

        public Key() {}
        public Key(UUID runId, UUID employeeId) { this.runId = runId; this.employeeId = employeeId; }

        @Override public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof Key other)) return false;
            return Objects.equals(runId, other.runId) && Objects.equals(employeeId, other.employeeId);
        }
        @Override public int hashCode() { return Objects.hash(runId, employeeId); }
    }

    @Id
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Id
    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "lop_days", precision = 5, scale = 2)
    private BigDecimal lopDays;  // null = use attendance

    @Column(name = "bonus", nullable = false)
    private Integer bonus = 0;  // paise

    @Column(name = "other_deduction", nullable = false)
    private Integer otherDeduction = 0;

    @Column(name = "note")
    private String note;

    @Column(name = "updated_by")
    private UUID updatedById;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", insertable = false, updatable = false)
    private PayrollRun run;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", insertable = false, updatable = false)
    private Employee updatedBy;

    @PrePersist
    void prePersist() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public BigDecimal getLopDays() { return lopDays; }
    public void setLopDays(BigDecimal lopDays) { this.lopDays = lopDays; }
    public Integer getBonus() { return bonus; }
    public void setBonus(Integer bonus) { this.bonus = bonus; }
    public Integer getOtherDeduction() { return otherDeduction; }
    public void setOtherDeduction(Integer otherDeduction) { this.otherDeduction = otherDeduction; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public UUID getUpdatedById() { return updatedById; }
    public void setUpdatedById(UUID updatedById) { this.updatedById = updatedById; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    /** Read-only navigation; set runId to change it. */
    public PayrollRun getRun() { return run; }
    /** Read-only navigation; set employeeId to change it. */
    public Employee getEmployee() { return employee; }
    /** Read-only navigation; set updatedById to change it. */
    public Employee getUpdatedBy() { return updatedBy; }
}
