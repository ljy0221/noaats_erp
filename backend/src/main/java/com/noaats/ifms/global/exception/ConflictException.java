package com.noaats.ifms.global.exception;

import java.util.Map;

/**
 * 상태 충돌(409) 예외. DUPLICATE_*, OPTIMISTIC_LOCK_CONFLICT, RETRY_* 등.
 *
 * 생성자에서 ErrorCode HTTP status == 409 강제.
 */
public final class ConflictException extends ApiException {

    public ConflictException(ErrorCode errorCode) {
        super(errorCode);
        assertStatus(errorCode);
    }

    public ConflictException(ErrorCode errorCode, Map<String, Object> extra) {
        super(errorCode, extra);
        assertStatus(errorCode);
    }

    public ConflictException(ErrorCode errorCode, String message, Map<String, Object> extra) {
        super(errorCode, message, extra);
        assertStatus(errorCode);
    }

    private static void assertStatus(ErrorCode code) {
        if (code.getHttpStatus() != 409) {
            throw new IllegalArgumentException(
                    "ConflictException requires HTTP 409, got " + code.getHttpStatus() + " for " + code);
        }
    }
}
