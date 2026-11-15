package com.sora.evidence.review;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * 정적 규칙·조건식 평가기(SpEL).
 *
 * <p>규정 관리자가 올리는 YAML의 식을 실행하므로, 타입 참조·생성자·빈 접근이 막힌 읽기 전용 컨텍스트
 * ({@link SimpleEvaluationContext})만 쓰고, 값은 facts Map과 아래 도우미 함수로만 읽는다.
 */
@Component
public class ExpressionEvaluator {
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();
    private static final Method DAYS;
    private static final Method MONTHS;
    private static final Method NZ;
    private static final Method SAME_NAME;

    static {
        try {
            DAYS = ExpressionEvaluator.class.getMethod("days", Object.class, Object.class);
            MONTHS = ExpressionEvaluator.class.getMethod("months", Object.class, Object.class);
            NZ = ExpressionEvaluator.class.getMethod("nz", Object.class);
            SAME_NAME = ExpressionEvaluator.class.getMethod("sameName", Object.class, Object.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** 문법 오류 메시지. 문제가 없으면 null. */
    public String syntaxError(String expr) {
        try {
            parser.parseExpression(expr);
            return null;
        } catch (ParseException e) {
            return e.getSimpleMessage();
        }
    }

    /** 식을 평가해 참/거짓을 돌려준다. 값이 없거나 평가할 수 없으면 null(판단 불가). */
    public Boolean evalBoolean(String expr, Map<String, Object> facts) {
        try {
            SimpleEvaluationContext ctx = SimpleEvaluationContext.forPropertyAccessors(new FactAccessor()).withRootObject(facts).build();
            ctx.setVariable("days", DAYS);
            ctx.setVariable("months", MONTHS);
            ctx.setVariable("nz", NZ);
            ctx.setVariable("sameName", SAME_NAME);
            Object v = cache.computeIfAbsent(expr, parser::parseExpression).getValue(ctx);
            return v instanceof Boolean b ? b : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- 식에서 쓰는 도우미 함수 (#days, #months, #nz, #sameName) ----

    public static long days(Object from, Object to) {
        return ChronoUnit.DAYS.between(date(from), date(to));
    }

    public static long months(Object from, Object to) {
        return ChronoUnit.MONTHS.between(date(from), date(to));
    }

    public static long nz(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    public static boolean sameName(Object a, Object b) {
        return a != null && b != null && ValueComparator.matches("name", a, b);
    }

    private static LocalDate date(Object o) {
        if (o instanceof LocalDate d) {
            return d;
        }
        if (o == null) {
            throw new IllegalArgumentException("날짜 값이 없습니다.");
        }
        return LocalDate.parse(String.valueOf(o));
    }

    /** Map의 키를 속성처럼 읽는다(app.requested_amount). 없는 키는 null. 쓰기는 허용하지 않는다. */
    private static final class FactAccessor implements PropertyAccessor {
        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return new Class<?>[]{Map.class};
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) {
            return target instanceof Map;
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) {
            return new TypedValue(((Map<?, ?>) target).get(name));
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object newValue) throws AccessException {
            throw new AccessException("읽기 전용입니다.");
        }
    }
}
