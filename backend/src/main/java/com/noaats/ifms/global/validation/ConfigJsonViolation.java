package com.noaats.ifms.global.validation;

/**
 * {@link ConfigJsonValidator} 위반 1건 — (JSONPath, 사유).
 *
 * {@code rejectedValue}는 의도적으로 포함하지 않는다 (api-spec.md §7.1 `CONFIG_JSON_INVALID` 규약):
 * 평문 시크릿을 응답에 에코하지 않기 위함.
 */
public record ConfigJsonViolation(String path, String reason) {

    public static final String REASON_FORBIDDEN_KEY = "FORBIDDEN_KEY";
    public static final String REASON_JWT_PATTERN_DETECTED = "JWT_PATTERN_DETECTED";
    public static final String REASON_AWS_KEY_PATTERN_DETECTED = "AWS_KEY_PATTERN_DETECTED";
    public static final String REASON_SECRET_REF_INVALID_PREFIX = "SECRET_REF_INVALID_PREFIX";
    public static final String REASON_PATH_TOO_LONG = "PATH_TOO_LONG";
    public static final String REASON_DEPTH_EXCEEDED = "DEPTH_EXCEEDED";
    public static final String REASON_NODE_LIMIT_EXCEEDED = "NODE_LIMIT_EXCEEDED";
}
