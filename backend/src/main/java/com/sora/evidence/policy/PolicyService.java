package com.sora.evidence.policy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sora.evidence.ai.AiClient;
import com.sora.evidence.common.ApiException;
import com.sora.evidence.common.Maps;
import com.sora.evidence.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지원 종류 설정(규정)의 버전 관리.
 *
 * <p>개정본은 DRAFT로 올라가 스키마 검증 → 조항 색인(Qdrant) → 정답 세트 회귀 테스트를 거친 뒤, 관리자가 확인하면
 * ACTIVE가 되고 이전 버전은 RETIRED가 된다. 이전 버전의 조항 색인과 설정은 지우지 않으므로, 과거 신청 건의 판정 근거를
 * 당시 규정 그대로 다시 볼 수 있다.
 */
@Service
public class PolicyService {
    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);
    public static final String DRAFT = "DRAFT";
    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";

    private final PolicyRepository repo;
    private final PolicyValidator validator;
    private final AiClient ai;
    private final StorageService storage;
    private final Map<Long, PolicyDefinition> parsed = new ConcurrentHashMap<>();

    public PolicyService(PolicyRepository repo, PolicyValidator validator, AiClient ai, StorageService storage) {
        this.repo = repo;
        this.validator = validator;
        this.ai = ai;
        this.storage = storage;
    }

    public PolicyDefinition definition(PolicyVersion v) {
        return parsed.computeIfAbsent(v.getId(), id -> PolicyDefinition.parse(v.getYamlText()));
    }

    public PolicyVersion get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("규정 버전을 찾을 수 없습니다."));
    }

    public PolicyVersion active(String supportType) {
        return repo.findFirstBySupportTypeAndStatus(supportType, ACTIVE)
                .orElseThrow(() -> ApiException.notFound("시행 중인 규정이 없는 지원 종류입니다: " + supportType));
    }

    public List<PolicyVersion> actives() {
        return repo.findByStatusOrderBySupportTypeAsc(ACTIVE);
    }

    public List<PolicyVersion> all() {
        return repo.findAllByOrderBySupportTypeAscCreatedAtDesc();
    }

    public List<String> validate(String yamlText, String regulationText) {
        try {
            return validator.validate(PolicyDefinition.parse(yamlText), regulationText);
        } catch (IllegalArgumentException e) {
            return List.of(e.getMessage());
        }
    }

    @Transactional
    public PolicyVersion createDraft(String yamlText, String regulationText, String actor, String note) {
        List<String> errors = validate(yamlText, regulationText);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("규정 설정에 오류가 있습니다:\n- " + String.join("\n- ", errors));
        }
        PolicyDefinition def = PolicyDefinition.parse(yamlText);
        if (repo.findBySupportTypeAndVersion(def.supportType(), def.version()).isPresent()) {
            throw ApiException.conflict("같은 버전이 이미 있습니다: " + def.supportType() + " " + def.version() + ". version을 올려 주세요.");
        }
        PolicyVersion v = repo.save(new PolicyVersion(def.supportType(), def.name(), def.version(),
                LocalDate.parse(def.effectiveDate()), DRAFT, yamlText, regulationText, actor, note));
        archive(v);
        try {
            index(v);
        } catch (ApiException e) {
            log.warn("규정 색인은 다음 주기에 다시 시도합니다: {}", e.getMessage());
        }
        return v;
    }

    /** 초기 데이터(seed)로 규정을 넣는다. 해당 지원 종류에 시행 중 버전이 없으면 바로 ACTIVE로 둔다. */
    @Transactional
    public PolicyVersion seed(String yamlText, String regulationText) {
        PolicyDefinition def = PolicyDefinition.parse(yamlText);
        List<String> errors = validator.validate(def, regulationText);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("seed 규정 오류(" + def.supportType() + "): " + errors);
        }
        return repo.findBySupportTypeAndVersion(def.supportType(), def.version()).orElseGet(() -> {
            boolean hasActive = repo.findFirstBySupportTypeAndStatus(def.supportType(), ACTIVE).isPresent();
            PolicyVersion v = new PolicyVersion(def.supportType(), def.name(), def.version(), LocalDate.parse(def.effectiveDate()),
                    hasActive ? DRAFT : ACTIVE, yamlText, regulationText, "system", "초기 규정");
            if (!hasActive) {
                v.setActivatedAt(Instant.now());
            }
            v = repo.save(v);
            archive(v);
            log.info("규정 등록: {} v{} ({})", v.getSupportType(), v.getVersion(), v.getStatus());
            return v;
        });
    }

    @Transactional
    public PolicyVersion activate(Long id) {
        PolicyVersion v = get(id);
        if (!DRAFT.equals(v.getStatus())) {
            throw ApiException.conflict("검토 중(DRAFT)인 버전만 시행할 수 있습니다.");
        }
        if (v.getIndexedAt() == null) {
            index(v);
        }
        repo.findFirstBySupportTypeAndStatus(v.getSupportType(), ACTIVE).ifPresent(old -> {
            old.setStatus(RETIRED);
            repo.save(old);
        });
        v.setStatus(ACTIVE);
        v.setActivatedAt(Instant.now());
        return repo.save(v);
    }

    @Transactional
    public void deleteDraft(Long id) {
        PolicyVersion v = get(id);
        if (!DRAFT.equals(v.getStatus())) {
            throw ApiException.conflict("검토 중(DRAFT)인 버전만 삭제할 수 있습니다.");
        }
        repo.delete(v);
        parsed.remove(id);
    }

    @Transactional
    public void saveRegression(Long id, Map<String, Object> regression) {
        PolicyVersion v = get(id);
        v.setRegression(regression);
        repo.save(v);
    }

    /** 규정집을 조항 단위로 잘라 벡터 DB에 올린다(버전·시행일 메타데이터 포함). */
    @SuppressWarnings("unchecked")
    public void index(PolicyVersion v) {
        Map<String, Object> r = ai.indexPolicy(v.getSupportType(), v.getVersion(),
                v.getEffectiveDate() == null ? "" : v.getEffectiveDate().toString(), v.getRegulationText());
        v.setClauses((List<Map<String, Object>>) r.get("clauses"));
        v.setIndexedAt(Instant.now());
        repo.save(v);
        log.info("규정 색인 완료: {} v{} — 조항 {}개", v.getSupportType(), v.getVersion(), r.get("clause_count"));
    }

    private void archive(PolicyVersion v) {
        try {
            String base = "policies/" + v.getSupportType() + "/" + v.getVersion() + "/";
            storage.put(base + "regulation.md", v.getRegulationText().getBytes(StandardCharsets.UTF_8), "text/markdown; charset=utf-8");
            storage.put(base + "policy.yaml", v.getYamlText().getBytes(StandardCharsets.UTF_8), "application/yaml; charset=utf-8");
            v.setRegulationKey(base + "regulation.md");
            repo.save(v);
        } catch (RuntimeException e) {
            log.warn("규정 원문 보관 실패({} v{}): {}", v.getSupportType(), v.getVersion(), e.getMessage());
        }
    }

    /** AI 서버·벡터 DB가 나중에 뜨거나 재시작해도 시행·검토 중 규정의 색인이 유지되게 주기적으로 확인한다. */
    @Scheduled(initialDelay = 5_000, fixedDelay = 60_000)
    public void ensureIndexed() {
        for (PolicyVersion v : repo.findByStatusIn(List.of(ACTIVE, DRAFT))) {
            try {
                if (v.getIndexedAt() == null || ai.indexedCount(v.getSupportType(), v.getVersion()) == 0) {
                    index(v);
                }
            } catch (RuntimeException e) {
                log.warn("규정 색인 확인 실패({} v{}): {} — 다음 주기에 다시 시도", v.getSupportType(), v.getVersion(), e.getMessage());
                return;
            }
        }
    }

    public Map<String, Object> summary(PolicyVersion v) {
        PolicyDefinition d = definition(v);
        return Maps.of("id", v.getId(), "supportType", v.getSupportType(), "name", v.getName(), "version", v.getVersion(),
                "effectiveDate", v.getEffectiveDate() == null ? null : v.getEffectiveDate().toString(), "status", v.getStatus(),
                "createdBy", v.getCreatedBy(), "createdAt", v.getCreatedAt().toString(),
                "activatedAt", v.getActivatedAt() == null ? null : v.getActivatedAt().toString(),
                "indexedAt", v.getIndexedAt() == null ? null : v.getIndexedAt().toString(),
                "clauseCount", v.getClauses() == null ? 0 : v.getClauses().size(), "note", v.getNote(),
                "counts", Maps.of("documents", d.documents().size(), "comparisons", d.comparisons().size(),
                        "staticRules", d.staticRules().size(), "judgments", d.judgments().size()),
                "decision", Maps.of("autoThreshold", d.autoThreshold(), "ocrMinConfidence", d.ocrMinConfidence()),
                "regression", v.getRegression());
    }
}
