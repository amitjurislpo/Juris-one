package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "employees")
public class Employee {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true)
    private String code;  // JC-###

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "father_name")
    private String fatherName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;  // stored lower-case

    @Column(name = "phone_enc")
    private String phoneEnc;

    @Column(name = "phone_last4")
    private String phoneLast4;

    @Column(name = "address_enc")
    private String addressEnc;

    @Column(name = "pan_enc")
    private String panEnc;

    @Column(name = "pan_last4")
    private String panLast4;

    @Column(name = "aadhaar_enc")
    private String aadhaarEnc;

    @Column(name = "aadhaar_last4")
    private String aadhaarLast4;

    @Column(name = "uan_enc")
    private String uanEnc;

    @Column(name = "uan_last4")
    private String uanLast4;

    @Column(name = "bank_account_enc")
    private String bankAccountEnc;

    @Column(name = "bank_account_last4")
    private String bankAccountLast4;

    @Column(name = "bank_ifsc")
    private String bankIfsc;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "designation", nullable = false)
    private String designation;

    @Column(name = "reporting_manager_id")
    private UUID reportingManagerId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "date_of_joining", nullable = false)
    private LocalDate dateOfJoining;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "\"EmployeeStatus\"")
    private EmployeeStatus status = EmployeeStatus.ONBOARDING;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;  // IANA, e.g. Asia/Kolkata

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "region", nullable = false, columnDefinition = "\"Region\"")
    private Region region;

    @Column(name = "shift_id")
    private UUID shiftId;

    @Column(name = "portal_access", nullable = false)
    private Boolean portalAccess = true;

    @Column(name = "created_by")
    private UUID createdById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporting_manager_id", insertable = false, updatable = false)
    private Employee reportingManager;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", insertable = false, updatable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", insertable = false, updatable = false)
    private Shift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", insertable = false, updatable = false)
    private Employee createdBy;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getFatherName() { return fatherName; }
    public void setFatherName(String fatherName) { this.fatherName = fatherName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhoneEnc() { return phoneEnc; }
    public void setPhoneEnc(String phoneEnc) { this.phoneEnc = phoneEnc; }
    public String getPhoneLast4() { return phoneLast4; }
    public void setPhoneLast4(String phoneLast4) { this.phoneLast4 = phoneLast4; }
    public String getAddressEnc() { return addressEnc; }
    public void setAddressEnc(String addressEnc) { this.addressEnc = addressEnc; }
    public String getPanEnc() { return panEnc; }
    public void setPanEnc(String panEnc) { this.panEnc = panEnc; }
    public String getPanLast4() { return panLast4; }
    public void setPanLast4(String panLast4) { this.panLast4 = panLast4; }
    public String getAadhaarEnc() { return aadhaarEnc; }
    public void setAadhaarEnc(String aadhaarEnc) { this.aadhaarEnc = aadhaarEnc; }
    public String getAadhaarLast4() { return aadhaarLast4; }
    public void setAadhaarLast4(String aadhaarLast4) { this.aadhaarLast4 = aadhaarLast4; }
    public String getUanEnc() { return uanEnc; }
    public void setUanEnc(String uanEnc) { this.uanEnc = uanEnc; }
    public String getUanLast4() { return uanLast4; }
    public void setUanLast4(String uanLast4) { this.uanLast4 = uanLast4; }
    public String getBankAccountEnc() { return bankAccountEnc; }
    public void setBankAccountEnc(String bankAccountEnc) { this.bankAccountEnc = bankAccountEnc; }
    public String getBankAccountLast4() { return bankAccountLast4; }
    public void setBankAccountLast4(String bankAccountLast4) { this.bankAccountLast4 = bankAccountLast4; }
    public String getBankIfsc() { return bankIfsc; }
    public void setBankIfsc(String bankIfsc) { this.bankIfsc = bankIfsc; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public UUID getDepartmentId() { return departmentId; }
    public void setDepartmentId(UUID departmentId) { this.departmentId = departmentId; }
    public String getDesignation() { return designation; }
    public void setDesignation(String designation) { this.designation = designation; }
    public UUID getReportingManagerId() { return reportingManagerId; }
    public void setReportingManagerId(UUID reportingManagerId) { this.reportingManagerId = reportingManagerId; }
    public UUID getRoleId() { return roleId; }
    public void setRoleId(UUID roleId) { this.roleId = roleId; }
    public LocalDate getDateOfJoining() { return dateOfJoining; }
    public void setDateOfJoining(LocalDate dateOfJoining) { this.dateOfJoining = dateOfJoining; }
    public LocalDate getExitDate() { return exitDate; }
    public void setExitDate(LocalDate exitDate) { this.exitDate = exitDate; }
    public EmployeeStatus getStatus() { return status; }
    public void setStatus(EmployeeStatus status) { this.status = status; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public Region getRegion() { return region; }
    public void setRegion(Region region) { this.region = region; }
    public UUID getShiftId() { return shiftId; }
    public void setShiftId(UUID shiftId) { this.shiftId = shiftId; }
    public Boolean getPortalAccess() { return portalAccess; }
    public void setPortalAccess(Boolean portalAccess) { this.portalAccess = portalAccess; }
    public UUID getCreatedById() { return createdById; }
    public void setCreatedById(UUID createdById) { this.createdById = createdById; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    /** Read-only navigation; set departmentId to change it. */
    public Department getDepartment() { return department; }
    /** Read-only navigation; set reportingManagerId to change it. */
    public Employee getReportingManager() { return reportingManager; }
    /** Read-only navigation; set roleId to change it. */
    public Role getRole() { return role; }
    /** Read-only navigation; set shiftId to change it. */
    public Shift getShift() { return shift; }
    /** Read-only navigation; set createdById to change it. */
    public Employee getCreatedBy() { return createdBy; }
}
