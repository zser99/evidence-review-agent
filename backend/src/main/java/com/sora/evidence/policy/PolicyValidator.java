package com.sora.evidence.policy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.sora.evidence.common.Maps;
import com.sora.evidence.review.ExpressionEvaluator;
import org.springframework.stereotype.Component;

/** 지원 종류 YAML의 스키마 검증. 규정 관리자가 올린 설정을 반영하기 전에 구조·참조·식 문법을 확인한다. */
@Component
public class PolicyValidator {
    private static final Set<String> FORM_TYPES = Set.of("text", "person_name", "enum", "money", "date", "semester");
    private static final Set<String> FIELD_TYPES = Set.of("text", "person_name", "money", "date", "semester", "identifier", "presence", "name_list");
    private static final Set<String> METHODS = Set.of("name", "text", "institution", "money", "semester", "date", "in_list");
    private static final Set<String> JUDGMENT_TYPES = Set.of("noul", "choice", "score");
    private static final Set<String> BUILTINS = Set.of("no_duplicate");
    private static final Set<String> ROOTS = Set.of("app", "emp", "ev", "ext", "ctx", "params", "judgments");
    private final ExpressionEvaluator expressions;

    public PolicyValidator(ExpressionEvaluator expressions) {
        this.expressions = expressions;
    }

