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
 *
 * <h2>불변식</h2>
 * <ul>
 *   <li>{@code success=true} 응답에서만 {@code data} 경로가 마스킹 대상이다 (DefensiveMaskingFilter §3.4).</li>
 *   <li>{@code success=false} 응답의 {@code data}는 ErrorDetail로, 마스킹은 적용되지 않는다 (구조화된 안전 필드).</li>
 *   <li>{@code message}는 사용자 노출 가능 텍스트만 담는다 (스택트레이스·SQL·내부 식별자 금지).</li>
 *   <li>{@code timestamp}는 응답 생성 시각, ISO-8601 OffsetDateTime (응답 본문 일관성).</li>
 * </ul>
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
