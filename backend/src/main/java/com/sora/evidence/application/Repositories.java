package com.sora.evidence.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 신청·증빙·검수 이력·감사 로그 저장소. */
public final class Repositories {
    private Repositories() {
    }

    public interface ApplicationRepository extends JpaRepository<Application, Long> {
        List<Application> findByApplicantIdOrderByCreatedAtDesc(Long applicantId);

        List<Application> findByStatusNotOrderByCreatedAtDesc(String status);

        List<Application> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses);

        /** 검토 대기 목록: 확신도가 낮은 순(같으면 먼저 접수된 순). */
        @Query("select a from Application a where a.status in :statuses order by coalesce(a.confidence, 0) asc, a.submittedAt asc")
        List<Application> reviewQueue(Collection<String> statuses);

        boolean existsByDedupKeyAndStatusInAndIdNot(String dedupKey, Collection<String> statuses, Long id);

        long countByStatus(String status);
    }

    public interface EvidenceFileRepository extends JpaRepository<EvidenceFile, Long> {
        List<EvidenceFile> findByApplicationIdAndActiveTrueOrderByUploadedAtAsc(Long applicationId);

        List<EvidenceFile> findByApplicationIdOrderByUploadedAtAsc(Long applicationId);

        Optional<EvidenceFile> findByIdAndApplicationId(Long id, Long applicationId);
    }

    public interface ReviewRunRepository extends JpaRepository<ReviewRun, Long> {
        List<ReviewRun> findByApplicationIdOrderByRunNoDesc(Long applicationId);

        Optional<ReviewRun> findFirstByApplicationIdOrderByRunNoDesc(Long applicationId);
    }

    public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
        List<AuditLog> findByApplicationIdOrderByCreatedAtAsc(Long applicationId);
    }
}
