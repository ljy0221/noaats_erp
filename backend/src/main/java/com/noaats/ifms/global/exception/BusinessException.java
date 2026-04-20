package com.noaats.ifms.global.exception;

import java.util.Map;

/**
 * 비즈니스 규칙 위반(400·403) 예외.
 *
 * 사용 예: {@code throw new BusinessException(ErrorCode.CONFIG_JSON_INVALID, ...)}
 * 허용 ErrorCode HTTP status: 400·403 (생성자에서 강제 검증).
 */
public final class BusinessException extends ApiException {

    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
        assertStatus(errorCode);
    }

    public BusinessException(ErrorCode errorCode, Map<String, Object> extra) {
        super(errorCode, extra);
        assertStatus(errorCode);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> extra) {
        super(errorCode, message, extra);
        assertStatus(errorCode);
    }

    private static void assertStatus(ErrorCode code) {
        int status = code.getHttpStatus();
        if (status != 400 && status != 403) {
            throw new IllegalArgumentException(
                    "BusinessException requires HTTP 400 or 403, got " + status + " for " + code);
        }
    }
}
