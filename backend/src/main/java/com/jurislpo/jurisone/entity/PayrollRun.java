package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payroll_runs")
public class PayrollRun {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "month", nullable = false, unique = true)
    private String month;  // "2026-09"

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "\"PayrollStatus\"")
    private PayrollStatus status = PayrollStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "stage", nullable = false, columnDefinition = "\"PayrollStage\"")
    private PayrollStage stage = PayrollStage.LOCK_ATTENDANCE;

    @Column(name = "attendance_locked_at")
    private Instant attendanceLockedAt;

    @Column(name = "computed_at")
    private Instant computedAt;

    @Column(name = "approved_by")
    private UUID approvedById;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "closed_by")
    private UUID closedById;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "pay_date")
    private LocalDate payDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by", insertable = false, updatable = false)
    private Employee approvedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by", insertable = false, updatable = false)
    private Employee closedBy;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getMonth() { return month; }
    public void setMonth(String month) { this.month = month; }
    public PayrollStatus getStatus() { return status; }
    public void setStatus(PayrollStatus status) { this.status = status; }
    public PayrollStage getStage() { return stage; }
    public void setStage(PayrollStage stage) { this.stage = stage; }
    public Instant getAttendanceLockedAt() { return attendanceLockedAt; }
    public void setAttendanceLockedAt(Instant attendanceLockedAt) { this.attendanceLockedAt = attendanceLockedAt; }
    public Instant getComputedAt() { return computedAt; }
    public void setComputedAt(Instant computedAt) { this.computedAt = computedAt; }
    public UUID getApprovedById() { return approvedById; }
    public void setApprovedById(UUID approvedById) { this.approvedById = approvedById; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public UUID getClosedById() { return closedById; }
    public void setClosedById(UUID closedById) { this.closedById = closedById; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public LocalDate getPayDate() { return payDate; }
    public void setPayDate(LocalDate payDate) { this.payDate = payDate; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    /** Read-only navigation; set approvedById to change it. */
    public Employee getApprovedBy() { return approvedBy; }
    /** Read-only navigation; set closedById to change it. */
    public Employee getClosedBy() { return closedBy; }
}
