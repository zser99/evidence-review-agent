package com.sora.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.sora.evidence.common.Maps;
import com.sora.evidence.policy.PolicyDefinition;
import com.sora.evidence.policy.PolicyValidator;
import com.sora.evidence.review.ExpressionEvaluator;
import com.sora.evidence.review.ValueComparator;
import org.junit.jupiter.api.Test;

class PolicyAndExpressionTest {
    private final ExpressionEvaluator expr = new ExpressionEvaluator();
    private final PolicyValidator validator = new PolicyValidator(expr);

    private static String read(String path) throws Exception {
        return Files.readString(Path.of("../seed/policies/" + path), StandardCharsets.UTF_8);
    }

    @Test
    void seedPoliciesPassSchemaValidation() throws Exception {
        for (String type : List.of("tuition", "medical")) {
            List<String> errors = validator.validate(PolicyDefinition.parse(read(type + "/policy.yaml")), read(type + "/regulation.md"));
            assertThat(errors).as(type).isEmpty();
        }
    }

    @Test
    void validatorReportsBrokenReferencesAndExpressions() throws Exception {
        String yaml = read("tuition/policy.yaml")
                .replace("right: ev.tuition_payment_certificate.paid_amount", "right: ev.tuition_payment_certificate.no_such_field")
                .replace("expr: \"app.requested_amount <= params.semester_limit\"", "expr: \"app.requested_amount <= (\"")
                .replace("pass_options: [all_eligible]", "pass_options: [nope]")
                .replace("clause_refs: [제7조]", "clause_refs: [제99조]");
        List<String> errors = validator.validate(PolicyDefinition.parse(yaml), read("tuition/regulation.md"));
        assertThat(errors).anyMatch(e -> e.contains("C-AMOUNT")).anyMatch(e -> e.contains("S-LIMIT"))
                .anyMatch(e -> e.contains("J-ITEMS")).anyMatch(e -> e.contains("제99조"));
    }

    @Test
    void publicViewDoesNotLeakJudgmentInternals() throws Exception {
        Map<String, Object> view = PolicyDefinition.parse(read("medical/policy.yaml")).publicView();
        assertThat(view.toString()).doesNotContain("examples").doesNotContain("fail_guidance");
        assertThat(Maps.listOfMaps(view.get("documents"))).hasSize(2);
    }

    @Test
    void expressionsReadFactsAndHelpers() {
        Map<String, Object> facts = Maps.of("app", Maps.of("requested_amount", 3_000_000, "relationship", "자녀"),
                "params", Maps.of("limit", 5_000_000L), "ev", Maps.of("cert", Maps.of("payment_date", "2026-08-21", "total", 4_000_000, "scholarship", null)),
                "ctx", Maps.of("applied_on", "2026-09-10"));
        assertThat(expr.evalBoolean("app.requested_amount <= params.limit", facts)).isTrue();
        assertThat(expr.evalBoolean("app.relationship == '자녀'", facts)).isTrue();
        assertThat(expr.evalBoolean("#days(ev.cert.payment_date, ctx.applied_on) <= 60", facts)).isTrue();
        assertThat(expr.evalBoolean("ev.cert.total - #nz(ev.cert.scholarship) == 4000000", facts)).isTrue();
        assertThat(expr.evalBoolean("#sameName('김 민준', '김민준')", facts)).isTrue();
        assertThat(expr.evalBoolean("ev.missing.field > 1", facts)).isNull();            // 판단 불가
    }

    @Test
    void expressionsCannotReachOutsideTheFacts() {
        Map<String, Object> facts = Maps.of("app", Maps.of("x", 1));
        // 타입 참조·생성자·빈 접근은 읽기 전용 컨텍스트에서 모두 막힌다 → 판단 불가(null)
        assertThat(expr.evalBoolean("T(java.lang.Runtime).getRuntime().availableProcessors() > 0", facts)).isNull();
        assertThat(expr.evalBoolean("new java.io.File('/').exists()", facts)).isNull();
        assertThat(expr.evalBoolean("app.getClass().getName() != null", facts)).isNull();
        assertThat(expr.evalBoolean("@environment != null", facts)).isNull();
    }

    @Test
    void comparisonMethods() {
        assertThat(ValueComparator.matches("money", 4_200_000, "4,200,000원")).isTrue();
        assertThat(ValueComparator.matches("name", "김 민준", "김민준")).isTrue();
        assertThat(ValueComparator.matches("institution", "한빛대학교 대학원", "한빛 대학교 대학원")).isTrue();
        assertThat(ValueComparator.matches("semester", "2026-2", "2026학년도 제2학기")).isTrue();
        assertThat(ValueComparator.matches("semester", "2026-1", "2026-2")).isFalse();
        assertThat(ValueComparator.matches("in_list", "김하은", List.of("김도윤", "김하은"))).isTrue();
        assertThat(ValueComparator.matches("money", 4_200_000, null)).isFalse();
        assertThat(ValueComparator.display(4200000, "money")).isEqualTo("4,200,000");
    }
}
