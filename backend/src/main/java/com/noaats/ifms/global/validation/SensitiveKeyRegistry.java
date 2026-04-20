package com.noaats.ifms.global.validation;

import java.util.Locale;
import java.util.Set;

/**
 * 민감 키 블랙리스트 단일 출처 (erd.md §3.3 · api-spec.md §7.1).
 *
 * 시크릿 8종 + PII 7종 = 총 15종. 정규화(소문자 + {@code _}/{@code -} 제거) 후 contains 매칭하여
 * 변형형(Password / pass_word / PASSWORD 등)까지 탐지.
 *
 * 참조 지점 3곳:
 * - {@link ConfigJsonValidator}   — 저장 전 `configJson` 평문 시크릿 차단 (400)
 * - `MaskingRule`                  — JSON 키 재귀 마스킹 블랙리스트
 * - `GlobalExceptionHandler`       — `rejectedValue` REDACTED 규칙
 */
public final class SensitiveKeyRegistry {

    /** 정규화된 블랙리스트. 소문자, 밑줄/하이픈 제거 후 contains 매칭 기준값. */
    public static final Set<String> FORBIDDEN_KEY_TOKENS = Set.of(
            // 시크릿 8종
            "password",
            "pwd",
            "secret",
            "apikey",
            "token",
            "privatekey",
            "authorization",
            "credential",
            // PII 7종
            "ssn",
            "rrn",
            "memberrrn",
            "cardno",
            "custcardno",
            "accountno",
            "acctno"
    );

    private SensitiveKeyRegistry() {
    }

    /**
     * 키가 블랙리스트에 매치되는지 판정. `secretRef`는 예외 키로 false 반환 —
     * `vault://` / `env://` 프리픽스 검증은 호출자 책임.
     */
    public static boolean matches(String key) {
        if (key == null || key.isEmpty()) return false;
        String normalized = normalize(key);
        if ("secretref".equals(normalized)) return false;
        for (String token : FORBIDDEN_KEY_TOKENS) {
            if (normalized.contains(token)) return true;
        }
        return false;
    }

    /**
     * {@code apikey}·{@code secretRef}·{@code pass_word} 등 변형형을 정규 형태로 변환.
     */
    public static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
