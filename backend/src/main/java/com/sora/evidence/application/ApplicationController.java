package com.sora.evidence.application;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.sora.evidence.auth.CurrentUser;
import com.sora.evidence.auth.User;
import com.sora.evidence.common.ApiException;
import com.sora.evidence.common.Maps;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 신청 구성원 화면의 API. HR·관리자는 같은 조회 API로 모든 건을 볼 수 있다(권한은 서비스에서 검증). */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {
    private final ApplicationService service;
    private final CurrentUser current;

    public ApplicationController(ApplicationService service, CurrentUser current) {
        this.service = service;
        this.current = current;
    }

    public record CreateRequest(String supportType, Map<String, Object> form) {
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest req) {
        User u = current.get();
        if (req.supportType() == null || req.supportType().isBlank()) {
            throw ApiException.badRequest("지원 종류를 선택해 주세요.");
        }
        return service.detail(u, service.create(u, req.supportType(), req.form()).getId());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody CreateRequest req) {
        User u = current.get();
        service.updateForm(u, id, req.form());
        return service.detail(u, id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.deleteDraft(current.get(), id);
        return Maps.of("deleted", true);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "mine") String scope, @RequestParam(required = false) String status) {
        User u = current.get();
        if ("all".equals(scope)) {
            if (!u.isStaff()) {
                throw ApiException.forbidden("전체 신청 목록은 HR 담당자만 볼 수 있습니다.");
            }
            return service.listAll(status);
        }
        return service.listMine(u);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return service.detail(current.get(), id);
    }

    /** 증빙을 올리면 바로 추출해, 뽑힌 항목·신청 내용과의 대조·빠진 서류를 담은 상세를 돌려준다. */
    @PostMapping(value = "/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable Long id, @RequestParam String docId, @RequestParam MultipartFile file) throws IOException {
        User u = current.get();
        service.upload(u, id, docId, file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return service.detail(u, id);
    }

    @DeleteMapping("/{id}/evidence/{fileId}")
    public Map<String, Object> removeEvidence(@PathVariable Long id, @PathVariable Long fileId) {
        User u = current.get();
        service.removeEvidence(u, id, fileId);
        return service.detail(u, id);
    }

    @GetMapping("/{id}/evidence/{fileId}/pages/{page}")
    public ResponseEntity<byte[]> page(@PathVariable Long id, @PathVariable Long fileId, @PathVariable int page) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePrivate())
                .body(service.page(current.get(), id, fileId, page));
    }

    @GetMapping("/{id}/evidence/{fileId}/original")
    public ResponseEntity<byte[]> original(@PathVariable Long id, @PathVariable Long fileId) {
        EvidenceFile f = service.original(current.get(), id, fileId);
        String name = URLEncoder.encode(f.getFilename(), StandardCharsets.UTF_8).replace("+", "%20");
        String type = f.getStorageKey().endsWith(".pdf") ? "application/pdf" : f.getStorageKey().endsWith(".png") ? "image/png" : "image/jpeg";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + name)
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore())
                .body(service.bytes(f));
    }

    @PostMapping("/{id}/submit")
    public Map<String, Object> submit(@PathVariable Long id) {
        return service.submit(current.get(), id);
    }

    /** 과거 검수 실행의 스냅샷(감사·이의제기 대응용). */
    @GetMapping("/{id}/runs/{runNo}")
    public Map<String, Object> run(@PathVariable Long id, @PathVariable int runNo) {
        return service.run(current.get(), id, runNo);
    }
}
