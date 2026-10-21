package com.jurislpo.jurisone.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;

/**
 * Every connection handed out says which business it works for: {@code app.business_id} plus the
 * {@code jurisone_tenant} role for a business request (row-level security applies), or no business and the
 * login role for system mode. Pooled connections are reset on every checkout, so nothing leaks between requests.
 */
public class TenantDataSource extends DelegatingDataSource {

    public static final String TENANT_ROLE = "jurisone_tenant";

    public TenantDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return apply(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return apply(super.getConnection(username, password));
    }

    private static Connection apply(Connection connection) throws SQLException {
        UUID business = TenantContext.current();
        try (Statement s = connection.createStatement()) {
            // the UUID's own toString() is the only value interpolated — no user text reaches this SQL
            if (business == null) s.execute("RESET ROLE; SELECT set_config('app.business_id', '', false)");
            else s.execute("SET ROLE " + TENANT_ROLE + "; SELECT set_config('app.business_id', '" + business + "', false)");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /** Wraps the application's DataSource (Hikari) — Flyway, JDBC and JPA all go through it. */
    @Component
    static class Wrapper implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
            return bean instanceof DataSource ds && !(bean instanceof TenantDataSource) ? new TenantDataSource(ds) : bean;
        }
    }
}
