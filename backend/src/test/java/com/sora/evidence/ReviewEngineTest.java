package com.sora.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sora.evidence.ai.AiClient;
import com.sora.evidence.common.ApiException;
import com.sora.evidence.common.Maps;
import com.sora.evidence.directory.UniversityDirectory;
import com.sora.evidence.policy.PolicyDefinition;
import com.sora.evidence.review.ExpressionEvaluator;
import com.sora.evidence.review.GuidanceWriter;
import com.sora.evidence.review.ReviewEngine;
import com.sora.evidence.review.ReviewInput;
import com.sora.evidence.review.ReviewInput.DocInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 검수 엔진의 판정 규칙: 확실히 읽은 값으로만 자동 반려하고, 애매하면 담당자 확인으로 보낸다. */
class ReviewEngineTest {
    private AiClient ai;
    private ReviewEngine engine;
    private PolicyDefinition tuition;
    private PolicyDefinition medical;

    @BeforeEach
    void setUp() throws Exception {
        ai = mock(AiClient.class);
        UniversityDirectory dir = mock(UniversityDirectory.class);
        when(dir.lookup(anyString())).thenReturn(Maps.of("found", true, "summary", "조회됨"));
        engine = new ReviewEngine(new ExpressionEvaluator(), new GuidanceWriter(), dir, ai);
        tuition = PolicyDefinition.parse(Files.readString(Path.of("../seed/policies/tuition/policy.yaml"), StandardCharsets.UTF_8));
        medical = PolicyDefinition.parse(Files.readString(Path.of("../seed/policies/medical/policy.yaml"), StandardCharsets.UTF_8));
    }

    // ---- 테스트 데이터 도우미 ----
    private static Map<String, Object> field(Object value, double confidence) {
        return Maps.of("value", value, "confidence", confidence, "found", value != null, "bbox", List.of(1, 2, 3, 4), "page", 0);
    }

