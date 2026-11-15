package com.sora.evidence.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import com.sora.evidence.auth.User;
import com.sora.evidence.policy.PolicyVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 지원 신청 건. */
@Entity
@Table(name = "application")
public class Application {
    public static final String DRAFT = "DRAFT";
    public static final String AUTO_APPROVED = "AUTO_APPROVED";
    public static final String SUPPLEMENT_REQUIRED = "SUPPLEMENT_REQUIRED";
    public static final String NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_no", nullable = false, unique = true, length = 40)
    private String applicationNo;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "applicant_id")
    private User applicant;
    @Column(name = "support_type", nullable = false, length = 40)
    private String supportType;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "policy_version_id")
    private PolicyVersion policyVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> form;
    @Column(nullable = false, length = 30)
    private String status;
    @Column(name = "applied_on")
    private LocalDate appliedOn;
    @Column(name = "auto_verdict", length = 20)
    private String autoVerdict;
    private Double confidence;
    @Column(columnDefinition = "text")
    private String guidance;
    @Column(name = "final_decision", length = 20)
    private String finalDecision;
    @Column(name = "decided_by", length = 60)
    private String decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "decision_comment", columnDefinition = "text")
    private String decisionComment;
    @Column(name = "dedup_key", length = 300)
    private String dedupKey;
    @Column(name = "last_run_no", nullable = false)
    private int lastRunNo;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Application() {
    }

    public Application(String applicationNo, User applicant, PolicyVersion policyVersion, Map<String, Object> form) {
        this.applicationNo = applicationNo;
        this.applicant = applicant;
        this.policyVersion = policyVersion;
        this.supportType = policyVersion.getSupportType();
        this.form = form;
        this.status = DRAFT;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public boolean editable() {
        return DRAFT.equals(status) || SUPPLEMENT_REQUIRED.equals(status);
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getApplicationNo() {
        return applicationNo;
    }

    public User getApplicant() {
        return applicant;
    }

    public String getSupportType() {
        return supportType;
    }

    public PolicyVersion getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(PolicyVersion policyVersion) {
        this.policyVersion = policyVersion;
    }

    public Map<String, Object> getForm() {
        return form;
    }

    public void setForm(Map<String, Object> form) {
        this.form = form;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getAppliedOn() {
        return appliedOn;
    }

    public void setAppliedOn(LocalDate appliedOn) {
        this.appliedOn = appliedOn;
    }

    public String getAutoVerdict() {
        return autoVerdict;
    }

    public void setAutoVerdict(String autoVerdict) {
        this.autoVerdict = autoVerdict;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getGuidance() {
        return guidance;
    }

    public void setGuidance(String guidance) {
        this.guidance = guidance;
    }

    public String getFinalDecision() {
        return finalDecision;
    }

    public void setFinalDecision(String finalDecision) {
        this.finalDecision = finalDecision;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public void setDecisionComment(String decisionComment) {
        this.decisionComment = decisionComment;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public int getLastRunNo() {
        return lastRunNo;
    }

    public void setLastRunNo(int lastRunNo) {
        this.lastRunNo = lastRunNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
