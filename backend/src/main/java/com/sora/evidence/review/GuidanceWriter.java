package com.sora.evidence.review;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sora.evidence.common.Maps;
import org.springframework.stereotype.Component;

/**
 * 구성원에게 보여 줄 보완 안내 문장을 만든다.
 *
 * <p>Jev는 글을 만들지 않으므로 안내는 규정 YAML의 fail_guidance 템플릿으로 만든다(결정적·항상 사용 가능).
 * HR 담당자는 이 초안을 그대로 쓰거나 LLM으로 다듬을 수 있다.
 */
@Component
public class GuidanceWriter {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)\\}");

    public String fill(String template, Map<String, ?> values) {
        if (template == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "확인 안 됨" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public String write(String verdict, String supportName, List<Map<String, Object>> issues, List<Map<String, Object>> missingDocs) {
        StringBuilder sb = new StringBuilder();
        List<Map<String, Object>> rejects = issues.stream().filter(i -> "REJECT".equals(i.get("severity")) && !"MISSING_DOCUMENT".equals(i.get("kind"))).toList();
        List<Map<String, Object>> reviews = issues.stream().filter(i -> "REVIEW".equals(i.get("severity"))).toList();
        switch (verdict) {
            case "APPROVE" -> sb.append("제출하신 증빙이 ").append(supportName).append(" 규정의 모든 조건을 충족하여 승인되었습니다.");
            case "REJECT" -> {
                sb.append("신청하신 ").append(supportName).append(" 건은 아래 사유로 반려되었습니다. 내용을 보완해 다시 제출해 주세요.\n");
                if (!rejects.isEmpty()) {
                    sb.append("\n■ 맞지 않는 내용\n");
                    int n = 1;
                    for (Map<String, Object> i : rejects) {
                        sb.append(n++).append(". ").append(i.get("message"));
                        if (i.get("clause") != null) {
                            sb.append(" (규정 ").append(i.get("clause")).append(")");
                        }
                        sb.append("\n");
                    }
                }
                if (!missingDocs.isEmpty()) {
                    sb.append("\n■ 추가로 제출할 서류\n");
                    for (Map<String, Object> d : missingDocs) {
                        sb.append("- ").append(d.get("name"));
                        if (d.get("reason") != null) {
                            sb.append(" — ").append(d.get("reason"));
                        }
                        if (d.get("description") != null) {
                            sb.append("\n  ").append(d.get("description"));
                        }
                        sb.append("\n");
                    }
                }
                if (!reviews.isEmpty()) {
                    sb.append("\n※ 이 밖에 자동으로 확정하지 못한 항목 ").append(reviews.size()).append("건은 보완 제출 후 다시 검수합니다.\n");
                }
                sb.append("\n보완 방법: 같은 신청 건에서 신청 내용을 수정하거나 증빙을 교체한 뒤 '다시 제출'을 눌러 주세요.");
            }
            default -> {
                sb.append("자동 검수에서 확정하지 못한 항목이 있어 HR 담당자가 직접 확인합니다. 결과가 나오면 알려 드리겠습니다.\n\n■ 담당자 확인 항목\n");
                int n = 1;
                for (Map<String, Object> i : reviews) {
                    sb.append(n++).append(". ").append(i.get("message")).append("\n");
                }
                sb.append("\n증빙이 흐리거나 일부가 잘린 경우, 선명한 파일로 교체해 다시 제출하시면 더 빨리 처리됩니다.");
            }
        }
        return sb.toString().trim();
    }

    public static Map<String, Object> issue(String code, String kind, String severity, String label, String message, Object clause) {
        return Maps.of("code", code, "kind", kind, "severity", severity, "label", label, "message", message, "clause", clause);
    }
}
