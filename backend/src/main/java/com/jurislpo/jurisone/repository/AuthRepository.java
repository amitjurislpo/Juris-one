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

    /** The permission keys of the employee: the main role's plus those of every additional role. */
    public List<String> findPermissionKeys(UUID roleId, UUID employeeId) {
        List<?> keys = entityManager.createNativeQuery("""
                SELECT permission_key FROM role_permissions WHERE role_id = :r
                UNION SELECT rp.permission_key FROM role_permissions rp JOIN employee_additional_roles a ON a.role_id = rp.role_id WHERE a.employee_id = :e""")
                .setParameter("r", roleId).setParameter("e", employeeId).getResultList();
        return keys.stream().map(String::valueOf).toList();
    }
}
