package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.entity.Setting;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/** Saved settings rows (row-level security limits them to the current business). */
@Repository
public class SettingsRepository {

    private final EntityManager entityManager;

    public SettingsRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The current business's saved row for a key, or null. */
    public Setting find(String key) {
        return entityManager.find(Setting.class, key);
    }

    /** Saves the current business's value for a key (insert or replace). */
    public void upsert(String key, String valueJson) {
        entityManager.createNativeQuery("INSERT INTO settings (key, value, updated_at) VALUES (?1, CAST(?2 AS jsonb), now()) "
                + "ON CONFLICT (business_id, key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()")
                .setParameter(1, key).setParameter(2, valueJson).executeUpdate();
    }
}
