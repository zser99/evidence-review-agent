package com.sora.evidence.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** YAML·JSON에서 읽은 Map 구조를 다루는 작은 도우미. */
public final class Maps {
    private Maps() {
    }

    /** 키와 값을 번갈아 받아 순서를 유지하는 Map을 만든다(null 값 허용). */
    public static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listOfMaps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    public static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x != null) {
                    out.add(String.valueOf(x));
                }
            }
        } else if (o != null) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    public static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    public static double dbl(Object o, double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? fallback : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean bool(Object o, boolean fallback) {
        if (o instanceof Boolean b) {
            return b;
        }
        return o == null ? fallback : Boolean.parseBoolean(String.valueOf(o));
    }

    /** 점으로 이은 경로(ev.문서.항목)의 값. 없으면 null. */
    public static Object path(Map<String, Object> root, String path) {
        Object cur = root;
        for (String p : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m) || !m.containsKey(p)) {
                return null;
            }
            cur = m.get(p);
        }
        return cur;
    }
}
