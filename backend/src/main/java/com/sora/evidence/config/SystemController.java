package com.sora.evidence.config;

import java.util.Map;

import com.sora.evidence.ai.AiClient;
import com.sora.evidence.common.Maps;
import com.sora.evidence.storage.StorageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 화면 상단에 '지금 어떤 결정 모델·OCR로 돌고 있는지' 보여 주기 위한 상태 조회. */
@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final AiClient ai;
    private final StorageService storage;

    public SystemController(AiClient ai, StorageService storage) {
        this.ai = ai;
        this.storage = storage;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> out = Maps.of("storage", storage.describe().startsWith("s3") ? "S3" : "local");
        try {
            out.put("ai", ai.info());
            out.put("aiAvailable", true);
        } catch (RuntimeException e) {
            out.put("aiAvailable", false);
        }
        return out;
    }
}
