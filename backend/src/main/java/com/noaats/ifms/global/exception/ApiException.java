package com.noaats.ifms.global.exception;

import java.util.Map;
import lombok.Getter;

/**
 * 애플리케이션 레벨 커스텀 예외의 루트 sealed 클래스.
 *
 * 직접 인스턴스화 대신 3개 서브클래스({@link BusinessException} 400·403,
 * {@link ConflictException} 409, {@link NotFoundException} 404) 중 HTTP 상태에
 * 맞는 타입을 던진다. 테스트에서는 타입 기반 assertion(`assertThrows(ConflictException.class)`)이
 * 가능하고, {@link GlobalExceptionHandler}는 {@code ApiException} 단일 핸들러로 17종을 모두 처리한다.
 */
@Getter
public abstract sealed class ApiException extends RuntimeException
        permits BusinessException, ConflictException, NotFoundException, RateLimitException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> extra;

    protected ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), Map.of());
    }

    protected ApiException(ErrorCode errorCode, Map<String, Object> extra) {
        this(errorCode, errorCode.getDefaultMessage(), extra);
    }

    protected ApiException(ErrorCode errorCode, String message, Map<String, Object> extra) {
        super(message);
        this.errorCode = errorCode;
        this.extra = extra != null ? extra : Map.of();
    }
}
