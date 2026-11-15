package com.sora.evidence.review;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sora.evidence.ai.AiClient;
import com.sora.evidence.common.ApiException;
import com.sora.evidence.common.Maps;
import com.sora.evidence.directory.UniversityDirectory;
import com.sora.evidence.policy.PolicyDefinition;
import com.sora.evidence.review.ReviewInput.DocInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 증빙 검수 엔진. 지원 종류와 무관한 하나의 흐름으로 동작하고, 무엇을 볼지는 전부 규정 YAML이 정한다.
 *
 * <pre>
 *  ① 서류 확인      필요한 서류가 다 있는지, 올린 서류가 요구한 종류가 맞는지
 *  ② 사실값 구성    신청 내용(app) · 구성원(emp) · 증빙 추출값(ev) · 외부 조회(ext)
 *  ③ 대조           신청 내용 ↔ 증빙 추출값 → 일치 / 불일치 / 읽지 못함
 *  ④ 정적 규칙      코드로 확정할 수 있는 조건(한도, 기한 등) → 충족 / 미충족 / 판단 불가
 *  ⑤ Jev 판정       의미 판단이 필요한 조건 → 충족 여부 + 확신도 + 근거 조항
 *  ⑥ 최종 판정      승인 / 반려 / 담당자 확인 + 보완 안내
 * </pre>
 *
 * 자동 반려는 '확실히 읽은 값'으로만 한다. 읽지 못했거나 확신도가 기준에 못 미치면 담당자 확인으로 보낸다.
 */
@Service
public class ReviewEngine {
    private static final Logger log = LoggerFactory.getLogger(ReviewEngine.class);
    private final ExpressionEvaluator expr;
    private final GuidanceWriter guidance;
    private final UniversityDirectory universities;
    private final AiClient ai;

    public ReviewEngine(ExpressionEvaluator expr, GuidanceWriter guidance, UniversityDirectory universities, AiClient ai) {
        this.expr = expr;
        this.guidance = guidance;
        this.universities = universities;
        this.ai = ai;
    }

    /** 신청 화면의 즉시 확인용: 서류·추출·대조까지만 수행한다(결정 모델 호출 없음). */
    public Map<String, Object> precheck(ReviewInput in) {
        return run(in, false);
    }

    public Map<String, Object> review(ReviewInput in) {
        return run(in, true);
    }

