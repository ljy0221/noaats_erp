package com.noaats.ifms.support;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ProtocolType;
import com.noaats.ifms.domain.interface_.domain.ScheduleType;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.HashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Day 7 통합 테스트 전용 시드 헬퍼.
 *
 * <p>{@link com.noaats.ifms.domain.execution.domain.ExecutionLog}는 정적 팩토리만 노출하므로
 * 임의 상태(FAILED/SUCCESS/특정 actor 등) 시드는 native INSERT로 처리한다.
 * 일반 Service 경로는 RetryService/Trigger 통해.
 *
 * <p>모든 시드 메서드는 {@code REQUIRES_NEW}로 자체 TX를 생성·커밋하여,
 * 호출 측 테스트가 별도 TX 없이도(또는 NOT_SUPPORTED여도) 결과를 즉시 관찰할 수 있게 한다.
 */
@Component
public class ExecutionLogTestSeeder {

    private final EntityManager em;
    private final InterfaceConfigRepository ifcRepo;

    public ExecutionLogTestSeeder(EntityManager em, InterfaceConfigRepository ifcRepo) {
        this.em = em;
        this.ifcRepo = ifcRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InterfaceConfig seedInterface(String name, InterfaceStatus status, int maxRetry) {
        return ifcRepo.save(InterfaceConfig.builder()
                .name(name)
                .description("test " + name)
                .protocol(ProtocolType.REST)
                .endpoint("https://example.test/" + name)
                .httpMethod("POST")
                .configJson(new HashMap<>())
                .scheduleType(ScheduleType.MANUAL)
                .cronExpression(null)
                .timeoutSeconds(30)
                .maxRetryCount(maxRetry)
                .status(status)
                .build());
    }

    /**
     * 임의 상태/parent/actor의 execution_log를 native INSERT로 시드.
     * @return 생성된 log id
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long seedLog(Long interfaceConfigId,
                        ExecutionStatus status,
                        String actor,
                        Long parentId,
                        Long rootId,
                        int retryCount,
                        int maxRetrySnapshot) {
        boolean terminal = status != ExecutionStatus.RUNNING;
        LocalDateTime startedAt = LocalDateTime.now().minusMinutes(5);
        Object finishedAt = terminal ? LocalDateTime.now().minusMinutes(4) : null;
        Object durationMs = terminal ? 60_000L : null;
        String triggeredBy = parentId != null ? "RETRY" : "MANUAL";

        em.createNativeQuery("""
                INSERT INTO execution_log
                  (interface_config_id, parent_log_id, root_log_id, triggered_by, actor_id,
                   status, started_at, finished_at, duration_ms,
                   payload_format, payload_truncated, retry_count, max_retry_snapshot, created_at)
                VALUES
                  (:cfg, :parent, :root, :trig, :actor,
                   :status, :started, :finished, :dur,
                   'JSON', false, :retry, :maxSnap, NOW())
                """)
                .setParameter("cfg", interfaceConfigId)
                .setParameter("parent", parentId)
                .setParameter("root", rootId)
                .setParameter("trig", triggeredBy)
                .setParameter("actor", actor)
                .setParameter("status", status.name())
                .setParameter("started", startedAt)
                .setParameter("finished", finishedAt)
                .setParameter("dur", durationMs)
                .setParameter("retry", retryCount)
                .setParameter("maxSnap", maxRetrySnapshot)
                .executeUpdate();

        Number id = (Number) em.createNativeQuery(
                        "SELECT MAX(id) FROM execution_log WHERE interface_config_id = :cfg")
                .setParameter("cfg", interfaceConfigId).getSingleResult();
        em.flush();
        em.clear();
        return id.longValue();
    }

    /**
     * RUNNING 상태 + 임의 startedAt 시드 (OrphanRunningWatchdog 테스트용).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long seedRunningStuck(Long ifcId, String actor, LocalDateTime startedAt) {
        em.createNativeQuery("""
                INSERT INTO execution_log
                  (interface_config_id, triggered_by, actor_id, status, started_at,
                   payload_format, payload_truncated, retry_count, max_retry_snapshot, created_at)
                VALUES
                  (:cfg, 'MANUAL', :actor, 'RUNNING', :started,
                   'JSON', false, 0, 3, NOW())
                """)
                .setParameter("cfg", ifcId)
                .setParameter("actor", actor)
                .setParameter("started", startedAt)
                .executeUpdate();
        Number id = (Number) em.createNativeQuery(
                        "SELECT MAX(id) FROM execution_log WHERE interface_config_id = :cfg")
                .setParameter("cfg", ifcId).getSingleResult();
        em.flush();
        em.clear();
        return id.longValue();
    }

    /**
     * interface_config의 timeout_seconds를 강제로 변경 (Watchdog 테스트의 임계값 단축용).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setTimeoutSeconds(Long ifcId, int timeoutSeconds) {
        em.createNativeQuery("UPDATE interface_config SET timeout_seconds = :t WHERE id = :id")
                .setParameter("t", timeoutSeconds)
                .setParameter("id", ifcId)
                .executeUpdate();
        em.flush();
        em.clear();
    }
}
