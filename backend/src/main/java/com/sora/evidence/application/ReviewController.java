package com.sora.evidence.application;

import java.util.List;
import java.util.Map;

import com.sora.evidence.auth.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HR 담당자 화면의 API: 검토 대기 목록과 최종 결정. */
@RestController
@RequestMapping("/api/review")
@PreAuthorize("hasAnyRole('HR','ADMIN')")
public class ReviewController {
    private final ApplicationService service;
    private final CurrentUser current;

    public ReviewController(ApplicationService service, CurrentUser current) {
        this.service = service;
        this.current = current;
    }

    /** kind: review(담당자 확인 필요, 기본) | rejected(반려·보완 필요) | all */
    @GetMapping("/queue")
    public List<Map<String, Object>> queue(@RequestParam(defaultValue = "review") String kind) {
        return service.queue(kind);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    public record DecisionRequest(String decision, String comment, String guidance) {
    }

    @PostMapping("/applications/{id}/decision")
    public Map<String, Object> decide(@PathVariable Long id, @RequestBody DecisionRequest req) {
        return service.decide(current.get(), id, req.decision(), req.comment(), req.guidance());
    }

    public record PolishRequest(String draft) {
    }

    /** 보완 안내 초안을 LLM으로 다듬는다(성명은 마스킹해 보낸다). OpenAI 키가 없으면 초안을 그대로 돌려준다. */
    @PostMapping("/applications/{id}/guidance-polish")
    public Map<String, Object> polish(@PathVariable Long id, @RequestBody PolishRequest req) {
        return service.polishGuidance(current.get(), id, req.draft());
    }
}
