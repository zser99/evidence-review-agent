package com.sora.evidence.policy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 지원 종류 설정(규정)의 한 버전. 신청 건은 접수 당시의 버전을 참조한다. */
@Entity
@Table(name = "policy_version", uniqueConstraints = @UniqueConstraint(columnNames = {"support_type", "version"}))
public class PolicyVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "support_type", nullable = false, length = 40)
    private String supportType;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 40)
    private String version;
    @Column(name = "effective_date")
    private LocalDate effectiveDate;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "yaml_text", nullable = false, columnDefinition = "text")
    private String yamlText;
    @Column(name = "regulation_text", nullable = false, columnDefinition = "text")
    private String regulationText;
    @Column(name = "regulation_key", length = 300)
    private String regulationKey;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, Object>> clauses;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> regression;
    @Column(name = "indexed_at")
    private Instant indexedAt;
    @Column(name = "created_by", length = 60)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "activated_at")
    private Instant activatedAt;
    @Column(columnDefinition = "text")
    private String note;

    protected PolicyVersion() {
    }

    public PolicyVersion(String supportType, String name, String version, LocalDate effectiveDate, String status,
                         String yamlText, String regulationText, String createdBy, String note) {
        this.supportType = supportType;
        this.name = name;
        this.version = version;
        this.effectiveDate = effectiveDate;
        this.status = status;
        this.yamlText = yamlText;
        this.regulationText = regulationText;
        this.createdBy = createdBy;
        this.note = note;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSupportType() {
        return supportType;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getYamlText() {
        return yamlText;
    }

    public void setYamlText(String yamlText) {
        this.yamlText = yamlText;
    }

    public String getRegulationText() {
        return regulationText;
    }

    public String getRegulationKey() {
        return regulationKey;
    }

    public void setRegulationKey(String regulationKey) {
        this.regulationKey = regulationKey;
    }

    public List<Map<String, Object>> getClauses() {
        return clauses;
    }

    public void setClauses(List<Map<String, Object>> clauses) {
        this.clauses = clauses;
    }

    public Map<String, Object> getRegression() {
        return regression;
    }

    public void setRegression(Map<String, Object> regression) {
        this.regression = regression;
    }

    public Instant getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(Instant indexedAt) {
        this.indexedAt = indexedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    public String getNote() {
        return note;
    }
}