    public List<String> validate(PolicyDefinition p, String regulationText) {
        List<String> e = new ArrayList<>();
        Map<String, Object> r = p.root();
        if (!Integer.valueOf(1).equals(r.get("schema_version"))) {
            e.add("schema_version은 1이어야 합니다.");
        }
        if (p.supportType() == null || !p.supportType().matches("[a-z][a-z0-9_]{1,38}")) {
            e.add("support_type은 영문 소문자로 시작하는 식별자여야 합니다(예: tuition).");
        }
        if (blank(p.name())) {
            e.add("name(지원 종류 이름)이 없습니다.");
        }
        if (blank(p.version())) {
            e.add("version이 없습니다.");
        }
        try {
            LocalDate.parse(String.valueOf(p.effectiveDate()));
        } catch (Exception ex) {
            e.add("effective_date는 YYYY-MM-DD 형식이어야 합니다.");
        }
        for (String k : List.of("auto_threshold", "ocr_min_confidence", "ocr_min_doc_quality")) {
            Object v = p.decision().get(k);
            if (v != null && (!(v instanceof Number n) || n.doubleValue() < 0.5 || n.doubleValue() > 1.0)) {
                e.add("decision." + k + "는 0.5~1.0 사이의 수여야 합니다.");
            }
        }

        Set<String> formKeys = new HashSet<>();
        for (Map<String, Object> f : p.applicationForm()) {
            String key = Maps.str(f.get("key"));
            if (blank(key) || blank(Maps.str(f.get("label")))) {
                e.add("application_form 항목에는 key와 label이 필요합니다.");
                continue;
            }
            if (!formKeys.add(key)) {
                e.add("application_form의 key가 중복됩니다: " + key);
            }
            String type = Maps.str(f, "type", "text");
            if (!FORM_TYPES.contains(type)) {
                e.add("application_form." + key + "의 type이 올바르지 않습니다: " + type);
            }
            if ("enum".equals(type) && Maps.strings(f.get("options")).isEmpty()) {
                e.add("application_form." + key + "(enum)에는 options가 필요합니다.");
            }
        }
        if (formKeys.isEmpty()) {
            e.add("application_form이 비어 있습니다.");
        }

        Set<String> evPaths = new HashSet<>();
        Set<String> docIds = new HashSet<>();
        if (p.documents().isEmpty()) {
            e.add("documents(필요한 서류)가 비어 있습니다.");
        }
        for (Map<String, Object> d : p.documents()) {
            String id = Maps.str(d.get("id"));
            if (blank(id) || blank(Maps.str(d.get("name")))) {
                e.add("documents 항목에는 id와 name이 필요합니다.");
                continue;
            }
            if (!docIds.add(id)) {
                e.add("documents의 id가 중복됩니다: " + id);
            }
            if (d.get("required_when") != null) {
                String err = expressions.syntaxError(String.valueOf(d.get("required_when")));
                if (err != null) {
                    e.add("documents." + id + ".required_when 식 오류: " + err);
                }
            }
            Set<String> tableCols = new HashSet<>();
            for (Map<String, Object> t : Maps.listOfMaps(d.get("tables"))) {
                String tk = Maps.str(t.get("key"));
                if (blank(tk) || Maps.listOfMaps(t.get("columns")).isEmpty()) {
                    e.add("documents." + id + ".tables 항목에는 key와 columns가 필요합니다.");
                    continue;
                }
                evPaths.add("ev." + id + "." + tk);
                for (Map<String, Object> c : Maps.listOfMaps(t.get("columns"))) {
                    if (blank(Maps.str(c.get("key"))) || Maps.strings(c.get("header")).isEmpty()) {
                        e.add("documents." + id + ".tables." + tk + "의 열에는 key와 header가 필요합니다.");
                    }
                    tableCols.add(tk + "." + c.get("key"));
                    evPaths.add("ev." + id + "." + tk + "." + c.get("key"));
                }
            }
            for (Map<String, Object> f : Maps.listOfMaps(d.get("fields"))) {
                String key = Maps.str(f.get("key"));
                if (blank(key)) {
                    e.add("documents." + id + ".fields 항목에 key가 없습니다.");
                    continue;
                }
                evPaths.add("ev." + id + "." + key);
                String type = Maps.str(f, "type", "text");
                if (!FIELD_TYPES.contains(type)) {
                    e.add("documents." + id + "." + key + "의 type이 올바르지 않습니다: " + type);
                }
                boolean hasLabels = !Maps.strings(f.get("labels")).isEmpty();
                boolean hasPatterns = !Maps.strings(f.get("patterns")).isEmpty();
                if (f.get("from_table") != null) {
                    Map<String, Object> ft = Maps.map(f.get("from_table"));
                    if (!tableCols.contains(ft.get("table") + "." + ft.get("column"))) {
                        e.add("documents." + id + "." + key + ".from_table이 정의되지 않은 표·열을 가리킵니다.");
                    }
                } else if (!hasLabels && !hasPatterns) {
                    e.add("documents." + id + "." + key + "에는 labels, patterns, from_table 중 하나가 필요합니다.");
                }
                for (String pat : Maps.strings(f.get("patterns"))) {
                    try {
                        Pattern.compile(pat);
                    } catch (PatternSyntaxException ex) {
                        e.add("documents." + id + "." + key + "의 정규식 오류: " + ex.getDescription());
                    }
                }
            }
            evPaths.add("ev." + id + "._title");
        }

        Set<String> ids = new HashSet<>();
        for (Map<String, Object> c : p.comparisons()) {
            String id = Maps.str(c.get("id"));
            if (blank(id) || !ids.add(id)) {
                e.add("comparisons의 id가 없거나 중복됩니다: " + id);
            }
            if (!METHODS.contains(Maps.str(c, "method", ""))) {
                e.add("comparisons." + id + "의 method가 올바르지 않습니다.");
            }
            String left = Maps.str(c, "left", "");
            String right = Maps.str(c, "right", "");
            if (!(left.startsWith("app.") && formKeys.contains(left.substring(4))) && !left.startsWith("emp.")) {
                e.add("comparisons." + id + ".left는 신청서 항목(app.*) 또는 구성원 정보(emp.*)여야 합니다: " + left);
            }
            if (!evPaths.contains(right)) {
                e.add("comparisons." + id + ".right가 정의되지 않은 증빙 항목입니다: " + right);
            }
            if (c.get("when") != null && expressions.syntaxError(String.valueOf(c.get("when"))) != null) {
                e.add("comparisons." + id + ".when 식 오류");
            }
        }
        for (Map<String, Object> s : p.staticRules()) {
            String id = Maps.str(s.get("id"));
            if (blank(id) || !ids.add(id)) {
                e.add("static_rules의 id가 없거나 중복됩니다: " + id);
            }
            if (s.get("builtin") != null) {
                if (!BUILTINS.contains(String.valueOf(s.get("builtin")))) {
                    e.add("static_rules." + id + ".builtin을 알 수 없습니다: " + s.get("builtin"));
                }
            } else if (blank(Maps.str(s.get("expr")))) {
                e.add("static_rules." + id + "에는 expr 또는 builtin이 필요합니다.");
            } else {
                String err = expressions.syntaxError(String.valueOf(s.get("expr")));
                if (err != null) {
                    e.add("static_rules." + id + ".expr 식 오류: " + err);
                }
            }
            for (String path : Maps.strings(s.get("requires"))) {
                checkRoot(e, "static_rules." + id + ".requires", path);
            }
        }
        Set<String> jids = new HashSet<>();
        for (Map<String, Object> j : p.judgments()) {
            String id = Maps.str(j.get("id"));
            if (blank(id) || !ids.add(id)) {
                e.add("judgments의 id가 없거나 중복됩니다: " + id);
                continue;
            }
            jids.add(id);
            String type = Maps.str(j, "type", "");
            if (!JUDGMENT_TYPES.contains(type)) {
                e.add("judgments." + id + ".type은 noul, choice, score 중 하나여야 합니다.");
            }
            if (blank(Maps.str(j.get("instructions")))) {
                e.add("judgments." + id + "에 instructions(Jev에 물을 질문)가 없습니다.");
            }
            Object crit = j.get("criteria");
            if ("choice".equals(type)) {
                Map<String, Object> m = Maps.map(crit);
                if (m.size() < 2 || m.size() > 255) {
                    e.add("judgments." + id + "(choice)의 criteria에는 보기가 2개 이상 필요합니다.");
                }
                List<String> pass = Maps.strings(j.get("pass_options"));
                if (pass.isEmpty() || !m.keySet().containsAll(pass)) {
                    e.add("judgments." + id + ".pass_options는 criteria의 보기 중에서 골라야 합니다.");
                }
            } else if ("score".equals(type)) {
                int n = crit instanceof List<?> l ? l.size() : 0;
                if (n < 2 || n > 10) {
                    e.add("judgments." + id + "(score)의 criteria는 2~10개의 수준 목록이어야 합니다.");
                }
                Object min = j.get("pass_min_level");
                if (!(min instanceof Number mn) || mn.intValue() < 1 || mn.intValue() >= Math.max(n, 2)) {
                    e.add("judgments." + id + ".pass_min_level은 1 이상, 수준 수 미만이어야 합니다.");
                }
            }
            if (Maps.map(j.get("inputs")).isEmpty()) {
                e.add("judgments." + id + "에 inputs(판단에 쓸 값)가 없습니다.");
            }
            for (Object path : Maps.map(j.get("inputs")).values()) {
                checkRoot(e, "judgments." + id + ".inputs", String.valueOf(path));
            }
            for (String req : Maps.strings(j.get("required_inputs"))) {
                if (!Maps.map(j.get("inputs")).containsKey(req)) {
                    e.add("judgments." + id + ".required_inputs에 inputs에 없는 이름이 있습니다: " + req);
                }
            }
            for (String ref : Maps.strings(j.get("clause_refs"))) {
                if (regulationText != null && !regulationText.replaceAll("\\s", "").contains(ref.replaceAll("\\s", ""))) {
                    e.add("judgments." + id + ".clause_refs의 조항이 규정집에 없습니다: " + ref);
                }
            }
        }
        for (Map<String, Object> j : p.judgments()) {
            for (String dep : Maps.strings(j.get("depends_on"))) {
                if (!jids.contains(dep) || dep.equals(j.get("id"))) {
                    e.add("judgments." + j.get("id") + ".depends_on이 올바르지 않습니다: " + dep);
                }
            }
        }
        if (regulationText == null || !Pattern.compile("제\\s*\\d+\\s*조\\s*[(（]").matcher(regulationText).find()) {
            e.add("규정집에서 '제N조(제목)' 형식의 조항을 찾지 못했습니다.");
        }
        return e;
    }

    private static void checkRoot(List<String> e, String where, String path) {
        String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        if (!ROOTS.contains(root)) {
            e.add(where + "의 경로는 app·emp·ev·ext·ctx·params·judgments 중 하나로 시작해야 합니다: " + path);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
