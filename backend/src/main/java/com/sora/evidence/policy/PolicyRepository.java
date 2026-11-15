package com.sora.evidence.policy;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<PolicyVersion, Long> {
    Optional<PolicyVersion> findFirstBySupportTypeAndStatus(String supportType, String status);

    Optional<PolicyVersion> findBySupportTypeAndVersion(String supportType, String version);

    List<PolicyVersion> findByStatusOrderBySupportTypeAsc(String status);

    List<PolicyVersion> findByStatusIn(List<String> statuses);

    List<PolicyVersion> findAllByOrderBySupportTypeAscCreatedAtDesc();
}
