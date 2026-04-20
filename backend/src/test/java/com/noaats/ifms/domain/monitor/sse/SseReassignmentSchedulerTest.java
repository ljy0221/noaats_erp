package com.noaats.ifms.domain.monitor.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseReassignmentSchedulerTest {

    @Test
    void schedules_complete_after_grace() throws Exception {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        SseReassignmentScheduler s = new SseReassignmentScheduler(sched, Duration.ofMillis(200));
        boolean[] completed = { false };
        // SseEmitter.onCompletion 콜백은 servlet handler 바인딩 후에만 트리거되므로,
        // 테스트에서는 complete() 호출 자체를 감지하기 위해 서브클래싱으로 훅킹.
        SseEmitter em = new SseEmitter(5000L) {
            @Override
            public synchronized void complete() {
                completed[0] = true;
                super.complete();
            }
        };

        s.scheduleComplete(em);
        assertThat(completed[0]).isFalse();
        Thread.sleep(400);
        assertThat(completed[0]).isTrue();

        sched.shutdownNow();
    }

    @Test
    void double_complete_is_safe() throws Exception {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        SseReassignmentScheduler s = new SseReassignmentScheduler(sched, Duration.ofMillis(100));
        SseEmitter em = new SseEmitter(5000L);
        em.complete();
        s.scheduleComplete(em);
        Thread.sleep(250);
        // no exception thrown — scheduler should swallow

        sched.shutdownNow();
    }
}
