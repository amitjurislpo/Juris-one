package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "login_history")
public class LoginHistory {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "employee_id")
    private UUID employeeId;

    @Column(name = "identifier", nullable = false)
    private String identifier;

    @Column(name = "success", nullable = false)
    private Boolean success;

    @Column(name = "reason")
    private String reason;

    @Column(name = "ip")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "at", nullable = false)
    private Instant attemptedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    private Employee employee;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (attemptedAt == null) attemptedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public String getIdentifier() { return identifier; }
    public void setIdentifier(String identifier) { this.identifier = identifier; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public Instant getAttemptedAt() { return attemptedAt; }
    public void setAttemptedAt(Instant attemptedAt) { this.attemptedAt = attemptedAt; }
    /** Read-only navigation; set employeeId to change it. */
    public Employee getEmployee() { return employee; }
}
