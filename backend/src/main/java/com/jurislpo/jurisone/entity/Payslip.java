package com.jurislpo.jurisone.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payslips")
@IdClass(Payslip.Key.class)
public class Payslip {

    /** Composite primary key. */
    public static class Key implements Serializable {
        private UUID runId;
        private UUID employeeId;

        public Key() {}
        public Key(UUID runId, UUID employeeId) { this.runId = runId; this.employeeId = employeeId; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false)
    private JsonNode snapshot;  // immutable once the run is closed

    @Column(name = "pdf_path")
    private String pdfPath;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", insertable = false, updatable = false)
    private PayrollRun run;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    private Employee employee;

    @PrePersist
    void prePersist() {
        if (publishedAt == null) publishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public JsonNode getSnapshot() { return snapshot; }
    public void setSnapshot(JsonNode snapshot) { this.snapshot = snapshot; }
    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) { this.pdfPath = pdfPath; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    /** Read-only navigation; set runId to change it. */
    public PayrollRun getRun() { return run; }
    /** Read-only navigation; set employeeId to change it. */
    public Employee getEmployee() { return employee; }
}
