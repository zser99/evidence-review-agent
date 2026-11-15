package com.sora.evidence.review;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 신청 내용과 증빙 추출값의 대조 방식과 화면 표시 형식. */
public final class ValueComparator {
    private static final Pattern SEMESTER = Pattern.compile("(20\\d{2})\\D*([12])");

    private ValueComparator() {
    }

    public static String squash(Object o) {
        return o == null ? "" : String.valueOf(o).replaceAll("\\s+", "");
    }

    private static String institution(Object o) {
        return squash(o).replaceAll("[()（）]", "").replace("학교법인", "").replace("의료법인", "");
    }

    public static Long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return null;
        }
        String digits = String.valueOf(o).replaceAll("[^0-9-]", "");
        if (digits.isEmpty() || digits.equals("-")) {
            return null;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String semester(Object o) {
        if (o == null) {
            return null;
        }
        Matcher m = SEMESTER.matcher(String.valueOf(o));
        return m.find() ? m.group(1) + "-" + m.group(2) : squash(o);
    }

    public static boolean matches(String method, Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        return switch (method) {
            case "money" -> {
                Long a = toLong(left);
                Long b = toLong(right);
                yield a != null && a.equals(b);
            }
            case "semester" -> semester(left).equals(semester(right));
            case "institution" -> institution(left).equalsIgnoreCase(institution(right));
            case "in_list" -> {
                String a = squash(left);
                yield right instanceof List<?> l ? l.stream().anyMatch(x -> squash(x).equals(a)) : squash(right).equals(a);
            }
            default -> squash(left).equalsIgnoreCase(squash(right));   // name, text, date
        };
    }

    /** 사람이 읽는 형태(금액은 천 단위 구분, 학기는 'YYYY학년도 N학기'). */
    public static String display(Object v, String type) {
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> l) {
            return String.join(", ", l.stream().map(String::valueOf).toList());
        }
        if ("money".equals(type)) {
            Long n = toLong(v);
            return n == null ? String.valueOf(v) : NumberFormat.getIntegerInstance(Locale.KOREA).format(n);
        }
        if ("semester".equals(type)) {
            Matcher m = SEMESTER.matcher(String.valueOf(v));
            return m.find() ? m.group(1) + "학년도 " + m.group(2) + "학기" : String.valueOf(v);
        }
        return String.valueOf(v);
    }
}
