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
        private String fy;
        private String key;

        public Key() {}
        public Key(String fy, String key) { this.fy = fy; this.key = key; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
            return Objects.equals(fy, other.fy) && Objects.equals(key, other.key);
        }
        @Override public int hashCode() { return Objects.hash(fy, key); }
    }

    @Id
    @Column(name = "fy", nullable = false)
    private String fy;

    @Id
    @Column(name = "key", nullable = false)
    private String key;  // HRA · 80C · 80CCD1B · 80D_SELF · ...

    @Column(name = "label", nullable = false)
    private String label;  // configurable section label (Income-tax Act, 2025 renumbering)

    @Column(name = "cap")
    private Integer cap;  // rupees; null = no cap

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    public String getFy() { return fy; }
    public void setFy(String fy) { this.fy = fy; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Integer getCap() { return cap; }
    public void setCap(Integer cap) { this.cap = cap; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
