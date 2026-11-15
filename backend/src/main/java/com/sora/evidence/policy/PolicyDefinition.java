package com.sora.evidence.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.sora.evidence.common.Maps;

/**
 * 지원 종류 설정 YAML을 읽기 좋게 감싼 객체.
 *
 * <p>뽑을 항목(documents), 대조(comparisons), 정적 규칙(static_rules), Jev 판정 질문(judgments)이 모두 설정에 있고
 * 판정 흐름 코드는 이 객체만 본다. 그래서 의료비 같은 새 지원 종류는 YAML만 더하면 같은 흐름으로 돌아간다.
 */
public class PolicyDefinition {
    private static final YAMLMapper YAML = new YAMLMapper();
    private final Map<String, Object> root;

    private PolicyDefinition(Map<String, Object> root) {
        this.root = root;
    }

    public static PolicyDefinition parse(String yamlText) {
        try {
            Map<String, Object> m = YAML.readValue(yamlText, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            if (m == null) {
                throw new IllegalArgumentException("YAML이 비어 있습니다.");
            }
            return new PolicyDefinition(m);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("YAML 문법 오류: " + e.getOriginalMessage());
        }
    }

    public Map<String, Object> root() {
        return root;
    }

    public String supportType() {
        return Maps.str(root.get("support_type"));
    }

    public String name() {
        return Maps.str(root.get("name"));
    }

    public String version() {
        return Maps.str(root.get("version"));
    }

    public String effectiveDate() {
        return Maps.str(root.get("effective_date"));
    }

    public Map<String, Object> params() {
        return Maps.map(root.get("params"));
    }

    public Map<String, Object> decision() {
        return Maps.map(root.get("decision"));
    }

    /** 확신도가 이 값 이상이면 자동 판정, 미만이면 담당자 확인. */
    public double autoThreshold() {
        return Maps.dbl(decision().get("auto_threshold"), 0.85);
    }

    /** 항목의 OCR 신뢰도가 이 값 미만이면 '읽지 못함'. */
    public double ocrMinConfidence() {
        return Maps.dbl(decision().get("ocr_min_confidence"), 0.80);
    }

    /** 문서 전체의 평균 인식 신뢰도가 이 값 미만이면 저화질로 보고, 찾지 못한 항목을 '없음'이 아니라 '읽지 못함'으로 다룬다. */
    public double ocrMinDocQuality() {
        return Maps.dbl(decision().get("ocr_min_doc_quality"), 0.90);
    }

    public boolean autoFinalizeApproval() {
        return Maps.bool(decision().get("auto_finalize_approval"), true);
    }

    public List<Map<String, Object>> applicationForm() {
        return Maps.listOfMaps(root.get("application_form"));
    }

    public List<Map<String, Object>> documents() {
        return Maps.listOfMaps(root.get("documents"));
    }

    public Map<String, Object> document(String id) {
        return documents().stream().filter(d -> id.equals(d.get("id"))).findFirst().orElse(null);
    }

    public List<Map<String, Object>> enrichments() {
        return Maps.listOfMaps(root.get("enrichments"));
    }

    public List<Map<String, Object>> comparisons() {
        return Maps.listOfMaps(root.get("comparisons"));
    }

    public List<Map<String, Object>> staticRules() {
        return Maps.listOfMaps(root.get("static_rules"));
    }

    public List<Map<String, Object>> judgments() {
        return Maps.listOfMaps(root.get("judgments"));
    }

    /** facts 경로 → 항목 유형. 외부 API로 보내기 전 마스킹 대상(성명 등)을 가리는 데 쓴다. */
    public Map<String, String> fieldTypes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> f : applicationForm()) {
            out.put("app." + f.get("key"), Maps.str(f, "type", "text"));
        }
        out.put("emp.name", "person_name");
        out.put("emp.employee_no", "identifier");
        for (Map<String, Object> d : documents()) {
            for (Map<String, Object> f : Maps.listOfMaps(d.get("fields"))) {
                out.put("ev." + d.get("id") + "." + f.get("key"), Maps.str(f, "type", "text"));
            }
            for (Map<String, Object> t : Maps.listOfMaps(d.get("tables"))) {
                for (Map<String, Object> c : Maps.listOfMaps(t.get("columns"))) {
                    out.put("ev." + d.get("id") + "." + t.get("key") + "." + c.get("key"), Maps.str(c, "type", "text"));
                }
            }
        }
        return out;
    }

    public String fieldLabel(String docId, String key) {
        Map<String, Object> d = document(docId);
        if (d == null) {
            return key;
        }
        for (Map<String, Object> f : Maps.listOfMaps(d.get("fields"))) {
            if (key.equals(f.get("key"))) {
                return Maps.str(f, "label", key);
            }
        }
        for (Map<String, Object> t : Maps.listOfMaps(d.get("tables"))) {
            if (key.equals(t.get("key"))) {
                return Maps.str(t, "label", key);
            }
        }
        return key;
    }

    /** 화면(신청서 양식·필요 서류 안내)에 내려보내는 요약. 판정 로직·예시는 빼고 보낸다. */
    public Map<String, Object> publicView() {
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map<String, Object> d : documents()) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Map<String, Object> f : Maps.listOfMaps(d.get("fields"))) {
                fields.add(Maps.of("key", f.get("key"), "label", f.get("label"), "type", f.get("type"),
                        "required", Maps.bool(f.get("required"), false)));
            }
            List<Map<String, Object>> tables = new ArrayList<>();
            for (Map<String, Object> t : Maps.listOfMaps(d.get("tables"))) {
                tables.add(Maps.of("key", t.get("key"), "label", t.get("label"),
                        "columns", Maps.listOfMaps(t.get("columns")).stream().map(c -> Maps.of("key", c.get("key"), "type", c.get("type"))).toList()));
            }
            docs.add(Maps.of("id", d.get("id"), "name", d.get("name"), "description", d.get("description"),
                    "required", Maps.bool(d.get("required"), false), "requiredWhen", d.get("required_when"),
                    "fields", fields, "tables", tables));
        }
        return Maps.of("supportType", supportType(), "name", name(), "version", version(), "effectiveDate", effectiveDate(),
                "description", root.get("description"), "icon", root.get("icon"), "form", applicationForm(), "documents", docs,
                "params", params(), "decision", Maps.of("autoThreshold", autoThreshold(), "ocrMinConfidence", ocrMinConfidence(),
                        "ocrMinDocQuality", ocrMinDocQuality(), "autoFinalizeApproval", autoFinalizeApproval()),
                "counts", Maps.of("comparisons", comparisons().size(), "staticRules", staticRules().size(), "judgments", judgments().size()));
    }
}
