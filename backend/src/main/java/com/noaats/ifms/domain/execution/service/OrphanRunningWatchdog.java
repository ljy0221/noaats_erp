package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.monitor.sse.ExecutionEventPublisher;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고아 RUNNING 회수 (erd.md §8.3, ADR-001 §6 - 5).
 *
 * <h3>동작</h3>
 * <ul>
 *   <li>{@link #recoverOnStartup}: ApplicationReadyEvent 시점 1회 — 이전 인스턴스 비정상 종료
 *       (JVM crash, kill -9 등)로 남은 RUNNING을 일괄 FAILED + STARTUP_RECOVERY로 마감.</li>
 *   <li>{@link #sweep}: 5분 주기 — 운영 중 timeout_seconds + 60s를 초과한 RUNNING을 회수.
 *       Mock 실행기가 멈췄거나 AsyncExecutionRunner 예외로 TX2 미진입 시 발생.</li>
 * </ul>
 *
 * <h3>동시성</h3>
 * 본 컴포넌트는 advisory lock을 잡지 않는다. 회수는 RUNNING → FAILED 단방향이고,
 * AsyncExecutionRunner가 동일 로그에 동시 UPDATE 시도하면 후행 UPDATE는
 * {@code ExecutionLog.assertRunning()} 가드로 IllegalStateException 발생 → 안전.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanRunningWatchdog {

    private final ExecutionLogRepository    logRepository;
    private final ExecutionEventPublisher   eventPublisher;

    /**
     * 시작 시 1회 전수 복구. ApplicationReadyEvent는 모든 빈 초기화 완료 후 발행.
     * 이 시점 RUNNING은 모두 이전 인스턴스 잔재로 간주.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverOnStartup() {
        int recovered = logRepository.recoverAllRunningOnStartup();
        if (recovered > 0) {
            log.warn("ApplicationReadyEvent: 이전 인스턴스 잔재 RUNNING {}건 일괄 복구", recovered);
        } else {
            log.debug("ApplicationReadyEvent: 잔재 RUNNING 없음");
        }
    }

    /**
     * 5분 주기 고아 회수. erd §8.3 공식: started_at < now() - timeout_seconds - 60s.
     * fixedDelay로 직전 실행 종료 후 5분 대기 (cron 중복 실행 방지).
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @Transactional
    public void sweep() {
        List<ExecutionLog> orphans = logRepository.findOrphanRunning(LocalDateTime.now());
        if (orphans.isEmpty()) {
            return;
        }
        log.warn("OrphanRunningWatchdog: 고아 RUNNING {}건 발견 → 회수 시작", orphans.size());
        for (ExecutionLog orphan : orphans) {
            try {
                long elapsed = Duration.between(orphan.getStartedAt(), LocalDateTime.now()).toMillis();
                orphan.markRecovered(elapsed);
                eventPublisher.publishRecovered(orphan);
            } catch (IllegalStateException race) {
                // AsyncExecutionRunner가 동시에 종료 처리한 경우 — 정상 케이스, 무시
                log.debug("회수 스킵 (race): logId={} {}", orphan.getId(), race.getMessage());
            }
        }
    }
}
