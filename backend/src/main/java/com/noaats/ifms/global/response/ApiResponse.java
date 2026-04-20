package com.noaats.ifms.global.response;

import com.noaats.ifms.global.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Getter;

/**
 * 공통 응답 래퍼 (api-spec.md §1.2).
 *
 * <pre>
 * { "success": true|false,
 *   "data":    T | ErrorDetail | null,
 *   "message": 실패 시 사유 | null,
 *   "timestamp": ISO-8601 offset 포함 }
 * </pre>
 *
 * 빌더 대신 {@link #success(Object)} / {@link #error(ErrorCode, String, Map)} 정적 팩토리만 사용.
 */
@Getter
public final class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final String message;
    private final OffsetDateTime timestamp;

    private ApiResponse(boolean success, T data, String message) {
        this.success = success;
        this.data = data;
        this.message = message;
        this.timestamp = OffsetDateTime.now();
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<ErrorDetail> error(ErrorCode code, String message, Map<String, Object> extra) {
        return new ApiResponse<>(false, ErrorDetail.of(code, extra),
                message != null ? message : code.getDefaultMessage());
    }

    public static ApiResponse<ErrorDetail> error(ErrorCode code) {
        return error(code, code.getDefaultMessage(), Map.of());
    }
}
