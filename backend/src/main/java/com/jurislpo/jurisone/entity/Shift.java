package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "shifts")
public class Shift {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true)
    private String code;  // IN_NIGHT · IN_DAY · US_DAY

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "start_local", nullable = false)
    private String startLocal;  // "19:30" in the employee's own zone

    @Column(name = "end_local", nullable = false)
    private String endLocal;  // "04:30"

    @Column(name = "grace_minutes", nullable = false)
    private Integer graceMinutes = 15;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "region", nullable = false, columnDefinition = "\"Region\"")
    private Region region;

    @Column(name = "crosses_midnight", nullable = false)
    private Boolean crossesMidnight = false;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStartLocal() { return startLocal; }
    public void setStartLocal(String startLocal) { this.startLocal = startLocal; }
    public String getEndLocal() { return endLocal; }
    public void setEndLocal(String endLocal) { this.endLocal = endLocal; }
    public Integer getGraceMinutes() { return graceMinutes; }
    public void setGraceMinutes(Integer graceMinutes) { this.graceMinutes = graceMinutes; }
    public Region getRegion() { return region; }
    public void setRegion(Region region) { this.region = region; }
    public Boolean getCrossesMidnight() { return crossesMidnight; }
    public void setCrossesMidnight(Boolean crossesMidnight) { this.crossesMidnight = crossesMidnight; }
}
