package com.jurislpo.jurisone.tenancy;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The business the current thread works for. Set from the signed-in user for every request (JwtAuthFilter) and by
 * jobs / setup code that act inside one business; {@code null} is system mode (sign-in, super admin, jobs, seed).
 * {@link TenantDataSource} applies it to every database connection, and PostgreSQL row-level security does the rest.
 *
 * <p>Switch only outside a transaction: a connection already bound to a transaction keeps the business it started with.
 */
public final class TenantContext {
    private TenantContext() {}

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    public static UUID current() {
        return CURRENT.get();
    }

    public static void set(UUID businessId) {
        if (businessId == null) CURRENT.remove();
        else CURRENT.set(businessId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code work} inside {@code businessId} (null = system mode), then restores the previous business. */
    public static <T> T call(UUID businessId, Supplier<T> work) {
        UUID before = CURRENT.get();
        set(businessId);
        try {
            return work.get();
        } finally {
            set(before);
        }
    }

    public static void run(UUID businessId, Runnable work) {
        call(businessId, () -> {
            work.run();
            return null;
        });
    }
}
