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

    private final EntityManager em;

    public AuthRepository(EntityManager em) {
        this.em = em;
    }

    /** The business's status; throws when the business does not exist. */
    public String findBusinessStatus(UUID businessId) {
        return (String) em.createNativeQuery("SELECT status FROM businesses WHERE id = :b").setParameter("b", businessId).getSingleResult();
    }

    /** Employees (zero or one) with this sign-in email. */
    public List<Employee> findEmployeesByEmail(String email) {
        return em.createQuery("SELECT e FROM Employee e WHERE e.email = :v", Employee.class).setParameter("v", email).getResultList();
    }

    public User findUser(UUID employeeId) {
        return em.find(User.class, employeeId);
    }

    public void persistUser(User u) {
        em.persist(u);
    }

    public void persistLoginHistory(LoginHistory h) {
        em.persist(h);
    }

    /** The permission keys of a role. */
    public List<String> findPermissionKeys(UUID roleId) {
        return em.createQuery("SELECT p.permissionKey FROM RolePermission p WHERE p.roleId = :r", String.class)
                .setParameter("r", roleId).getResultList();
    }
}
