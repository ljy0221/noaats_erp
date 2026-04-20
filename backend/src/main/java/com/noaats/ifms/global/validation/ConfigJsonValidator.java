package com.noaats.ifms.global.validation;

import com.noaats.ifms.global.exception.BusinessException;
import com.noaats.ifms.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code configJson} 평문 시크릿 차단 검증기 (erd.md §3.3 · api-spec.md §3.3 · §7.1).
 *
 * 호출 지점: {@code InterfaceConfigService#create} / {@code #update} (EntityListener 미사용,
 * 도메인 코어 v0.2 결정). Repository 직접 save() 우회는 ArchUnit 테스트로 별도 방어.
 *
 * 위반 시 {@link BusinessException}({@link ErrorCode#CONFIG_JSON_INVALID}) 던지며
 * {@code extra.violations: List<ConfigJsonViolation>} 동봉. 응답 스키마는
 * {@code rejectedValue} 필드를 포함하지 않는다(path·reason만).
 *
 * 가드: 재귀 depth {@link #MAX_DEPTH}, 경로 길이 {@link #MAX_PATH_LENGTH}, 노드 수 {@link #MAX_NODE_COUNT}.
 */
@Component
public class ConfigJsonValidator {

    public static final int MAX_DEPTH = 8;
    public static final int MAX_PATH_LENGTH = 256;
    public static final int MAX_NODE_COUNT = 10_000;

    /** AWS Access Key ID 패턴 (AKIA + 16자 영숫자). */
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("AKIA[0-9A-Z]{16}");
    /** JWT 선두 토큰 패턴 — `.` 포함 필수. */
    private static final Pattern JWT_LEADING = Pattern.compile("eyJ[A-Za-z0-9_\\-]+\\.");

    public void validate(Map<String, Object> configJson) {
        if (configJson == null || configJson.isEmpty()) return;
        List<ConfigJsonViolation> violations = new ArrayList<>();
        NodeCounter counter = new NodeCounter();
        scanMap(configJson, "$", 0, violations, counter);

        // 노드 수 상한 도달 시 사용자에게 명시적 사유 전달 (DevilsAdvocate 지적:
        // 조용한 조기 종료로 부분 violations만 받으면 "수정했더니 또 다른 위반 등장" 무한 루프 위험).
        if (counter.limitReached() && violations.stream()
                .noneMatch(v -> ConfigJsonViolation.REASON_NODE_LIMIT_EXCEEDED.equals(v.reason()))) {
            violations.add(new ConfigJsonViolation("$",
                    ConfigJsonViolation.REASON_NODE_LIMIT_EXCEEDED));
        }

        if (!violations.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.CONFIG_JSON_INVALID,
                    Map.of("violations", violations));
        }
    }

    private void scanMap(Map<String, Object> map, String path, int depth,
                         List<ConfigJsonViolation> violations, NodeCounter counter) {
        if (depth > MAX_DEPTH) {
            violations.add(new ConfigJsonViolation(path, ConfigJsonViolation.REASON_DEPTH_EXCEEDED));
            return;
        }
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (counter.incrementAndCheck()) return;
            String childPath = truncatePath(path + "." + e.getKey());

            if ("secretRef".equals(e.getKey())) {
                if (!isValidSecretRef(e.getValue())) {
                    violations.add(new ConfigJsonViolation(
                            childPath, ConfigJsonViolation.REASON_SECRET_REF_INVALID_PREFIX));
                }
                continue;
            }

            if (SensitiveKeyRegistry.matches(e.getKey())) {
                violations.add(new ConfigJsonViolation(
                        childPath, ConfigJsonViolation.REASON_FORBIDDEN_KEY));
                continue;
            }

            scanValue(e.getValue(), childPath, depth + 1, violations, counter);
        }
    }

    @SuppressWarnings("unchecked")
    private void scanValue(Object value, String path, int depth,
                           List<ConfigJsonViolation> violations, NodeCounter counter) {
        if (counter.incrementAndCheck()) return;
        if (value instanceof Map<?, ?> nested) {
            scanMap((Map<String, Object>) nested, path, depth, violations, counter);
            return;
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                scanValue(list.get(i), truncatePath(path + "[" + i + "]"),
                        depth + 1, violations, counter);
            }
            return;
        }
        if (value instanceof String s) {
            if (AWS_ACCESS_KEY.matcher(s).find()) {
                violations.add(new ConfigJsonViolation(
                        path, ConfigJsonViolation.REASON_AWS_KEY_PATTERN_DETECTED));
            }
            if (JWT_LEADING.matcher(s).find()) {
                violations.add(new ConfigJsonViolation(
                        path, ConfigJsonViolation.REASON_JWT_PATTERN_DETECTED));
            }
        }
    }

    private boolean isValidSecretRef(Object value) {
        return value instanceof String s
                && (s.startsWith("vault://") || s.startsWith("env://"));
    }

    private String truncatePath(String path) {
        return path.length() <= MAX_PATH_LENGTH ? path : path.substring(0, MAX_PATH_LENGTH);
    }

    /** 노드 순회 수 상한 가드. */
    private static final class NodeCounter {
        int count;
        boolean incrementAndCheck() {
            count++;
            return count > MAX_NODE_COUNT;
        }
        boolean limitReached() {
            return count > MAX_NODE_COUNT;
        }
    }
}
