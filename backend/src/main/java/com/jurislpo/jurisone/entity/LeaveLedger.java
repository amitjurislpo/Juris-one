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
@Table(name = "leave_ledger")
public class LeaveLedger {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "leave_type", nullable = false)
    private String leaveType;

    @Column(name = "delta", nullable = false, precision = 6, scale = 2)
    private BigDecimal delta;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "reason", nullable = false, columnDefinition = "\"LedgerReason\"")
    private LedgerReason reason;

    @Column(name = "ref_id")
    private String refId;

    @Column(name = "note")
    private String note;

    @Column(name = "expires_on")
    private LocalDate expiresOn;  // comp-off credits

    @Column(name = "at", nullable = false)
    private Instant recordedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_type", insertable = false, updatable = false)
    private LeaveType type;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (recordedAt == null) recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public String getLeaveType() { return leaveType; }
    public void setLeaveType(String leaveType) { this.leaveType = leaveType; }
    public BigDecimal getDelta() { return delta; }
    public void setDelta(BigDecimal delta) { this.delta = delta; }
    public LedgerReason getReason() { return reason; }
    public void setReason(LedgerReason reason) { this.reason = reason; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public void setExpiresOn(LocalDate expiresOn) { this.expiresOn = expiresOn; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
    /** Read-only navigation; set employeeId to change it. */
    public Employee getEmployee() { return employee; }
    /** Read-only navigation; set leaveType to change it. */
    public LeaveType getType() { return type; }
}
