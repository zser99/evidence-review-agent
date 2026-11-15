package com.sora.evidence.application;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 누가 언제 무엇을 했는지 남기는 감사 로그(추가만 한다). */
@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id")
    private Long applicationId;
    @Column(nullable = false, length = 60)
    private String actor;
    @Column(name = "actor_role", length = 20)
    private String actorRole;
    @Column(nullable = false, length = 60)
    private String action;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> detail;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public AuditLog(Long applicationId, String actor, String actorRole, String action, Map<String, Object> detail) {
        this.applicationId = applicationId;
        this.actor = actor;
        this.actorRole = actorRole;
        this.action = action;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public String getActor() {
        return actor;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getAction() {
        return action;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
