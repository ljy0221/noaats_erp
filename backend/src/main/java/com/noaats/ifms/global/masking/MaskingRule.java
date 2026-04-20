package com.noaats.ifms.global.masking;

import com.noaats.ifms.global.validation.SensitiveKeyRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 응답 민감정보 마스킹 유틸 (erd.md §3.2 · api-spec.md §3.4 1차 방어).
 *
 * 3단계 중 **이번 구현은 2단계** (값 정규식 + JSON 키 재귀).
 * XML StAX(3단계)는 Day 3 ExecutionLog XML payload 도입 시 추가.
 *
 * 호출 지점:
 * - Service `create()`/`update()` 저장 직전 (1차 MaskingRule)
 * - {@code DefensiveMaskingFilter} 응답 직전 (2차 재적용 — 우회 데이터 방어)
 *
 * 가드: {@link #MAX_DEPTH} 재귀, {@link #MAX_NODE_COUNT} 총 노드 (악성 중첩 JSON DoS 차단).
 */
@Component
public class MaskingRule {

    public static final int MAX_DEPTH = 10;
    public static final int MAX_NODE_COUNT = 10_000;
    public static final String MASK = "***MASKED***";

    /** 값 정규식 패턴 ({@code static final}로 JIT 최적화). */
    private static final Pattern RRN =
            Pattern.compile("\\d{6}-[1-4]\\d{6}");
    private static final Pattern CARD_CANDIDATE =
            Pattern.compile("(?<!\\d)(\\d{4})-?(\\d{4})-?(\\d{4})-?(\\d{4})(?!\\d)");
    private static final Pattern ACCOUNT_NO =
            Pattern.compile("(?<!\\d)\\d{3}-\\d{2,6}-\\d{2,8}(?!\\d)");
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)01[016789]-?\\d{3,4}-?\\d{4}(?!\\d)");
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern JWT_LEADING =
            Pattern.compile("eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-.]+");

    /**
     * 재귀 마스킹. Map/List/String 타입만 처리하며 그 외는 원본 반환.
     */
    public Object mask(Object value) {
        return mask(value, 0, new NodeCounter());
    }

    @SuppressWarnings("unchecked")
    private Object mask(Object value, int depth, NodeCounter counter) {
        if (value == null) return null;
        if (depth > MAX_DEPTH || counter.incrementAndCheck()) return value;

        if (value instanceof Map<?, ?> map) {
            return maskMap((Map<String, Object>) map, depth, counter);
        }
        if (value instanceof List<?> list) {
            return maskList(list, depth, counter);
        }
        if (value instanceof String s) {
            return maskString(s);
        }
        return value;
    }

    private Map<String, Object> maskMap(Map<String, Object> source, int depth, NodeCounter counter) {
        Map<String, Object> out = new HashMap<>(source.size());
        for (Map.Entry<String, Object> e : source.entrySet()) {
            if (SensitiveKeyRegistry.matches(e.getKey())) {
                out.put(e.getKey(), MASK);
            } else {
                out.put(e.getKey(), mask(e.getValue(), depth + 1, counter));
            }
        }
        return out;
    }

    private List<Object> maskList(List<?> source, int depth, NodeCounter counter) {
        List<Object> out = new ArrayList<>(source.size());
        for (Object item : source) {
            out.add(mask(item, depth + 1, counter));
        }
        return out;
    }

    /**
     * 문자열 패턴 매칭. 신용카드는 Luhn 검증 통과 시에만 마스킹(false positive 방지).
     *
     * public 노출 사유: GlobalExceptionHandler의 rejectedValue 값 마스킹, DefensiveMaskingFilter의 DTO 필드 마스킹에서 재사용.
     */
    public String maskString(String input) {
        if (input == null || input.isEmpty()) return input;

        String result = input;
        result = RRN.matcher(result).replaceAll(MASK);
        result = PHONE.matcher(result).replaceAll(MASK);
        result = ACCOUNT_NO.matcher(result).replaceAll(MASK);
        result = EMAIL.matcher(result).replaceAll(MASK);
        result = JWT_LEADING.matcher(result).replaceAll(MASK);

        // 신용카드 Luhn 검증 후 치환
        Matcher cardMatcher = CARD_CANDIDATE.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (cardMatcher.find()) {
            String digits = cardMatcher.group(1) + cardMatcher.group(2)
                    + cardMatcher.group(3) + cardMatcher.group(4);
            String replacement = luhnValid(digits) ? MASK : cardMatcher.group();
            cardMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        cardMatcher.appendTail(sb);
        return sb.toString();
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (alt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    private static final class NodeCounter {
        int count;
        boolean incrementAndCheck() {
            count++;
            return count > MAX_NODE_COUNT;
        }
    }
}