    private Map<String, Object> run(ReviewInput in, boolean full) {
        PolicyDefinition p = in.policy();
        double ocrMin = p.ocrMinConfidence();
        double docQuality = p.ocrMinDocQuality();
        double tau = p.autoThreshold();
        Map<String, String> types = p.fieldTypes();

        Map<String, Object> facts = new LinkedHashMap<>();
        Map<String, Object> app = coerceForm(p, in.form());
        facts.put("app", app);
        facts.put("emp", in.employee());
        facts.put("ctx", Maps.of("applied_on", in.appliedOn().toString()));
        facts.put("params", p.params());
        Map<String, Object> ev = new LinkedHashMap<>();
        facts.put("ev", ev);
        Map<String, Object> ext = new LinkedHashMap<>();
        facts.put("ext", ext);

        List<Map<String, Object>> issues = new ArrayList<>();
        List<Double> confidences = new ArrayList<>();

        // ---------- ① 서류 확인 + ② 증빙 사실값 ----------
        Map<String, DocInput> uploaded = new LinkedHashMap<>();
        for (DocInput d : in.documents()) {
            uploaded.put(d.docId(), d);
        }
        Map<String, Map<String, Object>> fieldInfo = new LinkedHashMap<>();   // "ev.문서.항목" → 상태·값·위치
        Map<String, String> docStatus = new LinkedHashMap<>();
        Set<String> lowQualityDocs = new LinkedHashSet<>();
        List<Map<String, Object>> docResults = new ArrayList<>();
        List<Map<String, Object>> missingDocs = new ArrayList<>();
        for (Map<String, Object> d : p.documents()) {
            String id = Maps.str(d.get("id"));
            boolean required = Maps.bool(d.get("required"), false)
                    || (d.get("required_when") != null && Boolean.TRUE.equals(expr.evalBoolean(String.valueOf(d.get("required_when")), facts)));
            DocInput di = uploaded.get(id);
            Map<String, Object> docFacts = new LinkedHashMap<>();
            Map<String, Object> res = Maps.of("id", id, "name", d.get("name"), "description", d.get("description"), "required", required);
            String status;
            String reason = null;
            if (di == null) {
                status = required ? "MISSING" : "NOT_REQUIRED";
                reason = required ? "제출되지 않았습니다." : null;
            } else {
                Map<String, Object> x = di.extraction();
                Map<String, Object> check = Maps.map(x.get("doc_check"));
                res.put("fileId", di.fileId());
                res.put("filename", di.filename());
                res.put("title", check.get("title"));
                res.put("engine", x.get("engine"));
                double q = Maps.dbl(x.get("quality"), 0);
                res.put("quality", q);
                if (!Maps.bool(check.get("matched"), false)) {
                    status = "WRONG_TYPE";
                    reason = Maps.str(check, "reason", "요구한 서류 종류가 아닙니다.");
                } else {
                    status = "OK";
                    boolean low = q < docQuality;
                    if (low) {
                        lowQualityDocs.add(id);
                    }
                    res.put("lowQuality", low);
                    docFacts.put("_title", check.get("title"));
                    readDocument(id, d, di, x, ocrMin, low, docFacts, fieldInfo);
                }
            }
            docStatus.put(id, status);
            res.put("status", status);
            res.put("reason", reason);
            docResults.add(res);
            ev.put(id, docFacts);
            if (required && !"OK".equals(status)) {
                Map<String, Object> md = Maps.of("id", id, "name", d.get("name"), "description", d.get("description"),
                        "reason", "WRONG_TYPE".equals(status) ? "올린 파일이 인정되지 않는 서류입니다. " + reason : reason);
                missingDocs.add(md);
                issues.add(GuidanceWriter.issue("MISSING:" + id, "MISSING_DOCUMENT", "REJECT", String.valueOf(d.get("name")),
                        "필요한 서류 「" + d.get("name") + "」: " + md.get("reason"), null));
            }
        }

        // ---------- 외부 조회(대학알리미 등) ----------
        for (Map<String, Object> e : p.enrichments()) {
            if ("university_directory".equals(e.get("source"))) {
                Object v = Maps.path(facts, Maps.str(e, "input", ""));
                ext.put(Maps.str(e.get("id")), v == null ? Maps.of("found", false, "summary", "확인 안 됨") : universities.lookup(String.valueOf(v)));
            }
        }

        // ---------- ③ 신청 내용 ↔ 증빙 대조 ----------
        List<Map<String, Object>> comparisons = new ArrayList<>();
        for (Map<String, Object> c : p.comparisons()) {
            if (c.get("when") != null && !Boolean.TRUE.equals(expr.evalBoolean(String.valueOf(c.get("when")), facts))) {
                continue;
            }
            String left = Maps.str(c, "left", "");
            String right = Maps.str(c, "right", "");
            String docId = right.split("\\.")[1];
            String method = Maps.str(c, "method", "text");
            String ltype = types.getOrDefault(left, "text");
            Object lv = Maps.path(facts, left);
            Map<String, Object> fi = fieldInfo.get(right);
            Map<String, Object> row = Maps.of("id", c.get("id"), "label", c.get("label"), "clause", c.get("clause"), "method", method,
                    "leftPath", left, "leftLabel", leftLabel(p, left), "leftValue", lv, "leftDisplay", ValueComparator.display(lv, ltype),
                    "rightPath", right, "docId", docId, "rightLabel", p.fieldLabel(docId, right.substring(right.lastIndexOf('.') + 1)));
            String status;
            String message = null;
            if (!"OK".equals(docStatus.get(docId))) {
                status = "NO_DOCUMENT";
                message = "서류가 제출되지 않아 대조하지 못했습니다.";
            } else {
                Object rv = fi == null ? null : fi.get("value");
                String rtype = types.getOrDefault(right, "text");
                row.put("rightValue", rv);
                row.put("rightDisplay", ValueComparator.display(rv, rtype));
                row.put("rightConfidence", fi == null ? null : fi.get("confidence"));
                row.put("fileId", fi == null ? null : fi.get("fileId"));
                row.put("bbox", fi == null ? null : fi.get("bbox"));
                row.put("page", fi == null ? null : fi.get("page"));
                String fs = fi == null ? "NOT_FOUND" : Maps.str(fi.get("status"));
                if (!"OK".equals(fs) || lv == null) {
                    status = "UNREADABLE";
                    message = lv == null ? "신청 내용에 값이 없습니다."
                            : "LOW_CONFIDENCE".equals(fs) ? "증빙의 해당 항목을 확실하게 읽지 못했습니다(인식 신뢰도 낮음)." : "증빙에서 해당 항목을 찾지 못했습니다.";
                    issues.add(GuidanceWriter.issue(Maps.str(c.get("id")), "COMPARISON", "REVIEW", Maps.str(c.get("label")),
                            c.get("label") + ": " + message, c.get("clause")));
                    confidences.add(0.0);
                } else if (ValueComparator.matches(method, lv, rv)) {
                    status = "MATCH";
                    confidences.add(Maps.dbl(fi.get("confidence"), 0));
                } else {
                    status = "MISMATCH";
                    confidences.add(Maps.dbl(fi.get("confidence"), 0));
                    message = guidance.fill(Maps.str(c, "fail_guidance", c.get("label") + "이(가) 일치하지 않습니다."),
                            Maps.of("left", row.get("leftDisplay"), "right", row.get("rightDisplay")));
                    issues.add(GuidanceWriter.issue(Maps.str(c.get("id")), "COMPARISON", "REJECT", Maps.str(c.get("label")), message, c.get("clause")));
                }
            }
            row.put("status", status);
            row.put("message", message);
            comparisons.add(row);
        }

        // ---------- ④ 정적 규칙 ----------
        List<Map<String, Object>> rules = new ArrayList<>();
        String dedupKey = null;
        for (Map<String, Object> s : p.staticRules()) {
            Map<String, Object> row = Maps.of("id", s.get("id"), "label", s.get("label"), "clause", s.get("clause"), "source", "코드 규칙");
            String status;
            String message = null;
            if ("no_duplicate".equals(s.get("builtin"))) {
                List<String> parts = new ArrayList<>();
                boolean complete = true;
                for (String k : Maps.strings(s.get("keys"))) {
                    Object v = Maps.path(facts, k);
                    complete &= v != null;
                    parts.add(ValueComparator.squash(v));
                }
                dedupKey = complete ? p.supportType() + "|" + String.join("|", parts) : null;
                row.put("expr", "동일 키 중복 접수 확인: " + String.join(", ", Maps.strings(s.get("keys"))));
                if (!full || in.duplicates() == null || dedupKey == null) {
                    status = "SKIPPED";
                } else if (in.duplicates().exists(dedupKey)) {
                    status = "FAIL";
                } else {
                    status = "PASS";
                }
            } else {
                row.put("expr", s.get("expr"));
                boolean noDoc = false;
                boolean unknown = false;
                for (String req : Maps.strings(s.get("requires"))) {
                    if (Maps.path(facts, req) == null) {
                        unknown = true;
                        noDoc |= req.startsWith("ev.") && !"OK".equals(docStatus.get(req.split("\\.")[1]));
                    }
                }
                List<Map<String, Object>> evidence = new ArrayList<>();
                for (String path : Maps.strings(s.get("evidence"))) {
                    Object v = Maps.path(facts, path);
                    evidence.add(Maps.of("path", path, "label", pathLabel(p, path), "value", ValueComparator.display(v, types.getOrDefault(path, guessType(path, v)))));
                }
                row.put("evidence", evidence);
                if (noDoc) {
                    status = "NO_DOCUMENT";
                } else if (unknown) {
                    status = "skip".equals(s.get("on_unknown")) ? "SKIPPED" : "UNKNOWN";
                } else {
                    Boolean ok = expr.evalBoolean(String.valueOf(s.get("expr")), facts);
                    status = ok == null ? "UNKNOWN" : ok ? "PASS" : "FAIL";
                }
            }
            if ("FAIL".equals(status)) {
                message = Maps.str(s, "fail_guidance", s.get("label") + " 조건을 충족하지 못했습니다.");
                issues.add(GuidanceWriter.issue(Maps.str(s.get("id")), "STATIC_RULE", "REJECT", Maps.str(s.get("label")), message, s.get("clause")));
            } else if ("UNKNOWN".equals(status)) {
                message = "판단에 필요한 값을 증빙에서 읽지 못했습니다.";
                issues.add(GuidanceWriter.issue(Maps.str(s.get("id")), "STATIC_RULE", "REVIEW", Maps.str(s.get("label")), s.get("label") + ": " + message, s.get("clause")));
                confidences.add(0.0);
            }
            row.put("status", status);
            row.put("message", message);
            rules.add(row);
        }

        // ---------- ⑤ Jev 판정 ----------
        List<Map<String, Object>> judgments = new ArrayList<>();
        Map<String, Object> model = Maps.of("provider", null, "model", null);
        if (full && !p.judgments().isEmpty()) {
            Map<String, Object> resp = callJudge(p, facts, types);
            model = Maps.of("provider", resp.get("provider"), "model", resp.get("model"), "requestedModel", resp.get("requested_model"),
                    "phases", resp.get("phases"), "requestHashes", resp.get("request_hashes"), "warnings", resp.get("warnings"),
                    "elapsedMs", resp.get("elapsed_ms"), "maskedNames", resp.get("masked_names"), "error", resp.get("error"));
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Map<String, Object> r : Maps.listOfMaps(resp.get("results"))) {
                byId.put(Maps.str(r.get("id")), r);
            }
            Map<String, String> decisions = new LinkedHashMap<>();
            for (Map<String, Object> j : p.judgments()) {
                String id = Maps.str(j.get("id"));
                Map<String, Object> r = byId.getOrDefault(id, Maps.of("status", "error",
                        "detail", "결정 모델 판정을 받지 못했습니다" + (resp.get("error") == null ? "." : ": " + resp.get("error"))));
                double threshold = Maps.dbl(j.get("threshold"), tau);
                String st = Maps.str(r, "status", "error");
                // 이 조건이 보는 증빙 항목의 상태
                boolean anyEv = false;
                boolean allDocsMissing = true;
                boolean unreliable = false;
                for (Object path : Maps.map(j.get("inputs")).values()) {
                    String pth = String.valueOf(path);
                    if (!pth.startsWith("ev.")) {
                        continue;
                    }
                    anyEv = true;
                    String[] seg = pth.split("\\.");
                    if ("OK".equals(docStatus.get(seg[1]))) {
                        allDocsMissing = false;
                    }
                    Map<String, Object> fi = fieldInfo.get(seg.length > 3 ? seg[0] + "." + seg[1] + "." + seg[2] : pth);
                    String fs = fi == null ? "NOT_FOUND" : Maps.str(fi.get("status"));
                    if ("LOW_CONFIDENCE".equals(fs) || ("NOT_FOUND".equals(fs) && lowQualityDocs.contains(seg[1]) && !seg[2].startsWith("_"))) {
                        unreliable = true;
                    }
                }
                boolean depNoDoc = Maps.strings(j.get("depends_on")).stream().anyMatch(dep -> "NO_DOCUMENT".equals(decisions.get(dep)));
                String decision;
                String reason = null;
                if ("ok".equals(st)) {
                    double conf = Maps.dbl(r.get("confidence"), 0);
                    boolean sat = Maps.bool(r.get("satisfied"), false);
                    if (unreliable) {
                        decision = "REVIEW";
                        reason = "증빙 인식 품질이 낮아 이 조건의 판정을 자동으로 확정하지 않았습니다.";
                        confidences.add(Math.min(conf, 0.5));
                    } else if (conf < threshold) {
                        decision = "REVIEW";
                        reason = String.format("확신도 %.2f가 자동 판정 기준 %.2f에 못 미칩니다.", conf, threshold);
                        confidences.add(conf);
                    } else {
                        decision = sat ? "PASS" : "FAIL";
                        confidences.add(conf);
                    }
                } else if ((anyEv && allDocsMissing) || depNoDoc) {
                    decision = "NO_DOCUMENT";
                    reason = "서류가 제출되지 않아 판정하지 않았습니다.";
                } else {
                    decision = "REVIEW";
                    reason = Maps.str(r, "detail", "판정하지 못했습니다.");
                    confidences.add(0.0);
                }
                decisions.put(id, decision);
                String message = null;
                if ("FAIL".equals(decision)) {
                    message = guidance.fill(Maps.str(j, "fail_guidance", j.get("label") + " 조건을 충족하지 못했습니다."), Maps.map(r.get("inputs")));
                    issues.add(GuidanceWriter.issue(id, "JUDGMENT", "REJECT", Maps.str(j.get("label")), message, String.join(", ", Maps.strings(j.get("clause_refs")))));
                } else if ("REVIEW".equals(decision)) {
                    issues.add(GuidanceWriter.issue(id, "JUDGMENT", "REVIEW", Maps.str(j.get("label")), j.get("label") + ": " + reason,
                            String.join(", ", Maps.strings(j.get("clause_refs")))));
                }
                Map<String, Object> row = Maps.of("id", id, "label", j.get("label"), "type", j.get("type"), "source", "결정 모델",
                        "clauseRefs", Maps.strings(j.get("clause_refs")), "clauses", r.get("clauses"), "phase", r.get("phase"),
                        "question", r.get("question"), "criteria", j.get("criteria"), "optionLabels", j.get("option_labels"), "inputs", r.get("inputs"),
                        "status", st, "answer", r.get("answer"), "answerLabel", r.get("answer_label"), "satisfied", r.get("satisfied"),
                        "passProbability", r.get("pass_probability"), "confidence", r.get("confidence"),
                        "nativeConfidence", r.get("native_confidence"), "probabilities", r.get("probabilities"),
                        "threshold", threshold, "decision", decision, "reason", reason, "message", message);
                judgments.add(row);
            }
        }

