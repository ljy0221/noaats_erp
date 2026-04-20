package com.noaats.ifms.domain.monitor.sse;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * SSE 단일 이벤트 레코드 (api-spec.md §6.1 이벤트 페이로드).
 *
 * <p>{@code id}는 링버퍼 키로 사용되는 단조 증가 시퀀스. 브라우저 재연결 시 {@code Last-Event-ID}
 * 헤더로 자동 전송되어 서버가 누락 이벤트를 복구한다.</p>
 *
 * @param id 단조 증가 시퀀스 (링버퍼 키)
 * @param type 이벤트 타입
 * @param payload 이벤트 본문 (마스킹 완료본)
 * @param timestamp emit 시각 (Asia/Seoul offset)
 */
public record SseEvent(
        long id,
        SseEventType type,
        Map<String, Object> payload,
        OffsetDateTime timestamp) {
}
