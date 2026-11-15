package com.sora.evidence.directory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sora.evidence.common.Maps;
import com.sora.evidence.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 교육기관의 실재 여부·기관 유형 조회(대학알리미 공시 정보 기준).
 *
 * <p>조회 결과는 Redis에 캐시한다. 대학알리미 OpenAPI 주소·키(UNIVERSITY_API_URL/KEY)가 설정되면 API를 먼저 조회하고,
 * 설정이 없거나 실패하면 내장 목록(seed/universities.csv)으로 조회한다. 동명·조회 실패 건은 담당자 확인으로 넘어간다.
 */
@Service
public class UniversityDirectory {
    private static final Logger log = LoggerFactory.getLogger(UniversityDirectory.class);
    private static final Pattern BASE = Pattern.compile("^(.*?(?:대학교|대학))(.*)$");
    private final AppProperties props;
    private final ObjectMapper json;
    private final Map<String, Map<String, String>> seed = new HashMap<>();

    public UniversityDirectory(AppProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    @PostConstruct
    void load() {
        Path csv = Path.of(props.seedDir(), "universities.csv");
        if (!Files.exists(csv)) {
            log.warn("대학 목록 파일이 없습니다: {}", csv.toAbsolutePath());
            return;
        }
        try {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            for (String line : lines.subList(1, lines.size())) {
                String[] c = line.split(",", -1);
                if (c.length >= 3 && !c[0].isBlank()) {
                    seed.put(normalize(c[0]), Map.of("name", c[0].trim(), "type", c[1].trim(), "establishment", c[2].trim()));
                }
            }
            log.info("대학 목록 {}건 로드", seed.size());
        } catch (IOException e) {
            log.warn("대학 목록 로드 실패: {}", e.getMessage());
        }
    }

    public static String normalize(String name) {
        return name == null ? "" : name.replaceAll("[\\s()（）]", "").replace("학교법인", "");
    }

    @Cacheable(cacheNames = "universities", key = "T(com.sora.evidence.directory.UniversityDirectory).normalize(#name)",
            condition = "#name != null")
    public Map<String, Object> lookup(String name) {
        String n = normalize(name);
        if (n.isEmpty()) {
            return Maps.of("found", false, "summary", "확인 안 됨");
        }
        Map<String, String> hit = find(n);
        String suffix = "";
        if (hit == null) {
            Matcher m = BASE.matcher(n);
            if (m.matches() && !m.group(2).isEmpty()) {
                hit = find(m.group(1));
                suffix = m.group(2);
            }
        }
        if (hit == null) {
            return Maps.of("found", false, "exact", false, "query", name, "summary", "조회되지 않음");
        }
        String base = "'" + hit.get("name") + "' " + hit.get("type") + "(" + hit.get("establishment") + ")";
        String summary = suffix.isEmpty() ? "조회됨 — " + base
                : "조회됨 — " + base + " · 증빙의 기관명에 부속 표기 '" + suffix + "' 포함";
        return Maps.of("found", true, "exact", suffix.isEmpty(), "query", name, "name", hit.get("name"),
                "type", hit.get("type"), "establishment", hit.get("establishment"), "suffix", suffix, "summary", summary);
    }

    private Map<String, String> find(String normalized) {
        Map<String, String> api = fromApi(normalized);
        return api != null ? api : seed.get(normalized);
    }

    /**
     * 대학알리미 OpenAPI 연동 지점. URL 템플릿의 {name}, {key}를 채워 호출하고, 응답 JSON에서 학교명이 같은 항목을 찾는다.
     * 실제 응답 필드명은 키 발급 뒤 확인해 맞춰야 하므로 흔한 필드명 후보를 넓게 받는다.
     */
    private Map<String, String> fromApi(String normalized) {
        String tpl = props.university() == null ? null : props.university().apiUrl();
        if (tpl == null || tpl.isBlank()) {
            return null;
        }
        try {
            String url = tpl.replace("{name}", URLEncoder.encode(normalized, StandardCharsets.UTF_8))
                    .replace("{key}", URLEncoder.encode(props.university().apiKey() == null ? "" : props.university().apiKey(), StandardCharsets.UTF_8));
            HttpResponse<String> res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                    .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return null;
            }
            return search(json.readTree(res.body()), normalized);
        } catch (Exception e) {
            log.warn("대학알리미 조회 실패 — 내장 목록으로 대체: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, String> search(JsonNode node, String normalized) {
        if (node.isObject()) {
            String name = first(node, "schlKrnNm", "schoolName", "schlNm", "학교명", "name");
            if (name != null && normalize(name).equals(normalized)) {
                String type = first(node, "schlKndNm", "schlDivNm", "schoolType", "학교종류", "type");
                String est = first(node, "fndnDivNm", "estbDivNm", "establishment", "설립구분");
                return Map.of("name", name, "type", type == null ? "대학" : type, "establishment", est == null ? "-" : est);
            }
        }
        for (JsonNode child : node) {
            Map<String, String> r = search(child, normalized);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static String first(JsonNode n, String... keys) {
        for (String k : keys) {
            if (n.hasNonNull(k) && n.get(k).isValueNode()) {
                return n.get(k).asText();
            }
        }
        return null;
    }
}
