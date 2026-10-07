package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "role_assignable_roles")
@IdClass(RoleAssignableRole.Key.class)
public class RoleAssignableRole {

    /** Composite primary key. */
    public static class Key implements Serializable {
        private UUID roleId;
        private UUID assignableRoleId;

        public Key() {}
        public Key(UUID roleId, UUID assignableRoleId) { this.roleId = roleId; this.assignableRoleId = assignableRoleId; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
            return Objects.equals(roleId, other.roleId) && Objects.equals(assignableRoleId, other.assignableRoleId);
        }
        @Override public int hashCode() { return Objects.hash(roleId, assignableRoleId); }
    }

    @Id
    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Id
    @Column(name = "assignable_role_id", nullable = false)
    private UUID assignableRoleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", insertable = false, updatable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignable_role_id", insertable = false, updatable = false)
    private Role assignableRole;

    public UUID getRoleId() { return roleId; }
    public void setRoleId(UUID roleId) { this.roleId = roleId; }
    public UUID getAssignableRoleId() { return assignableRoleId; }
    public void setAssignableRoleId(UUID assignableRoleId) { this.assignableRoleId = assignableRoleId; }
    /** Read-only navigation; set roleId to change it. */
    public Role getRole() { return role; }
    /** Read-only navigation; set assignableRoleId to change it. */
    public Role getAssignableRole() { return assignableRole; }
}
