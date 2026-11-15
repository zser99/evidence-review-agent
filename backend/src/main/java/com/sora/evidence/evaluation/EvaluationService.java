package com.sora.evidence.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sora.evidence.ai.AiClient;
import com.sora.evidence.common.ApiException;
import com.sora.evidence.common.Maps;
import com.sora.evidence.config.AppProperties;
import com.sora.evidence.policy.PolicyDefinition;
import com.sora.evidence.policy.PolicyService;
import com.sora.evidence.policy.PolicyVersion;
import com.sora.evidence.review.ReviewEngine;
import com.sora.evidence.review.ReviewInput;
import com.sora.evidence.review.ReviewInput.DocInput;
import com.sora.evidence.review.ValueComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 정답 세트 검증.
 *
 * <p>승인·반려 정답을 붙인 가상 증빙 세트를 실제 신청과 똑같은 판정 흐름(ReviewEngine)에 통과시켜
 * ① 항목 추출이 맞은 비율 ② 최종 판정이 맞은 비율 ③ 확신도 구간별 실제 정답률 ④ 담당자 확인으로 넘어간 비율을 잰다.
 * 규정 개정(DRAFT) 버전에 대해서는 같은 세트로 회귀 테스트를 돌려 시행 중 버전과 판정이 달라지는 건을 찾는다.
 */
@Service
public class EvaluationService {
    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final double TARGET_ACCURACY = 0.97;
    private final EvaluationRunRepository repo;
    private final PolicyService policies;
    private final ReviewEngine engine;
    private final AiClient ai;
    private final AppProperties props;
    private final ObjectMapper json;
    private final TaskExecutor executor;
    /** OCR 추출은 결정적이므로 (파일, 문서 정의)별로 한 번만 수행한다. */
    private final Map<String, Map<String, Object>> extractionCache = new ConcurrentHashMap<>();

    public EvaluationService(EvaluationRunRepository repo, PolicyService policies, ReviewEngine engine, AiClient ai,
                             AppProperties props, ObjectMapper json,
                             @Qualifier("applicationTaskExecutor") TaskExecutor applicationTaskExecutor) {
        this.repo = repo;
        this.policies = policies;
        this.engine = engine;
        this.ai = ai;
        this.props = props;
        this.json = json;
        this.executor = applicationTaskExecutor;
    }

    public EvaluationRun start(String kind, Long policyVersionId, String user) {
        if (repo.existsByStatus("RUNNING")) {
            throw ApiException.conflict("이미 실행 중인 검증이 있습니다. 끝난 뒤 다시 시도해 주세요.");
        }
        if (!Files.exists(goldenDir().resolve("manifest.json"))) {
            throw ApiException.notFound("정답 세트(seed/golden/manifest.json)를 찾을 수 없습니다.");
        }
        EvaluationRun run = repo.save(new EvaluationRun(kind, policyVersionId, user));
        executor.execute(() -> execute(run.getId()));
        return run;
    }

    /** 서버가 재시작되면 중간에 끊긴 실행을 실패로 정리한다. */
    public void failStaleRuns() {
        for (EvaluationRun r : repo.findByStatus("RUNNING")) {
            r.setStatus("FAILED");
            r.setError("서버 재시작으로 중단되었습니다.");
            r.setFinishedAt(Instant.now());
            repo.save(r);
        }
    }

    private Path goldenDir() {
        return Path.of(props.seedDir(), "golden");
    }

