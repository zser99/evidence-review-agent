package com.sora.evidence.review;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.sora.evidence.policy.PolicyDefinition;

/**
 * 검수 한 번에 필요한 입력. 저장소(DB)와 무관해서 실제 신청 건과 정답 세트 검증이 같은 판정 흐름을 탄다.
 *
 * @param policyVersionId 적용 규정 버전 ID(정답 세트 검증에서는 null일 수 있다)
 * @param form            신청 내용(app.*)
 * @param employee        구성원 정보(emp.*: name, hire_date, employee_no)
 * @param appliedOn       신청일(ctx.applied_on)
 * @param documents       제출된 증빙과 추출 결과
 * @param duplicates      중복 신청 확인(없으면 건너뜀)
 */
public record ReviewInput(Long policyVersionId, PolicyDefinition policy, Map<String, Object> form, Map<String, Object> employee,
                          LocalDate appliedOn, List<DocInput> documents, DuplicateChecker duplicates) {

    /** 증빙 파일 하나와 AI 서버의 추출 결과. */
    public record DocInput(String docId, Long fileId, String filename, Map<String, Object> extraction) {
    }

    @FunctionalInterface
    public interface DuplicateChecker {
        boolean exists(String dedupKey);
    }
}
