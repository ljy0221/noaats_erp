package com.noaats.ifms.domain.monitor.sse;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SSE 브로드캐스트 진입점 (Day 4: SseEmitterService 본 구현 위임).
 *
 * <h3>호출자 무변경 원칙</h3>
 * 호출자({@code ExecutionTriggerService}·{@code ExecutionResultPersister}·{@code RetryService}·
 * {@code OrphanRunningWatchdog})의 시그니처를 유지한 채 내부적으로 {@link SseEmitterService}에
 * 브로드캐스트를 위임한다.
 *
 * <h3>호출 타이밍 (ADR-001 §6 - 7)</h3>
 * 모든 emit는 TX 커밋 후 {@code TransactionSynchronization.afterCommit}에서 호출되어야 한다.
 * 본 컴포넌트는 emit 자체만 담당하고, 호출 타이밍은 호출자 책임.
 */
@Slf4j
@Component
public class ExecutionEventPublisher {

    /** payload timestamps 타임존 — 프런트 ExecutionLogResponse와 일치(KST offset 포함). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SseEmitterService sseService;

    public ExecutionEventPublisher(SseEmitterService sseService) {
        this.sseService = sseService;
    }

    public void publishStarted(ExecutionLog entry) {
        sseService.broadcast(SseEventType.EXECUTION_STARTED, payload(entry));
    }

    public void publishSucceeded(ExecutionLog entry) {
        sseService.broadcast(SseEventType.EXECUTION_SUCCESS, payload(entry));
    }

    public void publishFailed(ExecutionLog entry) {
        sseService.broadcast(SseEventType.EXECUTION_FAILED, payload(entry));
    }

    public void publishRecovered(ExecutionLog entry) {
        sseService.broadcast(SseEventType.EXECUTION_RECOVERED, payload(entry));
    }

    /**
     * SSE payload — 프런트 {@code ExecutionLogResponse} 계약과 동일한 필드명을 사용한다.
     * 리뷰 C1(Day 6 post-review): {@code logId}·{@code interfaceId}는 프런트 dedup의
     * {@code log.id} / in-place 갱신 키와 어긋나 실시간 상태 전이가 UI에 반영되지 않던 결함 수정.
     */
    private Map<String, Object> payload(ExecutionLog entry) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", entry.getId());
        map.put("interfaceConfigId", entry.getInterfaceConfig().getId());
        map.put("interfaceName", entry.getInterfaceConfig().getName());
        map.put("status", entry.getStatus().name());
        map.put("triggeredBy", entry.getTriggeredBy().name());
        map.put("startedAt",
                entry.getStartedAt() == null ? null
                        : entry.getStartedAt().atZone(KST).toOffsetDateTime().toString());
        map.put("finishedAt",
                entry.getFinishedAt() == null ? null
                        : entry.getFinishedAt().atZone(KST).toOffsetDateTime().toString());
        map.put("durationMs", entry.getDurationMs());
        map.put("retryCount", entry.getRetryCount());
        map.put("parentLogId", entry.getParent() != null ? entry.getParent().getId() : null);
        map.put("errorCode", entry.getErrorCode() != null ? entry.getErrorCode().name() : null);
        map.put("errorMessage", entry.getErrorMessage());
        return map;
    }
}
