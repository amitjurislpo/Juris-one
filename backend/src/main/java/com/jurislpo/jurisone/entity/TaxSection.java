package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "tax_sections")
@IdClass(TaxSection.Key.class)
public class TaxSection {

    /** Composite primary key. */
    public static class Key implements Serializable {
        private String financialYear;
        private String key;

        public Key() {}
        public Key(String financialYear, String key) { this.financialYear = financialYear; this.key = key; }

        @Override public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof Key other)) return false;
            return Objects.equals(financialYear, other.financialYear) && Objects.equals(key, other.key);
        }
        @Override public int hashCode() { return Objects.hash(financialYear, key); }
    }

    @Id
    @Column(name = "fy", nullable = false)
    private String financialYear;

    @Id
    @Column(name = "key", nullable = false)
    private String key;  // HRA · 80C · 80CCD1B · 80D_SELF · ...

    @Column(name = "label", nullable = false)
    private String label;  // configurable section label (Income-tax Act, 2025 renumbering)

    @Column(name = "cap")
    private Integer cap;  // rupees; null = no cap

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    public String getFinancialYear() { return financialYear; }
    public void setFinancialYear(String financialYear) { this.financialYear = financialYear; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Integer getCap() { return cap; }
    public void setCap(Integer cap) { this.cap = cap; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
