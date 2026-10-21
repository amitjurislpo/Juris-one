package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "attendance_days")
@IdClass(AttendanceDay.Key.class)
public class AttendanceDay {

    /** Composite primary key. */
    public static class Key implements Serializable {
        private UUID employeeId;
        private LocalDate workDate;

        public Key() {}
        public Key(UUID employeeId, LocalDate workDate) { this.employeeId = employeeId; this.workDate = workDate; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
            return Objects.equals(employeeId, other.employeeId) && Objects.equals(workDate, other.workDate);
        }
        @Override public int hashCode() { return Objects.hash(employeeId, workDate); }
    }

    @Id
    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Id
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;  // shift start date, employee's zone

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "\"DayStatus\"")
    private DayStatus status;

    @Column(name = "first_in")
    private Instant firstIn;

    @Column(name = "last_out")
    private Instant lastOut;

    @Column(name = "worked_minutes", nullable = false)
    private Integer workedMinutes = 0;

    @Column(name = "late", nullable = false)
    private Boolean late = false;

    @Column(name = "late_waived", nullable = false)
    private Boolean lateWaived = false;

    @Column(name = "regularized", nullable = false)
    private Boolean regularized = false;

    @Column(name = "missed_checkout", nullable = false)
    private Boolean missedCheckout = false;

    @Column(name = "lop_days", nullable = false, precision = 3, scale = 1)
    private BigDecimal lopDays = BigDecimal.ZERO;

    @Column(name = "leave_days", nullable = false, precision = 3, scale = 1)
    private BigDecimal leaveDays = BigDecimal.ZERO;

    @Column(name = "label")
    private String label;

    @Column(name = "locked", nullable = false)
    private Boolean locked = false;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    private Employee employee;

    @PrePersist
    void prePersist() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public DayStatus getStatus() { return status; }
    public void setStatus(DayStatus status) { this.status = status; }
    public Instant getFirstIn() { return firstIn; }
    public void setFirstIn(Instant firstIn) { this.firstIn = firstIn; }
    public Instant getLastOut() { return lastOut; }
    public void setLastOut(Instant lastOut) { this.lastOut = lastOut; }
    public Integer getWorkedMinutes() { return workedMinutes; }
    public void setWorkedMinutes(Integer workedMinutes) { this.workedMinutes = workedMinutes; }
    public Boolean getLate() { return late; }
    public void setLate(Boolean late) { this.late = late; }
    public Boolean getLateWaived() { return lateWaived; }
    public void setLateWaived(Boolean lateWaived) { this.lateWaived = lateWaived; }
    public Boolean getRegularized() { return regularized; }
    public void setRegularized(Boolean regularized) { this.regularized = regularized; }
    public Boolean getMissedCheckout() { return missedCheckout; }
    public void setMissedCheckout(Boolean missedCheckout) { this.missedCheckout = missedCheckout; }
    public BigDecimal getLopDays() { return lopDays; }
    public void setLopDays(BigDecimal lopDays) { this.lopDays = lopDays; }
    public BigDecimal getLeaveDays() { return leaveDays; }
    public void setLeaveDays(BigDecimal leaveDays) { this.leaveDays = leaveDays; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Boolean getLocked() { return locked; }
    public void setLocked(Boolean locked) { this.locked = locked; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    /** Read-only navigation; set employeeId to change it. */
    public Employee getEmployee() { return employee; }
}
