package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "leave_requests")
public class LeaveRequest {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "leave_type", nullable = false)
    private String leaveType;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "part", nullable = false, columnDefinition = "\"LeavePart\"")
    private LeavePart part = LeavePart.FULL;

    @Column(name = "days", nullable = false, precision = 5, scale = 2)
    private BigDecimal days;

    @Column(name = "lop_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal lopDays = BigDecimal.ZERO;

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

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by", insertable = false, updatable = false)
    private Employee decidedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_type", insertable = false, updatable = false)
    private LeaveType type;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (appliedAt == null) appliedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public String getLeaveType() { return leaveType; }
    public void setLeaveType(String leaveType) { this.leaveType = leaveType; }
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate fromDate) { this.fromDate = fromDate; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate toDate) { this.toDate = toDate; }
    public LeavePart getPart() { return part; }
    public void setPart(LeavePart part) { this.part = part; }
    public BigDecimal getDays() { return days; }
    public void setDays(BigDecimal days) { this.days = days; }
    public BigDecimal getLopDays() { return lopDays; }
    public void setLopDays(BigDecimal lopDays) { this.lopDays = lopDays; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public UUID getDecidedById() { return decidedById; }
    public void setDecidedById(UUID decidedById) { this.decidedById = decidedById; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
    /** Read-only navigation; set employeeId to change it. */
    public Employee getEmployee() { return employee; }
    /** Read-only navigation; set decidedById to change it. */
    public Employee getDecidedBy() { return decidedBy; }
    /** Read-only navigation; set leaveType to change it. */
    public LeaveType getType() { return type; }
}
