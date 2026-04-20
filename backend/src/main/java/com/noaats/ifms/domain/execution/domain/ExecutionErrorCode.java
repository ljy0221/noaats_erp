package com.noaats.ifms.domain.execution.domain;

/**
 * 실행 실패 사유 코드 (erd.md §3.2 error_code 컬럼, planning.md §5.5).
 *
 * 글로벌 {@link com.noaats.ifms.global.exception.ErrorCode}와는 별개 — 이쪽은
 * 외부 호출/Mock 실행기의 실패 분류 전용. ExecutionLog.errorCode VARCHAR 컬럼에
 * @Enumerated(STRING)으로 영속화되어 대시보드 집계 키로 사용된다.
 *
 * enum 사용 이유: 오타(TMIEOUT_EXCEEDED 등) 저장 차단 + 대시보드 키 일관성.
 */
public enum ExecutionErrorCode {
    /** Mock/실 호출 timeout_seconds 초과 */
    TIMEOUT_EXCEEDED,
    /** 외부 엔드포인트 도달 불가 (DNS/연결 거부) */
    ENDPOINT_UNREACHABLE,
    /** 외부 5xx 응답 */
    REMOTE_SERVER_ERROR,
    /** 외부 4xx 응답 (인증·요청 형식 등) */
    REMOTE_CLIENT_ERROR,
    /** Mock 실행기 의도적 실패 시뮬레이션 (failure_rate) */
    MOCK_INJECTED_FAILURE,
    /** OrphanRunningWatchdog가 timeout 초과 RUNNING을 강제 종료 (erd.md §8.3) */
    STARTUP_RECOVERY,
    /** 분류 실패 — 일단 잡고 후속 분석 */
    UNKNOWN
}
