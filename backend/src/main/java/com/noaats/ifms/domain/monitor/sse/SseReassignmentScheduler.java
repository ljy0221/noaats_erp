package com.noaats.ifms.domain.monitor.sse;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** clientId 재할당 시 이전 emitter를 grace 시간 후 complete 하는 단일 스케줄러. ADR-007 R3. */
@Slf4j
@Component
public class SseReassignmentScheduler {

    private final ScheduledExecutorService scheduler;
    private final Duration grace;

    @Autowired
    public SseReassignmentScheduler(@Value("${ifms.sse.reassign-grace:PT2S}") Duration grace) {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-reassign-scheduler");
            t.setDaemon(true);
            return t;
        }), grace);
    }

    /** 테스트용. */
    SseReassignmentScheduler(ScheduledExecutorService scheduler, Duration grace) {
        this.scheduler = scheduler;
        this.grace = grace;
    }

    public void scheduleComplete(SseEmitter emitter) {
        scheduler.schedule(() -> {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("grace complete 실패(이미 완료일 수 있음): {}", e.getMessage());
            }
        }, grace.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
