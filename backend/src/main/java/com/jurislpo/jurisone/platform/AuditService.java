package com.jurislpo.jurisone.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.entity.AuditLog;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who, what, when, before → after (REQUIREMENTS §5.16; src/lib/audit.ts). Joins the caller's transaction when
 * there is one. Values that were Prisma BigInt in the Node app are written as strings by the callers, so new
 * rows look like existing ones.
 */
@Service
@Transactional
public class AuditService {

    private final EntityManager em;
    private final ObjectMapper json;

    public AuditService(EntityManager em, ObjectMapper json) {
        this.em = em;
        this.json = json;
    }

    public void log(UUID actorId, String action, String entity, String entityId, Object before, Object after, String ip) {
        AuditLog a = new AuditLog();
        a.setActorId(actorId);
        a.setAction(action);
        a.setEntity(entity);
        a.setEntityId(entityId);
        a.setBefore(toJson(before));
        a.setAfter(toJson(after));
        a.setIp(ip);
        em.persist(a);
    }

    public void log(UUID actorId, String action, String entity, String entityId, String ip) {
        log(actorId, action, entity, entityId, null, null, ip);
    }

    private JsonNode toJson(Object v) {
        if (v == null) return null;
        JsonNode n = json.valueToTree(v);
        return n == null || n.isNull() ? null : n;
    }
}