        // ---------- ⑥ 최종 판정 ----------
        boolean reject = issues.stream().anyMatch(i -> "REJECT".equals(i.get("severity")));
        boolean review = issues.stream().anyMatch(i -> "REVIEW".equals(i.get("severity")));
        String verdict = reject ? "REJECT" : review ? "REVIEW" : "APPROVE";
        double confidence = confidences.stream().mapToDouble(Double::doubleValue).min().orElse(1.0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", full ? verdict : null);
        out.put("confidence", full ? Math.round(confidence * 10000) / 10000.0 : null);
        out.put("guidance", full ? guidance.write(verdict, p.name(), issues, missingDocs) : null);
        out.put("policy", Maps.of("policyVersionId", in.policyVersionId(), "supportType", p.supportType(), "name", p.name(),
                "version", p.version(), "effectiveDate", p.effectiveDate()));
        out.put("thresholds", Maps.of("auto", tau, "ocrMinConfidence", ocrMin, "ocrMinDocQuality", docQuality));
        out.put("appliedOn", in.appliedOn().toString());
        out.put("documents", docResults);
        out.put("missingDocuments", missingDocs);
        out.put("comparisons", comparisons);
        out.put("staticRules", rules);
        out.put("judgments", judgments);
        out.put("issues", issues);
        out.put("external", ext);
        out.put("decisionModel", model);
        out.put("dedupKey", dedupKey);
        out.put("generatedAt", Instant.now().toString());
        return out;
    }

