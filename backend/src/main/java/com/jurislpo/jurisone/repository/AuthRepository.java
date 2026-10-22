package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.entity.Employee;
import com.jurislpo.jurisone.entity.LoginHistory;
import com.jurislpo.jurisone.entity.User;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Accounts behind sign-in: employees by email, users, login history. */
@Repository
public class AuthRepository {

    private final EntityManager entityManager;

    public AuthRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The business's status; throws when the business does not exist. */
    public String findBusinessStatus(UUID businessId) {
        return (String) entityManager.createNativeQuery("SELECT status FROM businesses WHERE id = :b").setParameter("b", businessId).getSingleResult();
    }

    /** Employees (zero or one) with this sign-in email. */
    public List<Employee> findEmployeesByEmail(String email) {
        return entityManager.createQuery("SELECT e FROM Employee e WHERE e.email = :v", Employee.class).setParameter("v", email).getResultList();
    }

    public User findUser(UUID employeeId) {
        return entityManager.find(User.class, employeeId);
    }

    public void persistUser(User user) {
        entityManager.persist(user);
    }

    public void persistLoginHistory(LoginHistory loginHistory) {
        entityManager.persist(loginHistory);
    }

    /** The permission keys of a role. */
    public List<String> findPermissionKeys(UUID roleId) {
        return entityManager.createQuery("SELECT p.permissionKey FROM RolePermission p WHERE p.roleId = :r", String.class)
                .setParameter("r", roleId).getResultList();
    }
}
