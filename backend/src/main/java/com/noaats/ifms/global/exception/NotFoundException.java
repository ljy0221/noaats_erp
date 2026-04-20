package com.noaats.ifms.global.exception;

import java.util.Map;

/**
 * 리소스 부재(404) 예외. INTERFACE_NOT_FOUND, EXECUTION_NOT_FOUND.
 *
 * 생성자에서 ErrorCode HTTP status == 404 강제.
 */
public final class NotFoundException extends ApiException {

    public NotFoundException(ErrorCode errorCode) {
        super(errorCode);
        assertStatus(errorCode);
    }

    public NotFoundException(ErrorCode errorCode, Map<String, Object> extra) {
        super(errorCode, extra);
        assertStatus(errorCode);
    }

    public NotFoundException(ErrorCode errorCode, String message, Map<String, Object> extra) {
        super(errorCode, message, extra);
        assertStatus(errorCode);
    }

    private static void assertStatus(ErrorCode code) {
        if (code.getHttpStatus() != 404) {
            throw new IllegalArgumentException(
                    "NotFoundException requires HTTP 404, got " + code.getHttpStatus() + " for " + code);
        }
    }
}
