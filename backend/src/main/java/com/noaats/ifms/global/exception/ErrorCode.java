package com.noaats.ifms.global.exception;

import lombok.Getter;

/**
 * 전역 에러 코드 enum (api-spec.md §1.3 정본 17종).
 *
 * 각 상수는 HTTP 상태 코드 1개에 고정 매핑된다.
 * {@link BusinessException}(400·403), {@link ConflictException}(409),
 * {@link NotFoundException}(404) 3개 서브클래스가 HTTP status를 보증하며,
 * {@link GlobalExceptionHandler}는 {@link ApiException} 단일 핸들러로
 * 17종을 모두 커버한다.
 */
@Getter
public enum ErrorCode {
    VALIDATION_FAILED(400, "요청 검증에 실패했습니다"),
    CONFIG_JSON_INVALID(400, "configJson에 평문 시크릿 또는 금지 키가 포함되어 있습니다"),
    INTERFACE_INACTIVE(400, "비활성 인터페이스는 실행할 수 없습니다"),
    QUERY_PARAM_CONFLICT(400, "상호 배제 쿼리 파라미터가 동시에 지정되었습니다"),

    UNAUTHENTICATED(401, "로그인이 필요합니다"),

    FORBIDDEN(403, "권한이 없습니다"),
    RETRY_FORBIDDEN_ACTOR(403, "타 사용자의 실행 로그는 재처리할 수 없습니다"),

    INTERFACE_NOT_FOUND(404, "인터페이스를 찾을 수 없습니다"),
    EXECUTION_NOT_FOUND(404, "실행 로그를 찾을 수 없습니다"),

    DUPLICATE_NAME(409, "동일한 이름의 인터페이스가 이미 존재합니다"),
    DUPLICATE_RUNNING(409, "이미 실행 중인 동일 인터페이스가 있습니다"),
    OPTIMISTIC_LOCK_CONFLICT(409, "다른 사용자가 수정했습니다. 최신 버전을 확인하세요"),
    RETRY_CHAIN_CONFLICT(409, "재처리 체인 분기는 허용되지 않습니다"),
    RETRY_LIMIT_EXCEEDED(409, "재처리 최대 횟수를 초과했습니다"),
    RETRY_NOT_LEAF(409, "체인 최신 리프 로그만 재처리할 수 있습니다"),
    RETRY_TRUNCATED_BLOCKED(409, "payload가 잘린 로그는 재처리할 수 없습니다"),

    PAYLOAD_TOO_LARGE(413, "요청 본문 크기가 초과되었습니다"),

    TOO_MANY_CONNECTIONS(429, "연결 상한을 초과했습니다"),

    INTERNAL_ERROR(500, "서버 내부 오류가 발생했습니다"),
    NOT_IMPLEMENTED(501, "해당 기능은 아직 구현되지 않았습니다");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
