package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "password_hash")
    private String passwordHash;  // argon2id; null until invite accepted

    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_set_at")
    private Instant passwordSetAt;

    @Column(name = "is_super_admin", nullable = false)
    private Boolean isSuperAdmin = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    private Employee employee;

    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Integer getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(Integer failedAttempts) { this.failedAttempts = failedAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Instant getPasswordSetAt() { return passwordSetAt; }
    public void setPasswordSetAt(Instant passwordSetAt) { this.passwordSetAt = passwordSetAt; }
    public Boolean getIsSuperAdmin() { return isSuperAdmin; }
    public void setIsSuperAdmin(Boolean isSuperAdmin) { this.isSuperAdmin = isSuperAdmin; }
    /** Read-only navigation; set employeeId to change it. */
    public Employee getEmployee() { return employee; }
}
