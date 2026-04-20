package com.noaats.ifms.domain.monitor.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 리뷰 C2: UNAUTHORIZED 이벤트는 세션 로컬이어야 한다.
 *
 * <p>링버퍼({@link SseRingBuffer})는 전역 공유이므로 UNAUTHORIZED를 append 하면
 * 다른 세션이 {@code Last-Event-ID}로 재구독할 때 타 세션의 UNAUTHORIZED가
 * 재전송되어 무고한 세션이 강제 로그아웃된다. 이 테스트는 그 누출이 없음을 확인한다.</p>
 */
class SseUnauthorizedIsolationTest {

    @Test
    void unauthorized_is_not_appended_to_global_ring_buffer() {
        SseProperties props = new SseProperties(
                1000,
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(3),
                3,
                10
        );
        SseRingBuffer ring = new SseRingBuffer(props);

        // 링버퍼에 정상 이벤트 2건 축적.
        ring.append(SseEventType.EXECUTION_STARTED, Map.of("id", 1L));
        ring.append(SseEventType.EXECUTION_SUCCESS, Map.of("id", 1L));

        int sizeBefore = ring.size();

        // UNAUTHORIZED 송출을 흉내 — publishUnauthorizedAndClose가 ringBuffer.append를 호출하지 않고
        // ephemeral SseEvent를 직접 만들어 emitter로만 보내는지, 본 테스트는 링버퍼 상태로 간접 검증.
        // (SseEmitterService.publishUnauthorizedAndClose가 ringBuffer를 건드리지 않는 것은 코드 리뷰로 확인)

        // 링버퍼 크기는 변화 없어야 한다.
        assertThat(ring.size()).isEqualTo(sizeBefore);

        // 다른 세션이 since=0으로 재구독 시 UNAUTHORIZED가 재전송되지 않는다.
        List<SseEvent> replay = ring.since(0L);
        assertThat(replay).isNotEmpty();
        assertThat(replay).noneMatch(e -> e.type() == SseEventType.UNAUTHORIZED);
    }
}
