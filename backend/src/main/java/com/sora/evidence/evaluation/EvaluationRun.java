package com.sora.evidence.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 정답 세트 검증(EVALUATION) 또는 규정 개정 회귀 테스트(REGRESSION) 한 번의 실행 기록. */
@Entity
@Table(name = "evaluation_run")
public class EvaluationRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 20)
    private String kind;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "policy_version_id")
    private Long policyVersionId;
    @Column(name = "progress_done", nullable = false)
    private int progressDone;
    @Column(name = "progress_total", nullable = false)
    private int progressTotal;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> summary;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, Object>> cases;
    @Column(length = 60)
    private String provider;
    @Column(length = 100)
    private String model;
    @Column(name = "triggered_by", length = 60)
    private String triggeredBy;
    @Column(columnDefinition = "text")
    private String error;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;

    protected EvaluationRun() {
    }

    public EvaluationRun(String kind, Long policyVersionId, String triggeredBy) {
        this.kind = kind;
        this.policyVersionId = policyVersionId;
        this.triggeredBy = triggeredBy;
        this.status = "RUNNING";
        this.startedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getPolicyVersionId() {
        return policyVersionId;
    }

    public int getProgressDone() {
        return progressDone;
    }

    public void setProgressDone(int progressDone) {
        this.progressDone = progressDone;
    }

    public int getProgressTotal() {
        return progressTotal;
    }

    public void setProgressTotal(int progressTotal) {
        this.progressTotal = progressTotal;
    }

    public Map<String, Object> getSummary() {
        return summary;
    }

    public void setSummary(Map<String, Object> summary) {
        this.summary = summary;
    }

    public List<Map<String, Object>> getCases() {
        return cases;
    }

    public void setCases(List<Map<String, Object>> cases) {
        this.cases = cases;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
