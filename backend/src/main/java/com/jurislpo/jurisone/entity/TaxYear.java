package com.jurislpo.jurisone.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tax_years")
@IdClass(TaxYear.Key.class)
public class TaxYear {

    /** Composite primary key. */
    public static class Key implements Serializable {
        private String fy;
        private TaxRegime regime;

        public Key() {}
        public Key(String fy, TaxRegime regime) { this.fy = fy; this.regime = regime; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
            return Objects.equals(fy, other.fy) && Objects.equals(regime, other.regime);
        }
        @Override public int hashCode() { return Objects.hash(fy, regime); }
    }

    @Id
    @Column(name = "fy", nullable = false)
    private String fy;  // "2026-27"

    @Id
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "regime", nullable = false, columnDefinition = "\"TaxRegime\"")
    private TaxRegime regime;

    @Column(name = "std_deduction", nullable = false)
    private Integer stdDeduction;  // rupees

    @Column(name = "rebate_limit", nullable = false)
    private Integer rebateLimit;  // rupees; full rebate at or below

    @Column(name = "marginal_relief", nullable = false)
    private Boolean marginalRelief = false;

    @Column(name = "cess_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal cessRate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "slabs", nullable = false)
    private JsonNode slabs;  // [{ upto: number|null, rate: number }]

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "surcharge", nullable = false)
    private JsonNode surcharge;  // [{ above: number, rate: number }]

    public String getFy() { return fy; }
    public void setFy(String fy) { this.fy = fy; }
    public TaxRegime getRegime() { return regime; }
    public void setRegime(TaxRegime regime) { this.regime = regime; }
    public Integer getStdDeduction() { return stdDeduction; }
    public void setStdDeduction(Integer stdDeduction) { this.stdDeduction = stdDeduction; }
    public Integer getRebateLimit() { return rebateLimit; }
    public void setRebateLimit(Integer rebateLimit) { this.rebateLimit = rebateLimit; }
    public Boolean getMarginalRelief() { return marginalRelief; }
    public void setMarginalRelief(Boolean marginalRelief) { this.marginalRelief = marginalRelief; }
    public BigDecimal getCessRate() { return cessRate; }
    public void setCessRate(BigDecimal cessRate) { this.cessRate = cessRate; }
    public JsonNode getSlabs() { return slabs; }
    public void setSlabs(JsonNode slabs) { this.slabs = slabs; }
    public JsonNode getSurcharge() { return surcharge; }
    public void setSurcharge(JsonNode surcharge) { this.surcharge = surcharge; }
}
