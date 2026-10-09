package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.entity.Setting;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/** Saved settings rows (row-level security limits them to the current business). */
@Repository
public class SettingsRepository {

    private final EntityManager em;

    public SettingsRepository(EntityManager em) {
        this.em = em;
    }

    /** The current business's saved row for a key, or null. */
    public Setting find(String key) {
        return em.find(Setting.class, key);
    }
}
