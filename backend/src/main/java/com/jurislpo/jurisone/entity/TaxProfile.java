package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tax_profiles")
@IdClass(TaxProfile.Key.class)
public class TaxProfile {

    /** Composite primary key. */
    public static class Key implements Serializable {
        private UUID employeeId;
        private String fy;

        public Key() {}
        public Key(UUID employeeId, String fy) { this.employeeId = employeeId; this.fy = fy; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
            return Objects.equals(employeeId, other.employeeId) && Objects.equals(fy, other.fy);
        }
        @Override public int hashCode() { return Objects.hash(employeeId, fy); }
    }

    @Id
    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Id
    @Column(name = "fy", nullable = false)
    private String fy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "regime", nullable = false, columnDefinition = "\"TaxRegime\"")
    private TaxRegime regime = TaxRegime.NEW;

    @Column(name = "regime_overridden", nullable = false)
    private Boolean regimeOverridden = false;

    @Column(name = "metro", nullable = false)
    private Boolean metro = false;

    @Column(name = "parents_senior", nullable = false)
    private Boolean parentsSenior = false;  // 80D parents cap ₹50k instead of ₹25k

    @Column(name = "prev_employer_income", nullable = false)
    private Long prevEmployerIncome = 0L;  // paise

    @Column(name = "prev_employer_tds", nullable = false)
    private Long prevEmployerTds = 0L;  // paise

    @Column(name = "landlord_pan")
    private String landlordPan;

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
    public String getFy() { return fy; }
    public void setFy(String fy) { this.fy = fy; }
    public TaxRegime getRegime() { return regime; }
    public void setRegime(TaxRegime regime) { this.regime = regime; }
    public Boolean getRegimeOverridden() { return regimeOverridden; }
    public void setRegimeOverridden(Boolean regimeOverridden) { this.regimeOverridden = regimeOverridden; }
    public Boolean getMetro() { return metro; }
    public void setMetro(Boolean metro) { this.metro = metro; }
    public Boolean getParentsSenior() { return parentsSenior; }
    public void setParentsSenior(Boolean parentsSenior) { this.parentsSenior = parentsSenior; }
    public Long getPrevEmployerIncome() { return prevEmployerIncome; }
    public void setPrevEmployerIncome(Long prevEmployerIncome) { this.prevEmployerIncome = prevEmployerIncome; }
    public Long getPrevEmployerTds() { return prevEmployerTds; }
    public void setPrevEmployerTds(Long prevEmployerTds) { this.prevEmployerTds = prevEmployerTds; }
    public String getLandlordPan() { return landlordPan; }
    public void setLandlordPan(String landlordPan) { this.landlordPan = landlordPan; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    /** Read-only navigation; set employeeId to change it. */
    public Employee getEmployee() { return employee; }
}