    private static Map<String, Object> cert(long paid, double quality) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("student_name", field("김민준", 0.99));
        f.put("school_name", field("한빛대학교", 0.99));
        f.put("course", field("학사과정", 0.99));
        f.put("semester", field("2026-2", 0.98));
        f.put("tuition_total", field(paid, 0.99));
        f.put("scholarship_amount", field(0, 0.9));
        f.put("paid_amount", field(paid, 0.99));
        f.put("payment_date", field("2026-08-21", 0.99));
        f.put("issue_date", field("2026-09-01", 0.99));
        f.put("issuer", field("한빛대학교 총장", 0.99));
        f.put("seal", field("있음", 0.99));
        List<Map<String, Object>> items = new ArrayList<>(List.of(Maps.of("name", "수업료", "amount", paid, "_confidence", 0.99)));
        return Maps.of("doc_check", Maps.of("matched", true, "title", "등록금 납입증명서"), "quality", quality, "engine", "test",
                "fields", f, "tables", Maps.of("payment_items", items));
    }

    private static Map<String, Object> family(String principal, String child) {
        return Maps.of("doc_check", Maps.of("matched", true, "title", "가족관계증명서"), "quality", 0.99, "engine", "test",
                "fields", Maps.of("principal_name", field(principal, 0.99), "child_names", field(List.of(child), 0.99)), "tables", Maps.of());
    }

    private static Map<String, Object> judged(String id, boolean satisfied, double confidence) {
        return Maps.of("id", id, "status", "ok", "satisfied", satisfied, "confidence", confidence, "answer", "x", "answer_label", "x",
                "inputs", Maps.of("교육기관명", "한빛대학교", "납입항목", "수업료, 기숙사비"), "clauses", List.of());
    }

    private void judgeReturns(Map<String, Object>... results) {
        when(ai.judge(any())).thenReturn(Maps.of("provider", "test", "model", "test-1", "results", List.of(results)));
    }

    private void allPass() {
        judgeReturns(judged("J-INSTITUTION", true, 0.99), judged("J-ITEMS", true, 0.98), judged("J-FORMAL", true, 0.97), judged("J-ELIGIBLE-COURSE", true, 0.99));
    }

    private Map<String, Object> run(Map<String, Object> form, DocInput... docs) {
        Map<String, Object> emp = Maps.of("name", "김민준", "hire_date", "2019-03-04", "employee_no", "E1");
        return engine.review(new ReviewInput(1L, tuition, form, emp, LocalDate.parse("2026-09-10"), List.of(docs), key -> false));
    }

    private static Map<String, Object> form(String relationship, String target, long amount) {
        return Maps.of("target_name", target, "relationship", relationship, "school_name", "한빛대학교", "semester", "2026-2", "requested_amount", amount);
    }

    private static List<String> codes(Map<String, Object> r, String severity) {
        return Maps.listOfMaps(r.get("issues")).stream().filter(i -> severity.equals(i.get("severity"))).map(i -> String.valueOf(i.get("code"))).toList();
    }

    private static DocInput certDoc(Map<String, Object> x) {
        return new DocInput("tuition_payment_certificate", 10L, "cert.png", x);
    }

    // ---- 시나리오 ----
    @Test
    void approvesWhenEveryConditionIsMetWithHighConfidence() {
        allPass();
        Map<String, Object> r = run(form("본인", "김민준", 3_200_000), certDoc(cert(3_200_000, 0.98)));
        assertThat(r.get("verdict")).isEqualTo("APPROVE");
        assertThat((Double) r.get("confidence")).isGreaterThanOrEqualTo(0.85);
        assertThat(Maps.listOfMaps(r.get("comparisons"))).allMatch(c -> "MATCH".equals(c.get("status")));
        assertThat(String.valueOf(r.get("guidance"))).contains("승인");
    }

    @Test
    void rejectsAmountMismatchWithGuidanceAndClause() {
        allPass();
        Map<String, Object> r = run(form("본인", "김민준", 4_200_000), certDoc(cert(3_700_000, 0.98)));
        assertThat(r.get("verdict")).isEqualTo("REJECT");
        assertThat(codes(r, "REJECT")).containsExactly("C-AMOUNT");
        assertThat(String.valueOf(r.get("guidance"))).contains("4,200,000원").contains("3,700,000원").contains("제5조");
    }

    @Test
    void missingRequiredDocumentIsListedForSupplement() {
        allPass();
        Map<String, Object> r = run(form("자녀", "김하은", 3_200_000), certDoc(withName(cert(3_200_000, 0.98), "김하은")));
        assertThat(r.get("verdict")).isEqualTo("REJECT");
        assertThat(codes(r, "REJECT")).contains("MISSING:family_relation_certificate");
        assertThat(Maps.listOfMaps(r.get("missingDocuments"))).extracting(m -> m.get("id")).containsExactly("family_relation_certificate");
        assertThat(String.valueOf(r.get("guidance"))).contains("추가로 제출할 서류").contains("가족관계증명서");
    }

    @Test
    void childWithFamilyCertificateIsApproved() {
        allPass();
        Map<String, Object> r = run(form("자녀", "김하은", 3_200_000), certDoc(withName(cert(3_200_000, 0.98), "김하은")),
                new DocInput("family_relation_certificate", 11L, "fam.png", family("김민준", "김하은")));
        assertThat(r.get("verdict")).isEqualTo("APPROVE");
    }

    @Test
    void wrongDocumentTypeCountsAsNotSubmitted() {
        Map<String, Object> bill = cert(3_200_000, 0.98);
        bill.put("doc_check", Maps.of("matched", false, "title", "등록금 고지서", "reason", "'고지서'은(는) 인정되지 않는 서류입니다."));
        Map<String, Object> r = run(form("본인", "김민준", 3_200_000), certDoc(bill));
        assertThat(r.get("verdict")).isEqualTo("REJECT");
        assertThat(codes(r, "REJECT")).containsExactly("MISSING:tuition_payment_certificate");
        // 서류가 없어 판정할 수 없는 조건은 '담당자 확인'으로 세지 않는다
        assertThat(codes(r, "REVIEW")).isEmpty();
    }

    @Test
    void lowOcrConfidenceGoesToHumanReviewInsteadOfRejecting() {
        allPass();
        Map<String, Object> x = cert(3_200_000, 0.95);
        Maps.map(x.get("fields")).put("paid_amount", field(3_100_000, 0.55));   // 틀리게 읽었지만 신뢰도가 낮다
        Map<String, Object> r = run(form("본인", "김민준", 3_200_000), certDoc(x));
        assertThat(r.get("verdict")).isEqualTo("REVIEW");
        assertThat(codes(r, "REVIEW")).contains("C-AMOUNT");
        assertThat(codes(r, "REJECT")).isEmpty();
    }

    @Test
    void lowModelConfidenceGoesToHumanReview() {
        judgeReturns(judged("J-INSTITUTION", true, 0.99), judged("J-ITEMS", false, 0.62), judged("J-FORMAL", true, 0.97), judged("J-ELIGIBLE-COURSE", true, 0.99));
        Map<String, Object> r = run(form("본인", "김민준", 3_200_000), certDoc(cert(3_200_000, 0.98)));
        assertThat(r.get("verdict")).isEqualTo("REVIEW");
        assertThat(codes(r, "REVIEW")).containsExactly("J-ITEMS");
        assertThat((Double) r.get("confidence")).isEqualTo(0.62);
    }

    @Test
    void confidentModelFailureRejectsWithFilledGuidance() {
        judgeReturns(judged("J-INSTITUTION", true, 0.99), judged("J-ITEMS", false, 0.99), judged("J-FORMAL", true, 0.97), judged("J-ELIGIBLE-COURSE", true, 0.99));
        Map<String, Object> r = run(form("본인", "김민준", 3_200_000), certDoc(cert(3_200_000, 0.98)));
        assertThat(r.get("verdict")).isEqualTo("REJECT");
        assertThat(String.valueOf(r.get("guidance"))).contains("수업료, 기숙사비").contains("제4조");
    }

    @Test
    void absenceOnLowQualityScanIsNotTrusted() {
        // 저화질 문서에서 '직인 없음'으로 읽혀 모델이 미충족이라 해도 자동 반려하지 않는다
        judgeReturns(judged("J-INSTITUTION", true, 0.99), judged("J-ITEMS", true, 0.99), judged("J-FORMAL", false, 0.98), judged("J-ELIGIBLE-COURSE", true, 0.99));
        Map<String, Object> x = cert(3_200_000, 0.84);
        Maps.map(x.get("fields")).put("seal", field("없음", 0.84));
        Map<String, Object> r = run(form("본인", "김민준", 3_200_000), certDoc(x));
        assertThat(r.get("verdict")).isEqualTo("REVIEW");
        assertThat(codes(r, "REVIEW")).contains("J-FORMAL");
    }

    @Test
    void decisionModelOutageNeverAutoApproves() {
        when(ai.judge(any())).thenThrow(ApiException.unavailable("AI 서버에 연결할 수 없습니다."));
        Map<String, Object> r = run(form("본인", "김민준", 3_200_000), certDoc(cert(3_200_000, 0.98)));
        assertThat(r.get("verdict")).isEqualTo("REVIEW");
        assertThat(codes(r, "REVIEW")).hasSize(4);
    }

    @Test
    void staticRulesLimitDeadlineTenureDuplicate() {
        allPass();
        assertThat(codes(run(form("본인", "김민준", 5_800_000), certDoc(cert(5_800_000, 0.98))), "REJECT")).containsExactly("S-LIMIT");

        Map<String, Object> emp = Maps.of("name", "김민준", "hire_date", "2026-03-02", "employee_no", "E1");
        Map<String, Object> late = engine.review(new ReviewInput(1L, tuition, form("본인", "김민준", 3_200_000), emp, LocalDate.parse("2026-11-20"),
                List.of(certDoc(cert(3_200_000, 0.98))), key -> true));
        assertThat(codes(late, "REJECT")).containsExactlyInAnyOrder("S-TENURE", "S-DEADLINE", "S-DUPLICATE");
        assertThat(late.get("dedupKey")).isEqualTo("tuition|김민준|2026-2");
    }

    @Test
    void medicalRunsThroughTheSameEngineWithOnlyConfigChanged() {
        when(ai.judge(any())).thenReturn(Maps.of("provider", "test", "model", "test-1",
                "results", List.of(judged("J-MED-INSTITUTION", true, 0.99), judged("J-MED-PURPOSE", true, 0.97))));
        Map<String, Object> f = Maps.of("patient_name", field("김민준", 0.99), "hospital_name", field("한빛내과의원", 0.99),
                "institution_kind", field("의원", 0.99), "department", field("내과", 0.99), "treatment", field("급성 편도염 진료", 0.99),
                "patient_total", field(47300, 0.99), "paid_amount", field(47300, 0.99), "receipt_date", field("2026-09-03", 0.99));
        Map<String, Object> x = Maps.of("doc_check", Maps.of("matched", true, "title", "진료비 계산서·영수증"), "quality", 0.98, "fields", f, "tables", Maps.of());
        Map<String, Object> form = Maps.of("target_name", "김민준", "relationship", "본인", "hospital_name", "한빛내과의원",
                "receipt_date", "2026-09-03", "requested_amount", 47300);
        Map<String, Object> emp = Maps.of("name", "김민준", "hire_date", "2019-03-04");
        Map<String, Object> r = engine.review(new ReviewInput(2L, medical, form, emp, LocalDate.parse("2026-09-20"),
                List.of(new DocInput("medical_receipt", 20L, "r.png", x)), null));
        assertThat(r.get("verdict")).isEqualTo("APPROVE");
        assertThat(Maps.listOfMaps(r.get("judgments"))).hasSize(2);
        assertThat(Maps.listOfMaps(r.get("comparisons"))).hasSize(4);
    }

    @Test
    void precheckShowsComparisonsWithoutCallingTheModel() {
        Map<String, Object> emp = Maps.of("name", "김민준", "hire_date", "2019-03-04");
        Map<String, Object> r = engine.precheck(new ReviewInput(1L, tuition, form("본인", "김민준", 4_200_000), emp, LocalDate.parse("2026-09-10"),
                List.of(certDoc(cert(3_700_000, 0.98))), null));
        assertThat(r.get("verdict")).isNull();
        assertThat(Maps.listOfMaps(r.get("comparisons")).stream().filter(c -> "C-AMOUNT".equals(c.get("id"))).findFirst().orElseThrow().get("status"))
                .isEqualTo("MISMATCH");
        assertThat(Maps.listOfMaps(r.get("judgments"))).isEmpty();
    }

    private static Map<String, Object> withName(Map<String, Object> cert, String name) {
        Maps.map(cert.get("fields")).put("student_name", field(name, 0.99));
        return cert;
    }
}
