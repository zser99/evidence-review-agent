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

/**
 * 검수 실행 이력(불변). 대조표, 조건별 판정·확신도·근거 조항, 적용 규정 버전, 결정 모델 버전을 통째로 보관해
 * 감사·이의제기 때 '왜 그렇게 판정했는지'를 그대로 다시 보여 준다.
 */
@Entity
@Table(name = "review_run")
public class ReviewRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id", nullable = false)
    private Long applicationId;
    @Column(name = "run_no", nullable = false)
    private int runNo;
    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;
    @Column(name = "trigger_type", nullable = false, length = 20)
    private String triggerType;
    @Column(nullable = false, length = 20)
    private String verdict;
    private Double confidence;
    @Column(length = 60)
    private String provider;
    @Column(length = 100)
    private String model;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> result;
    @Column(name = "created_by", length = 60)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReviewRun() {
    }

    public ReviewRun(Long applicationId, int runNo, Long policyVersionId, String triggerType, String verdict, Double confidence,
                     String provider, String model, Map<String, Object> result, String createdBy) {
        this.applicationId = applicationId;
        this.runNo = runNo;
        this.policyVersionId = policyVersionId;
        this.triggerType = triggerType;
        this.verdict = verdict;
        this.confidence = confidence;
        this.provider = provider;
        this.model = model;
        this.result = result;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public int getRunNo() {
        return runNo;
    }

    public Long getPolicyVersionId() {
        return policyVersionId;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public String getVerdict() {
        return verdict;
    }

    public Double getConfidence() {
        return confidence;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
