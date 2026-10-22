package com.jurislpo.jurisone.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "role_permissions")
@IdClass(RolePermission.Key.class)
public class RolePermission {

    /** Composite primary key. */
    public static class Key implements Serializable {
        private UUID roleId;
        private String permissionKey;

        public Key() {}
        public Key(UUID roleId, String permissionKey) { this.roleId = roleId; this.permissionKey = permissionKey; }

        @Override public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof Key other)) return false;
            return Objects.equals(roleId, other.roleId) && Objects.equals(permissionKey, other.permissionKey);
        }
        @Override public int hashCode() { return Objects.hash(roleId, permissionKey); }
    }

    @Id
    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Id
    @Column(name = "permission_key", nullable = false)
    private String permissionKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", insertable = false, updatable = false)
    private Role role;

    public UUID getRoleId() { return roleId; }
    public void setRoleId(UUID roleId) { this.roleId = roleId; }
    public String getPermissionKey() { return permissionKey; }
    public void setPermissionKey(String permissionKey) { this.permissionKey = permissionKey; }
    /** Read-only navigation; set roleId to change it. */
    public Role getRole() { return role; }
}
