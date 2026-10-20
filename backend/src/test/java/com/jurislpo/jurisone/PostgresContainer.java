package com.jurislpo.jurisone;

import org.testcontainers.containers.PostgreSQLContainer;

/** A Postgres 16 container (same image as docker-compose). */
public final class PostgresContainer {
    private PostgresContainer() {}

    public static PostgreSQLContainer<?> start() {
        PostgreSQLContainer<?> c = new PostgreSQLContainer<>("postgres:16-alpine");
        c.start();
        return c;
    }
}
