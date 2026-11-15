package com.sora.evidence.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sora.evidence.common.ApiException;
import com.sora.evidence.common.Maps;
import com.sora.evidence.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** FastAPI AI 서버(증빙 추출·규정 검색·결정 모델 판정) 호출. 클러스터 내부 통신만 쓴다. */
@Component
public class AiClient {
    private static final Logger log = LoggerFactory.getLogger(AiClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private final RestClient http;
    private final ObjectMapper json;

    public AiClient(AppProperties props, ObjectMapper json) {
        this.json = json;
        HttpClient jdk = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(jdk);
        rf.setReadTimeout(Duration.ofSeconds(props.ai().timeoutSeconds()));
        RestClient.Builder b = RestClient.builder().baseUrl(props.ai().baseUrl()).requestFactory(rf);
        if (props.ai().internalToken() != null && !props.ai().internalToken().isBlank()) {
            b.defaultHeader("X-Internal-Token", props.ai().internalToken());
        }
        this.http = b.build();
    }

    private <T> T call(String what, java.util.function.Supplier<T> fn) {
        try {
            return fn.get();
        } catch (RestClientResponseException e) {
            String detail = e.getResponseBodyAsString();
            try {
                Object d = json.readValue(detail, Map.class).get("detail");
                if (d != null) {
                    detail = String.valueOf(d);
                }
            } catch (Exception ignore) {
                // 본문이 JSON이 아니면 그대로 쓴다
            }
            int sc = e.getStatusCode().value();
            log.warn("AI 서버 오류({}): HTTP {} {}", what, sc, detail);
            if (sc == 400 || sc == 413 || sc == 415 || sc == 422) {
                throw ApiException.badRequest(sc == 422 ? "AI 서버 요청 형식 오류: " + detail : detail);
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 서버 처리 중 오류가 발생했습니다(" + what + ").");
        } catch (ResourceAccessException e) {
            log.warn("AI 서버 연결 실패({}): {}", what, e.getMessage());
            throw ApiException.unavailable("AI 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** 증빙 한 건에서 문서 정의(docSpec)대로 항목·표를 추출한다. */
    public Map<String, Object> extract(byte[] data, String extension, Map<String, Object> docSpec, boolean includePages) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return "evidence." + extension;   // 원래 파일명(개인정보 포함 가능)은 보내지 않는다
            }
        });
        try {
            form.add("spec", json.writeValueAsString(docSpec));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        form.add("include_pages", String.valueOf(includePages));
        return call("증빙 추출", () -> http.post().uri("/v1/extract").contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(MAP));
    }

    public Map<String, Object> judge(Map<String, Object> request) {
        return call("규정 조건 판정", () -> http.post().uri("/v1/judge").contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(MAP));
    }

    public Map<String, Object> indexPolicy(String supportType, String version, String effectiveDate, String regulationText) {
        Map<String, Object> req = Maps.of("support_type", supportType, "version", version,
                "effective_date", effectiveDate == null ? "" : effectiveDate, "regulation_text", regulationText);
        return call("규정 색인", () -> http.post().uri("/v1/policies/index").contentType(MediaType.APPLICATION_JSON).body(req).retrieve().body(MAP));
    }

    public int indexedCount(String supportType, String version) {
        Map<String, Object> r = call("규정 색인 확인", () -> http.get()
                .uri(u -> u.path("/v1/policies/indexed").queryParam("support_type", supportType).queryParam("version", version).build())
                .retrieve().body(MAP));
        return ((Number) r.getOrDefault("clause_count", 0)).intValue();
    }

    public Map<String, Object> calibrate(List<Map<String, Object>> samples, double targetAccuracy) {
        Map<String, Object> req = Maps.of("samples", samples, "target_accuracy", targetAccuracy);
        return call("확신도 보정", () -> http.post().uri("/v1/calibrate").contentType(MediaType.APPLICATION_JSON).body(req).retrieve().body(MAP));
    }

    public Map<String, Object> polish(String draft, String verdict, List<String> piiNames) {
        Map<String, Object> req = Maps.of("draft", draft, "verdict", verdict, "pii_names", piiNames);
        return call("안내 문구 다듬기", () -> http.post().uri("/v1/guidance/polish").contentType(MediaType.APPLICATION_JSON).body(req).retrieve().body(MAP));
    }

    public Map<String, Object> draftJudgments(String supportType, String regulationText) {
        Map<String, Object> req = Maps.of("support_type", supportType, "regulation_text", regulationText);
        return call("규정 조건 초안", () -> http.post().uri("/v1/policies/draft").contentType(MediaType.APPLICATION_JSON).body(req).retrieve().body(MAP));
    }

    public Map<String, Object> info() {
        return call("상태 조회", () -> http.get().uri("/v1/info").retrieve().body(MAP));
    }
}
