package com.sora.evidence.evaluation;

import java.util.List;
import java.util.Map;

import com.sora.evidence.auth.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 검증 결과 화면의 API. */
@RestController
@RequestMapping("/api/evaluations")
@PreAuthorize("hasAnyRole('HR','ADMIN')")
public class EvaluationController {
    private final EvaluationService service;
    private final CurrentUser current;

    public EvaluationController(EvaluationService service, CurrentUser current) {
        this.service = service;
        this.current = current;
    }

    /** 시행 중인 규정으로 정답 세트 검증을 시작한다(비동기). 진행 상황은 /latest 로 확인한다. */
    @PostMapping
    public Map<String, Object> start() {
        return service.view(service.start("EVALUATION", null, current.get().getUsername()), false);
    }

    @GetMapping("/latest")
    public Map<String, Object> latest() {
        return service.latest();
    }

    @GetMapping
    public List<Map<String, Object>> recent() {
        return service.recent();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return service.view(service.get(id), true);
    }
}