    /** 추출 결과를 사실값(ev.*)과 화면용 항목 정보로 옮긴다. 신뢰도가 낮은 값은 사실값에 넣지 않는다. */
    private void readDocument(String docId, Map<String, Object> def, DocInput di, Map<String, Object> x, double ocrMin, boolean lowQuality,
                              Map<String, Object> docFacts, Map<String, Map<String, Object>> fieldInfo) {
        Map<String, Object> fields = Maps.map(x.get("fields"));
        for (Map<String, Object> f : Maps.listOfMaps(def.get("fields"))) {
            String key = Maps.str(f.get("key"));
            Map<String, Object> r = Maps.map(fields.get(key));
            boolean found = Maps.bool(r.get("found"), false) && r.get("value") != null;
            double conf = Maps.dbl(r.get("confidence"), 0);
            String status = !found ? "NOT_FOUND" : conf < ocrMin ? "LOW_CONFIDENCE" : "OK";
            if ("presence".equals(f.get("type")) && lowQuality && "없음".equals(r.get("value"))) {
                status = "LOW_CONFIDENCE";   // 저화질 문서에서 '표기가 없다'는 판단은 믿지 않는다
            }
            docFacts.put(key, "OK".equals(status) ? r.get("value") : null);
            fieldInfo.put("ev." + docId + "." + key, Maps.of("status", status, "value", r.get("value"), "confidence", conf,
                    "bbox", r.get("bbox"), "page", r.get("page"), "fileId", di.fileId(), "label", f.get("label")));
        }
        Map<String, Object> tables = Maps.map(x.get("tables"));
        for (Map<String, Object> t : Maps.listOfMaps(def.get("tables"))) {
            String key = Maps.str(t.get("key"));
            List<Map<String, Object>> rows = Maps.listOfMaps(tables.get(key));
            double conf = rows.stream().mapToDouble(r -> Maps.dbl(r.get("_confidence"), 0)).min().orElse(0);
            String status = rows.isEmpty() ? "NOT_FOUND" : conf < ocrMin ? "LOW_CONFIDENCE" : "OK";
            List<Map<String, Object>> clean = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> c = new LinkedHashMap<>();
                r.forEach((k, v) -> {
                    if (!k.startsWith("_")) {
                        c.put(k, v);
                    }
                });
                clean.add(c);
            }
            docFacts.put(key, "OK".equals(status) ? clean : null);
            fieldInfo.put("ev." + docId + "." + key, Maps.of("status", status, "value", clean, "confidence", conf,
                    "fileId", di.fileId(), "label", t.get("label")));
        }
    }

    private Map<String, Object> callJudge(PolicyDefinition p, Map<String, Object> facts, Map<String, String> types) {
        Set<String> names = new LinkedHashSet<>();
        types.forEach((path, type) -> {
            if ("person_name".equals(type) || "name_list".equals(type)) {
                names.addAll(Maps.strings(Maps.path(facts, path)));
            }
        });
        Map<String, Object> req = Maps.of("support_type", p.supportType(), "support_name", p.name(), "policy_version", p.version(),
                "facts", facts, "field_types", types, "pii_names", new ArrayList<>(names), "judgments", p.judgments());
        try {
            return ai.judge(req);
        } catch (ApiException e) {
            log.warn("결정 모델 판정 실패: {}", e.getMessage());
            return Maps.of("results", List.of(), "error", e.getMessage());
        }
    }

    /** 신청서 입력값을 항목 유형에 맞춰 정규화한다(금액 → 정수, 학기 → YYYY-N). */
    public Map<String, Object> coerceForm(PolicyDefinition p, Map<String, Object> form) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> f : p.applicationForm()) {
            String key = Maps.str(f.get("key"));
            Object v = form == null ? null : form.get(key);
            if (v instanceof String s && s.isBlank()) {
                v = null;
            }
            if (v != null) {
                switch (Maps.str(f, "type", "text")) {
                    case "money" -> v = ValueComparator.toLong(v);
                    case "semester" -> v = ValueComparator.semester(v);
                    default -> v = String.valueOf(v).trim();
                }
            }
            out.put(key, v);
        }
        return out;
    }

    private static String leftLabel(PolicyDefinition p, String path) {
        return pathLabel(p, path);
    }

    private static String pathLabel(PolicyDefinition p, String path) {
        String[] seg = path.split("\\.");
        if (path.startsWith("app.")) {
            return p.applicationForm().stream().filter(f -> seg[1].equals(f.get("key"))).map(f -> Maps.str(f.get("label"))).findFirst().orElse(seg[1]);
        }
        if (path.startsWith("ev.") && seg.length >= 3) {
            return p.fieldLabel(seg[1], seg[2]);
        }
        return switch (path) {
            case "emp.name" -> "구성원 성명";
            case "emp.hire_date" -> "입사일";
            case "ctx.applied_on" -> "신청일";
            default -> path.startsWith("params.") ? "규정 기준값(" + seg[1] + ")" : path;
        };
    }

    private static String guessType(String path, Object v) {
        return v instanceof Number && (path.contains("amount") || path.contains("limit") || path.contains("total")) ? "money" : "text";
    }
}
