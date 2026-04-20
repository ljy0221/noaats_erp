package com.noaats.ifms.domain.monitor.sse;

/**
 * SSE 이벤트 타입 (api-spec.md §6.1).
 *
 * <p>브라우저 {@code EventSource} 측에서는 {@code event:} 라인 값으로 분기한다.</p>
 */
public enum SseEventType {
    CONNECTED,
    EXECUTION_STARTED,
    EXECUTION_SUCCESS,
    EXECUTION_FAILED,
    EXECUTION_RECOVERED,
    HEARTBEAT,
    UNAUTHORIZED,
    RESYNC_REQUIRED
}
