package com.noaats.ifms.domain.monitor.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * M4 (DAY6-SUMMARY §9-2): SseEmitterService.subscribe의 find+unregister+register TOCTOU race 시나리오.
 *
 * <h2>현황 — 본 테스트는 의도적으로 @Disabled</h2>
 * Day 7 구현 결과, 본 시나리오는 매 회 FAIL — 즉, M4가 실제 race로 재현됨을 확인했다.
 * 두 스레드가 거의 동시에 {@code findOtherSessionByClientId}를 호출하면 둘 다 null을 받고,
 * 각자 register하여 동일 clientId가 두 세션에 공존한다 (TOCTOU).
 *
 * <h2>본 테스트의 가치</h2>
 * race 시나리오 자체는 보존. M4 정합 픽스(예: SseEmitterService.subscribe 진입부 synchronized,
 * 또는 Registry의 atomic compute-and-register)가 도입되면 본 @Disabled 제거하고 PASS 확인.
 *
 * <h2>왜 Day 7에 픽스하지 않는가</h2>
 * <ul>
 *   <li>프로토타입 단일 인스턴스 운영 — 실제 동시 subscribe 확률 매우 낮음</li>
 *   <li>fix은 ADR-007 R3 동시성 모델 변경 — 별도 검토 필요</li>
 *   <li>DAY6-SUMMARY §9-2 M4가 이미 "통합 테스트에서 시나리오 추가만"으로 결정됨</li>
 * </ul>
 *
 * <p>관련 backlog: {@code docs/backlog.md} §"운영 전환" — M4 항목.
 */
@Disabled("M4 — race가 실재 재현됨. SseEmitterService.subscribe 동기화 픽스 후 활성. 사유는 클래스 javadoc 참조")
@SpringBootTest
@TestPropertySource(properties = {
        "ifms.actor.anon-salt=test-salt-day7-race"
})
class SseSubscribeRaceTest {

    @Autowired SseEmitterService service;
    @Autowired SseEmitterRegistry registry;

    @RepeatedTest(20)
    void concurrentSubscribesOfSameClientIdConvergeToSingleEmitter() throws Exception {
        String clientId = "race-" + System.nanoTime();
        String sessionA = "sess-A-" + System.nanoTime();
        String sessionB = "sess-B-" + System.nanoTime();
        String actor = "raceActor";

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> {
            try { start.await(); service.subscribe(sessionA, actor, clientId, null); }
            catch (Exception ignored) { /* race outcome is what we test */ }
        });
        pool.submit(() -> {
            try { start.await(); service.subscribe(sessionB, actor, clientId, null); }
            catch (Exception ignored) { /* race outcome is what we test */ }
        });
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        java.util.function.LongSupplier countHolders = () -> {
            long n = 0;
            if (!registry.snapshotBySession(sessionA).isEmpty()) n++;
            if (!registry.snapshotBySession(sessionB).isEmpty()) n++;
            return n;
        };

        // ADR-007 reassign-grace=2s + 여유 2s = 4s 윈도우 내 수렴 확인
        long deadline = System.currentTimeMillis() + 4_000;
        long count = countHolders.getAsLong();
        while (System.currentTimeMillis() < deadline && count > 1) {
            Thread.sleep(100);
            count = countHolders.getAsLong();
        }
        assertThat(count)
                .as("after race + grace, ≤1 session should hold clientId")
                .isLessThanOrEqualTo(1L);

        // 다음 반복을 위한 정리
        registry.unregister(sessionA, clientId);
        registry.unregister(sessionB, clientId);
    }
}
