package com.noaats.ifms.domain.monitor.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * M4 (DAY6-SUMMARY §9-2): SseEmitterService.subscribe의 find+unregister+register TOCTOU race 시나리오.
 *
 * <p>동일 clientId·다른 sessionId의 두 subscribe가 거의 동시에 진입할 때,
 * ADR-007 R3의 "재할당 + grace complete" 흐름이 race에서도 결국 ≤1개의 active emitter로 수렴해야 한다.
 *
 * <p>flaky 가능성을 인지하여 @RepeatedTest(20). 1~2회 fail은 race 실재 신호.
 * 5회 이상 fail이면 SseEmitterService.subscribe에 synchronized 도입 검토(별도 follow-up).
 */
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
