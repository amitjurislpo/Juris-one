package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.entity.AuditLog;
import com.jurislpo.jurisone.repository.AuditRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who, what, when, before → after (REQUIREMENTS §5.16). Joins the caller's transaction when
 * there is one. Values that were Prisma BigInt in the Node app are written as strings by the callers, so new
 * rows look like existing ones.
 */
@Service
@Transactional
public class AuditService {

    private final AuditRepository repo;
    private final ObjectMapper json;

    public AuditService(AuditRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    public void log(UUID actorId, String action, String entity, String entityId, Object before, Object after, String ipAddress) {
        AuditLog auditLog = new AuditLog();
        auditLog.setActorId(actorId);
        auditLog.setAction(action);
        auditLog.setEntity(entity);
        auditLog.setEntityId(entityId);
        auditLog.setBefore(toJson(before));
        auditLog.setAfter(toJson(after));
        auditLog.setIpAddress(ipAddress);
        repo.persist(auditLog);
        if (com.jurislpo.jurisone.tenancy.TenantContext.current() == null && actorId != null) {
            // outside a business request (sign-in, password set): the event belongs to the actor's business, so its
            // admin sees it; platform users (no business) keep NULL
            repo.assignActorBusiness(auditLog.getId(), actorId);
        }
    }

    public void log(UUID actorId, String action, String entity, String entityId, String ipAddress) {
        log(actorId, action, entity, entityId, null, null, ipAddress);
    }

    private JsonNode toJson(Object value) {
        if (value == null) return null;
        JsonNode node = json.valueToTree(value);
        return node == null || node.isNull() ? null : node;
    }
}
