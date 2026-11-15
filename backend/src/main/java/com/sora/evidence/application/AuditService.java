package com.sora.evidence.application;

import java.util.Map;

import com.sora.evidence.application.Repositories.AuditLogRepository;
import com.sora.evidence.auth.User;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void log(Long applicationId, User actor, String action, Map<String, Object> detail) {
        repo.save(new AuditLog(applicationId, actor == null ? "system" : actor.getUsername(), actor == null ? null : actor.getRole(), action, detail));
    }
}
