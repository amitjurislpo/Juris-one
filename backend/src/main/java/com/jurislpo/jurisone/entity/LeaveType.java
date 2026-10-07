package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "leave_types")
public class LeaveType {

    @Id
    @Column(name = "code", nullable = false)
    private String code;  // CL · EL · CO · LOP

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "annual_quota", nullable = false, precision = 5, scale = 2)
    private BigDecimal annualQuota = BigDecimal.ZERO;

    @Column(name = "accrual_per_month", nullable = false, precision = 5, scale = 2)
    private BigDecimal accrualPerMonth = BigDecimal.ZERO;

    @Column(name = "carry_forward_cap", nullable = false, precision = 5, scale = 2)
    private BigDecimal carryForwardCap = BigDecimal.ZERO;

    @Column(name = "max_stretch", precision = 5, scale = 2)
    private BigDecimal maxStretch;

    @Column(name = "encash_per_year", nullable = false, precision = 5, scale = 2)
    private BigDecimal encashPerYear = BigDecimal.ZERO;

    @Column(name = "encash_after_months", nullable = false)
    private Integer encashAfterMonths = 0;

    @Column(name = "expiry_days")
    private Integer expiryDays;

    @Column(name = "paid", nullable = false)
    private Boolean paid = true;

    @Column(name = "needs_balance", nullable = false)
    private Boolean needsBalance = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getAnnualQuota() { return annualQuota; }
    public void setAnnualQuota(BigDecimal annualQuota) { this.annualQuota = annualQuota; }
    public BigDecimal getAccrualPerMonth() { return accrualPerMonth; }
    public void setAccrualPerMonth(BigDecimal accrualPerMonth) { this.accrualPerMonth = accrualPerMonth; }
    public BigDecimal getCarryForwardCap() { return carryForwardCap; }
    public void setCarryForwardCap(BigDecimal carryForwardCap) { this.carryForwardCap = carryForwardCap; }
    public BigDecimal getMaxStretch() { return maxStretch; }
    public void setMaxStretch(BigDecimal maxStretch) { this.maxStretch = maxStretch; }
    public BigDecimal getEncashPerYear() { return encashPerYear; }
    public void setEncashPerYear(BigDecimal encashPerYear) { this.encashPerYear = encashPerYear; }
    public Integer getEncashAfterMonths() { return encashAfterMonths; }
    public void setEncashAfterMonths(Integer encashAfterMonths) { this.encashAfterMonths = encashAfterMonths; }
    public Integer getExpiryDays() { return expiryDays; }
    public void setExpiryDays(Integer expiryDays) { this.expiryDays = expiryDays; }
    public Boolean getPaid() { return paid; }
    public void setPaid(Boolean paid) { this.paid = paid; }
    public Boolean getNeedsBalance() { return needsBalance; }
    public void setNeedsBalance(Boolean needsBalance) { this.needsBalance = needsBalance; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
