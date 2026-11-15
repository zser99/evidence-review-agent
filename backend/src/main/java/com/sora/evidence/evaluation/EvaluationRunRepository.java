package com.sora.evidence.evaluation;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationRunRepository extends JpaRepository<EvaluationRun, Long> {
    Optional<EvaluationRun> findFirstByKindAndStatusOrderByStartedAtDesc(String kind, String status);

    List<EvaluationRun> findTop20ByOrderByStartedAtDesc();

    boolean existsByStatus(String status);

    List<EvaluationRun> findByStatus(String status);
}
