package com.noaats.ifms.global.response;

import com.noaats.ifms.global.exception.ErrorCode;
import java.util.Map;

/**
 * 에러 응답의 {@code data} 필드 구조 (api-spec.md §7).
 *
 * <pre>
 * { "errorCode": "VALIDATION_FAILED",
 *   "extra":     { "fieldErrors": [...], "traceId": "...", "serverSnapshot": {...} } }
 * </pre>
 *
 * {@code extra}는 케이스별 가변 필드. Jackson Map 직렬화 기본 동작 사용.
 */
public record ErrorDetail(String errorCode, Map<String, Object> extra) {

    public static ErrorDetail of(ErrorCode code) {
        return new ErrorDetail(code.name(), Map.of());
    }

    public static ErrorDetail of(ErrorCode code, Map<String, Object> extra) {
        return new ErrorDetail(code.name(), extra != null ? extra : Map.of());
    }
}