    private void execute(Long runId) {
        EvaluationRun run = repo.findById(runId).orElseThrow();
        Instant t0 = Instant.now();
        try {
            Map<String, Object> manifest = json.readValue(goldenDir().resolve("manifest.json").toFile(), new TypeReference<>() {
            });
            List<Map<String, Object>> cases = Maps.listOfMaps(manifest.get("cases"));
            boolean regression = "REGRESSION".equals(run.getKind());
            Map<String, PolicyVersion> target = new LinkedHashMap<>();
            PolicyVersion draft = null;
            if (regression) {
                draft = policies.get(run.getPolicyVersionId());
                String st = draft.getSupportType();
                cases = cases.stream().filter(c -> st.equals(c.get("support_type"))).toList();
                target.put(st, draft);
            } else {
                for (PolicyVersion v : policies.actives()) {
                    target.put(v.getSupportType(), v);
                }
                cases = cases.stream().filter(c -> target.containsKey(Maps.str(c.get("support_type")))).toList();
            }
            run.setProgressTotal(cases.size() * (regression ? 2 : 1));
            repo.save(run);

            List<Map<String, Object>> baseResults = new ArrayList<>();
            if (regression) {
                PolicyVersion base = policies.actives().stream().filter(v -> v.getSupportType().equals(target.keySet().iterator().next())).findFirst().orElse(null);
                if (base != null) {
                    for (Map<String, Object> c : cases) {
                        baseResults.add(evalCase(base, c));
                        progress(run);
                    }
                }
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> c : cases) {
                results.add(evalCase(target.get(Maps.str(c.get("support_type"))), c));
                progress(run);
            }
            Map<String, Object> summary = summarize(results);
            summary.put("goldenSet", Maps.of("name", manifest.get("name"), "version", manifest.get("version")));
            summary.put("elapsedSeconds", Duration.between(t0, Instant.now()).toSeconds());
            summary.put("policies", target.values().stream().map(v -> Maps.of("supportType", v.getSupportType(), "name", v.getName(),
                    "version", v.getVersion(), "status", v.getStatus(), "autoThreshold", policies.definition(v).autoThreshold())).toList());
            if (regression) {
                Map<String, Object> reg = regressionDiff(draft, baseResults, results);
                summary.put("regression", reg);
                policies.saveRegression(draft.getId(), reg);
            }
            Map<String, Object> model = Maps.map(summary.get("decisionModel"));
            run.setProvider(Maps.str(model.get("provider")));
            run.setModel(Maps.str(model.get("model")));
            run.setSummary(summary);
            run.setCases(results);
            run.setStatus("DONE");
        } catch (Exception e) {
            log.error("정답 세트 검증 실패", e);
            run.setStatus("FAILED");
            run.setError(e.getMessage());
        }
        run.setFinishedAt(Instant.now());
        repo.save(run);
    }

    private void progress(EvaluationRun run) {
        run.setProgressDone(run.getProgressDone() + 1);
        if (run.getProgressDone() % 3 == 0) {
            repo.save(run);
        }
    }

