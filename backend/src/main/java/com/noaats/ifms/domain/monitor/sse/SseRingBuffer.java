package com.noaats.ifms.domain.monitor.sse;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * SSE 링버퍼 — 1,000건/5분 중 먼저 도달한 조건이 선입 이벤트를 축출 (api-spec.md §6.1).
 *
 * <h3>스레드 안전</h3>
 * 내부 {@link ArrayDeque}는 단일 synchronized 블록으로 보호한다. Mock 실행 풀이 동시 8건을
 * 초과하지 않는 프로토타입 규모에서 단일 lock으로 충분하다.
 *
 * <h3>시퀀스 발급</h3>
 * {@link AtomicLong}이 단조 증가 ID를 보증. JVM 재시작 시 시퀀스는 1로 초기화되며,
 * 클라이언트는 {@code ?since=} 폴백으로 누락분을 보강해야 한다 (api-spec §3.3).
 */
@Component
public class SseRingBuffer {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Deque<SseEvent> buffer = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong(0);
    private final int maxSize;
    private final Duration ttl;

    public SseRingBuffer(SseProperties props) {
        this.maxSize = props.ringBufferSize();
        this.ttl = props.ringBufferTtl();
    }

    /** 단조 증가 시퀀스 발급 + 버퍼에 추가. 결과 이벤트 반환. */
    public synchronized SseEvent append(SseEventType type, Map<String, Object> payload) {
        evictExpired();
        SseEvent event = new SseEvent(
                sequence.incrementAndGet(),
                type,
                payload != null ? payload : Map.of(),
                OffsetDateTime.now(KST));
        buffer.addLast(event);
        while (buffer.size() > maxSize) {
            buffer.pollFirst();
        }
        return event;
    }

    /** {@code lastEventId} 이후의 이벤트 목록 (순서 보존). */
    public synchronized List<SseEvent> since(long lastEventId) {
        evictExpired();
        List<SseEvent> out = new ArrayList<>();
        for (SseEvent e : buffer) {
            if (e.id() > lastEventId) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * 링버퍼가 {@code lastEventId}를 "알고" 있는지 여부.
     * 버퍼의 최소 ID - 1 이상이면 재전송 가능 (연속 구간). 그보다 작으면 이미 축출된 것 →
     * 호출자가 RESYNC_REQUIRED 발송을 결정한다.
     */
    public synchronized boolean isKnown(long lastEventId) {
        evictExpired();
        if (buffer.isEmpty()) {
            return lastEventId == 0L;
        }
        long minId = buffer.peekFirst().id();
        return lastEventId >= minId - 1;
    }

    public synchronized int size() {
        return buffer.size();
    }

    private void evictExpired() {
        OffsetDateTime threshold = OffsetDateTime.now(KST).minus(ttl);
        while (!buffer.isEmpty() && buffer.peekFirst().timestamp().isBefore(threshold)) {
            buffer.pollFirst();
        }
    }
}
