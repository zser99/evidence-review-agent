package com.sora.evidence.policy;

import java.util.List;
import java.util.Map;

import com.sora.evidence.ai.AiClient;
import com.sora.evidence.application.AuditService;
import com.sora.evidence.auth.CurrentUser;
import com.sora.evidence.auth.User;
import com.sora.evidence.common.Maps;
import com.sora.evidence.evaluation.EvaluationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PolicyController {
    private final PolicyService policies;
    private final CurrentUser current;
    private final AiClient ai;
    private final EvaluationService evaluations;
    private final AuditService audit;

    public PolicyController(PolicyService policies, CurrentUser current, AiClient ai, EvaluationService evaluations, AuditService audit) {
        this.policies = policies;
        this.current = current;
        this.ai = ai;
        this.evaluations = evaluations;
        this.audit = audit;
    }

    /** 신청 화면용: 시행 중인 지원 종류와 신청서 양식·필요 서류. */
    @GetMapping("/support-types")
    public List<Map<String, Object>> supportTypes() {
        return policies.actives().stream().map(v -> {
            Map<String, Object> m = policies.definition(v).publicView();
            m.put("policyVersionId", v.getId());
            return m;
        }).toList();
    }

    @GetMapping("/policies")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    public List<Map<String, Object>> list() {
        return policies.all().stream().map(policies::summary).toList();
    }

    @GetMapping("/policies/{id}")
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    public Map<String, Object> detail(@PathVariable Long id) {
        PolicyVersion v = policies.get(id);
        Map<String, Object> m = policies.summary(v);
        m.put("yamlText", v.getYamlText());
        m.put("regulationText", v.getRegulationText());
        m.put("clauses", v.getClauses());
        m.put("definition", policies.definition(v).publicView());
        m.put("judgments", policies.definition(v).judgments().stream().map(j -> Maps.of("id", j.get("id"), "label", j.get("label"),
                "type", j.get("type"), "clauseRefs", j.get("clause_refs"), "instructions", j.get("instructions"),
                "criteria", j.get("criteria"), "dependsOn", j.get("depends_on"))).toList());
        m.put("staticRules", policies.definition(v).staticRules().stream().map(s -> Maps.of("id", s.get("id"), "label", s.get("label"),
                "clause", s.get("clause"), "expr", s.get("expr") == null ? "내장 규칙: " + s.get("builtin") : s.get("expr"))).toList());
        m.put("comparisons", policies.definition(v).comparisons().stream().map(c -> Maps.of("id", c.get("id"), "label", c.get("label"),
                "left", c.get("left"), "right", c.get("right"), "method", c.get("method"))).toList());
        return m;
    }

    public record PolicyRequest(String yamlText, String regulationText, String note) {
    }

    @PostMapping("/policies/validate")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> validate(@RequestBody PolicyRequest req) {
        List<String> errors = policies.validate(req.yamlText() == null ? "" : req.yamlText(), req.regulationText());
        return Maps.of("valid", errors.isEmpty(), "errors", errors);
    }

    @PostMapping("/policies")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> create(@RequestBody PolicyRequest req) {
        User u = current.get();
        PolicyVersion v = policies.createDraft(req.yamlText() == null ? "" : req.yamlText(),
                req.regulationText() == null ? "" : req.regulationText(), u.getUsername(), req.note());
        audit.log(null, u, "POLICY_DRAFT_CREATED", Maps.of("policyVersionId", v.getId(), "supportType", v.getSupportType(), "version", v.getVersion()));
        return policies.summary(v);
    }

    /** 개정 규정으로 정답 세트 회귀 테스트를 돌려 판정이 바뀌는 건을 찾는다(비동기). */
    @PostMapping("/policies/{id}/regression")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> regression(@PathVariable Long id) {
        User u = current.get();
        return evaluations.view(evaluations.start("REGRESSION", policies.get(id).getId(), u.getUsername()), false);
    }

    @PostMapping("/policies/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> activate(@PathVariable Long id) {
        User u = current.get();
        PolicyVersion v = policies.activate(id);
        audit.log(null, u, "POLICY_ACTIVATED", Maps.of("policyVersionId", v.getId(), "supportType", v.getSupportType(), "version", v.getVersion()));
        return policies.summary(v);
    }

    @DeleteMapping("/policies/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> delete(@PathVariable Long id) {
        policies.deleteDraft(id);
        return Maps.of("deleted", true);
    }

    public record DraftRequest(String supportType, String regulationText) {
    }

    /** 규정집에서 Jev에 물을 질문(judgments) 초안을 LLM으로 제안받는다(선택 기능, OpenAI 키 필요). */
    @PostMapping("/policies/draft-judgments")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> draft(@RequestBody DraftRequest req) {
        return ai.draftJudgments(req.supportType(), req.regulationText());
    }
}