    private Map<String, Object> extractCached(byte[] data, String ext, Map<String, Object> docDef) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(data);
        md.update(json.writeValueAsBytes(docDef));
        String key = HexFormat.of().formatHex(md.digest());
        Map<String, Object> hit = extractionCache.get(key);
        if (hit == null) {
            hit = ai.extract(data, ext, docDef, false);
            hit.remove("lines");
            extractionCache.put(key, hit);
        }
        return hit;
    }

    /** 정답 세트 한 건을 실제 신청과 같은 흐름으로 검수하고 정답과 비교한다. */
    private Map<String, Object> evalCase(PolicyVersion pv, Map<String, Object> c) throws Exception {
        PolicyDefinition def = policies.definition(pv);
        double ocrMin = def.ocrMinConfidence();
        List<DocInput> docs = new ArrayList<>();
        List<Map<String, Object>> misses = new ArrayList<>();
        int fTotal = 0;
        int fCorrect = 0;
        int fWrong = 0;
        int fUnread = 0;
        for (Map<String, Object> d : Maps.listOfMaps(c.get("documents"))) {
            String docId = Maps.str(d.get("doc_id"));
            Map<String, Object> docDef = def.document(docId);
            if (docDef == null) {
                continue;
            }
            String file = Maps.str(d.get("file"));
            byte[] data = Files.readAllBytes(goldenDir().resolve(file));
            Map<String, Object> x = extractCached(data, file.substring(file.lastIndexOf('.') + 1), docDef);
            docs.add(new DocInput(docId, null, file, x));
            Map<String, Object> truth = Maps.map(d.get("truth"));
            Map<String, Object> fields = Maps.map(x.get("fields"));
            Map<String, Object> tables = Maps.map(x.get("tables"));
            for (Map.Entry<String, Object> t : truth.entrySet()) {
                fTotal++;
                Object got;
                double conf;
                if (tables.containsKey(t.getKey())) {
                    List<Map<String, Object>> rows = Maps.listOfMaps(tables.get(t.getKey()));
                    got = rows;
                    conf = rows.stream().mapToDouble(r -> Maps.dbl(r.get("_confidence"), 0)).min().orElse(0);
                } else {
                    Map<String, Object> f = Maps.map(fields.get(t.getKey()));
                    got = f.get("value");
                    conf = Maps.dbl(f.get("confidence"), 0);
                }
                if (sameValue(t.getValue(), got)) {
                    fCorrect++;
                } else {
                    boolean unread = got == null || (got instanceof List<?> l && l.isEmpty()) || conf < ocrMin;
                    if (unread) {
                        fUnread++;
                    } else {
                        fWrong++;
                    }
                    misses.add(Maps.of("doc", docId, "key", t.getKey(), "label", def.fieldLabel(docId, t.getKey()), "expected", t.getValue(),
                            "got", got instanceof List<?> ? simplify(got) : got, "confidence", conf, "kind", unread ? "UNREAD" : "WRONG"));
                }
            }
        }
        Map<String, Object> emp = Maps.map(c.get("employee"));
        Map<String, Object> result = engine.review(new ReviewInput(pv.getId(), def, Maps.map(c.get("form")),
                Maps.of("name", emp.get("name"), "hire_date", emp.get("hire_date"), "employee_no", emp.get("employee_no")),
                LocalDate.parse(Maps.str(c.get("applied_on"))), docs, null));

        Map<String, Object> expected = Maps.map(c.get("expected"));
        String want = Maps.str(expected.get("verdict"));
        String verdict = Maps.str(result.get("verdict"));
        String outcome = "REVIEW".equals(verdict) ? "REVIEW" : verdict.equals(want) ? "CORRECT" : "WRONG";
        List<String> codes = Maps.listOfMaps(result.get("issues")).stream().filter(i -> "REJECT".equals(i.get("severity")))
                .map(i -> Maps.str(i.get("code"))).toList();
        Boolean reasonMatched = "REJECT".equals(want) && "REJECT".equals(verdict) ? codes.containsAll(Maps.strings(expected.get("reasons"))) : null;

        Map<String, Object> jtruth = Maps.map(expected.get("judgments"));
        List<Map<String, Object>> js = new ArrayList<>();
        for (Map<String, Object> j : Maps.listOfMaps(result.get("judgments"))) {
            String id = Maps.str(j.get("id"));
            Object truth = jtruth.get(id);
            Map<String, Object> spec = def.judgments().stream().filter(s -> id.equals(s.get("id"))).findFirst().orElse(Map.of());
            Boolean correct = null;
            if (truth != null && "ok".equals(j.get("status"))) {
                boolean truthPass = switch (Maps.str(spec, "type", "")) {
                    case "choice" -> Maps.strings(spec.get("pass_options")).contains(String.valueOf(truth));
                    case "score" -> "pass".equals(String.valueOf(truth));
                    default -> "true".equals(String.valueOf(truth)) == Maps.bool(spec.get("pass_when"), true);
                };
                correct = Maps.bool(j.get("satisfied"), false) == truthPass;
            }
            js.add(Maps.of("id", id, "label", j.get("label"), "status", j.get("status"), "answer", j.get("answer"), "answerLabel", j.get("answerLabel"),
                    "truth", truth, "confidence", j.get("confidence"), "satisfied", j.get("satisfied"), "decision", j.get("decision"), "correct", correct));
        }
        Map<String, Object> model = Maps.map(result.get("decisionModel"));
        return Maps.of("id", c.get("id"), "supportType", c.get("support_type"), "scenario", c.get("scenario"), "title", c.get("title"),
                "format", c.get("format"), "quality", c.get("quality"), "expected", want, "expectedReasons", expected.get("reasons"),
                "verdict", verdict, "outcome", outcome, "confidence", result.get("confidence"), "issueCodes", codes,
                "reviewReasons", Maps.listOfMaps(result.get("issues")).stream().filter(i -> "REVIEW".equals(i.get("severity"))).map(i -> i.get("label")).toList(),
                "reasonMatched", reasonMatched, "extraction", Maps.of("total", fTotal, "correct", fCorrect, "wrong", fWrong, "unread", fUnread),
                "extractionMisses", misses, "judgments", js, "provider", model.get("provider"), "model", model.get("model"),
                "policyVersion", pv.getVersion());
    }

    private static Object simplify(Object rows) {
        return Maps.listOfMaps(rows).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> {
                if (!k.startsWith("_")) {
                    m.put(k, v);
                }
            });
            return m;
        }).toList();
    }

    private static boolean sameValue(Object truth, Object got) {
        if (got == null) {
            return false;
        }
        if (truth instanceof List<?> tl) {
            if (!(got instanceof List<?> gl) || tl.size() != gl.size()) {
                return false;
            }
            for (int i = 0; i < tl.size(); i++) {
                Object a = tl.get(i);
                Object b = gl.get(i);
                if (a instanceof Map<?, ?> am && b instanceof Map<?, ?> bm) {
                    for (Map.Entry<?, ?> e : am.entrySet()) {
                        if (!sameValue(e.getValue(), bm.get(e.getKey()))) {
                            return false;
                        }
                    }
                } else if (!sameValue(a, b)) {
                    return false;
                }
            }
            return true;
        }
        if (truth instanceof Number tn) {
            Long g = ValueComparator.toLong(got);
            return g != null && g == tn.longValue();
        }
        return ValueComparator.squash(truth).equals(ValueComparator.squash(got));
    }

    private static Map<String, Object> counter() {
        return Maps.of("total", 0, "correct", 0, "wrong", 0, "review", 0);
    }

    private static void inc(Map<String, Object> m, String key, int by) {
        m.put(key, ((Number) m.getOrDefault(key, 0)).intValue() + by);
    }

    private static Double rate(int a, int b) {
        return b == 0 ? null : Math.round(a * 10000.0 / b) / 10000.0;
    }

    private Map<String, Object> summarize(List<Map<String, Object>> results) {
        int total = results.size();
        int correct = 0;
        int wrong = 0;
        int review = 0;
        int falseApprove = 0;
        int falseReject = 0;
        int reasonTotal = 0;
        int reasonOk = 0;
        Map<String, Object> confusion = Maps.of("APPROVE", Maps.of("APPROVE", 0, "REJECT", 0, "REVIEW", 0), "REJECT", Maps.of("APPROVE", 0, "REJECT", 0, "REVIEW", 0));
        Map<String, Object> bySupport = new LinkedHashMap<>();
        Map<String, Object> byScenario = new LinkedHashMap<>();
        Map<String, Object> byQuality = new LinkedHashMap<>();
        Map<String, Object> exByQuality = new LinkedHashMap<>();
        Map<String, Object> ex = Maps.of("total", 0, "correct", 0, "wrong", 0, "unread", 0);
        Map<String, Object> byJudgment = new LinkedHashMap<>();
        List<Map<String, Object>> samples = new ArrayList<>();
        int jTotal = 0;
        int jCorrect = 0;
        String provider = null;
        String model = null;
        for (Map<String, Object> r : results) {
            String outcome = Maps.str(r.get("outcome"));
            String want = Maps.str(r.get("expected"));
            String verdict = Maps.str(r.get("verdict"));
            switch (outcome) {
                case "CORRECT" -> correct++;
                case "WRONG" -> {
                    wrong++;
                    if ("APPROVE".equals(verdict)) {
                        falseApprove++;
                    } else {
                        falseReject++;
                    }
                }
                default -> review++;
            }
            inc(Maps.map(confusion.get(want)), verdict, 1);
            for (Map<String, Object> group : List.of(
                    Maps.map(bySupport.computeIfAbsent(Maps.str(r.get("supportType")), k -> counter())),
                    Maps.map(byScenario.computeIfAbsent(Maps.str(r.get("scenario")), k -> counter())),
                    Maps.map(byQuality.computeIfAbsent(Maps.str(r.get("quality")), k -> counter())))) {
                inc(group, "total", 1);
                inc(group, outcome.toLowerCase(), 1);
            }
            if (r.get("reasonMatched") != null) {
                reasonTotal++;
                reasonOk += Boolean.TRUE.equals(r.get("reasonMatched")) ? 1 : 0;
            }
            Map<String, Object> e = Maps.map(r.get("extraction"));
            Map<String, Object> eq = Maps.map(exByQuality.computeIfAbsent(Maps.str(r.get("quality")), k -> Maps.of("total", 0, "correct", 0, "wrong", 0, "unread", 0)));
            for (String k : List.of("total", "correct", "wrong", "unread")) {
                inc(ex, k, ((Number) e.get(k)).intValue());
                inc(eq, k, ((Number) e.get(k)).intValue());
            }
            for (Map<String, Object> j : Maps.listOfMaps(r.get("judgments"))) {
                if (j.get("correct") == null) {
                    continue;
                }
                boolean ok = Boolean.TRUE.equals(j.get("correct"));
                jTotal++;
                jCorrect += ok ? 1 : 0;
                Map<String, Object> g = Maps.map(byJudgment.computeIfAbsent(Maps.str(j.get("id")), k -> Maps.of("label", j.get("label"), "total", 0, "correct", 0)));
                inc(g, "total", 1);
                inc(g, "correct", ok ? 1 : 0);
                samples.add(Maps.of("confidence", Maps.dbl(j.get("confidence"), 0), "correct", ok, "group", j.get("id")));
            }
            if (r.get("provider") != null) {
                provider = Maps.str(r.get("provider"));
                model = Maps.str(r.get("model"));
            }
        }
        for (Object g : exByQuality.values()) {
            Map<String, Object> m = Maps.map(g);
            m.put("accuracy", rate(((Number) m.get("correct")).intValue(), ((Number) m.get("total")).intValue()));
        }
        ex.put("accuracy", rate(((Number) ex.get("correct")).intValue(), ((Number) ex.get("total")).intValue()));
        ex.put("byQuality", exByQuality);
        Map<String, Object> calibration = null;
        if (!samples.isEmpty()) {
            try {
                calibration = ai.calibrate(samples, TARGET_ACCURACY);
            } catch (RuntimeException e) {
                log.warn("확신도 보정 계산 실패: {}", e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("expected", Maps.of("APPROVE", results.stream().filter(r -> "APPROVE".equals(r.get("expected"))).count(),
                "REJECT", results.stream().filter(r -> "REJECT".equals(r.get("expected"))).count()));
        out.put("verdict", Maps.of("correct", correct, "wrong", wrong, "review", review, "accuracyOverall", rate(correct, total),
                "accuracyAuto", rate(correct, correct + wrong), "reviewRate", rate(review, total), "autoRate", rate(correct + wrong, total),
                "falseApprove", falseApprove, "falseReject", falseReject));
        out.put("confusion", confusion);
        out.put("reasonMatch", Maps.of("total", reasonTotal, "matched", reasonOk, "rate", rate(reasonOk, reasonTotal)));
        out.put("extraction", ex);
        out.put("judgments", Maps.of("total", jTotal, "correct", jCorrect, "accuracy", rate(jCorrect, jTotal), "byJudgment", byJudgment));
        out.put("calibration", calibration);
        out.put("targetAccuracy", TARGET_ACCURACY);
        out.put("bySupportType", bySupport);
        out.put("byScenario", byScenario);
        out.put("byQuality", byQuality);
        out.put("decisionModel", Maps.of("provider", provider, "model", model));
        return out;
    }

    /** 시행 중 버전과 개정 버전의 판정이 달라지는 건 목록. 관리자가 이 목록을 확인한 뒤 개정 버전을 시행한다. */
    private Map<String, Object> regressionDiff(PolicyVersion draft, List<Map<String, Object>> base, List<Map<String, Object>> after) {
        Map<String, Map<String, Object>> before = new LinkedHashMap<>();
        for (Map<String, Object> b : base) {
            before.put(Maps.str(b.get("id")), b);
        }
        List<Map<String, Object>> changes = new ArrayList<>();
        for (Map<String, Object> a : after) {
            Map<String, Object> b = before.get(Maps.str(a.get("id")));
            if (b != null && !String.valueOf(b.get("verdict")).equals(String.valueOf(a.get("verdict")))) {
                changes.add(Maps.of("id", a.get("id"), "title", a.get("title"), "expected", a.get("expected"),
                        "before", b.get("verdict"), "after", a.get("verdict"), "beforeOutcome", b.get("outcome"), "afterOutcome", a.get("outcome"),
                        "afterIssues", a.get("issueCodes")));
            }
        }
        long beforeCorrect = base.stream().filter(r -> "CORRECT".equals(r.get("outcome"))).count();
        long afterCorrect = after.stream().filter(r -> "CORRECT".equals(r.get("outcome"))).count();
        return Maps.of("at", Instant.now().toString(), "version", draft.getVersion(), "baseVersion", base.isEmpty() ? null : base.get(0).get("policyVersion"),
                "total", after.size(), "changed", changes.size(), "changes", changes,
                "beforeCorrect", base.isEmpty() ? null : beforeCorrect, "afterCorrect", afterCorrect,
                "afterWrong", after.stream().filter(r -> "WRONG".equals(r.get("outcome"))).count(),
                "afterReview", after.stream().filter(r -> "REVIEW".equals(r.get("outcome"))).count());
    }

    public Map<String, Object> view(EvaluationRun r, boolean withCases) {
        Map<String, Object> m = Maps.of("id", r.getId(), "kind", r.getKind(), "status", r.getStatus(), "policyVersionId", r.getPolicyVersionId(),
                "progressDone", r.getProgressDone(), "progressTotal", r.getProgressTotal(), "provider", r.getProvider(), "model", r.getModel(),
                "triggeredBy", r.getTriggeredBy(), "error", r.getError(), "startedAt", r.getStartedAt().toString(),
                "finishedAt", r.getFinishedAt() == null ? null : r.getFinishedAt().toString(), "summary", r.getSummary());
        if (withCases) {
            m.put("cases", r.getCases());
        }
        return m;
    }

    public EvaluationRun get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("검증 실행 기록을 찾을 수 없습니다."));
    }

    public Map<String, Object> latest() {
        EvaluationRun done = repo.findFirstByKindAndStatusOrderByStartedAtDesc("EVALUATION", "DONE").orElse(null);
        EvaluationRun running = repo.findByStatus("RUNNING").stream().findFirst().orElse(null);
        return Maps.of("latest", done == null ? null : view(done, true), "running", running == null ? null : view(running, false));
    }

    public List<Map<String, Object>> recent() {
        return repo.findTop20ByOrderByStartedAtDesc().stream().map(r -> view(r, false)).toList();
    }
}
