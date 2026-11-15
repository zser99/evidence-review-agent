package com.sora.evidence.application;

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

/** 제출된 증빙 파일과 추출 결과. 교체된 파일도 지우지 않고 비활성으로 남겨 이력을 보존한다. */
@Entity
@Table(name = "evidence_file")
public class EvidenceFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id", nullable = false)
    private Long applicationId;
    @Column(name = "doc_id", nullable = false, length = 80)
    private String docId;
    @Column(nullable = false)
    private String filename;
    @Column(name = "content_type", length = 100)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(name = "storage_key", nullable = false, length = 300)
    private String storageKey;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "page_keys")
    private List<String> pageKeys;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> extraction;
    private Double quality;
    @Column(length = 60)
    private String engine;
    @Column(name = "title_matched", nullable = false)
    private boolean titleMatched = true;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected EvidenceFile() {
    }

    public EvidenceFile(Long applicationId, String docId, String filename, String contentType, long sizeBytes, String sha256, String storageKey) {
        this.applicationId = applicationId;
        this.docId = docId;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.uploadedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public String getDocId() {
        return docId;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public List<String> getPageKeys() {
        return pageKeys;
    }

    public void setPageKeys(List<String> pageKeys) {
        this.pageKeys = pageKeys;
    }

    public Map<String, Object> getExtraction() {
        return extraction;
    }

    public void setExtraction(Map<String, Object> extraction) {
        this.extraction = extraction;
    }

    public Double getQuality() {
        return quality;
    }

    public void setQuality(Double quality) {
        this.quality = quality;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public boolean isTitleMatched() {
        return titleMatched;
    }

    public void setTitleMatched(boolean titleMatched) {
        this.titleMatched = titleMatched;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
