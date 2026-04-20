package com.noaats.ifms.global.exception;

import java.util.Map;

/**
 * 요청 빈도 상한 초과(429) 예외. DELTA_RATE_LIMITED, TOO_MANY_CONNECTIONS.
 *
 * 생성자에서 ErrorCode HTTP status == 429 강제.
 */
public final class RateLimitException extends ApiException {

    public RateLimitException(ErrorCode errorCode) {
        super(errorCode);
        assertStatus(errorCode);
    }

    public RateLimitException(ErrorCode errorCode, Map<String, Object> extra) {
        super(errorCode, extra);
        assertStatus(errorCode);
    }

    public RateLimitException(ErrorCode errorCode, String message, Map<String, Object> extra) {
        super(errorCode, message, extra);
        assertStatus(errorCode);
    }

    private static void assertStatus(ErrorCode code) {
        if (code.getHttpStatus() != 429) {
            throw new IllegalArgumentException(
                    "RateLimitException requires HTTP 429, got " + code.getHttpStatus() + " for " + code);
        }
    }
}
