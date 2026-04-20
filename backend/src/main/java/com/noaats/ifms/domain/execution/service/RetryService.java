package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.dto.ExecutionTriggerResponse;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.monitor.sse.ExecutionEventPublisher;
import com.noaats.ifms.global.config.AdvisoryLockProperties;
import com.noaats.ifms.global.exception.ConflictException;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.global.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 재처리 트리거 서비스 — ADR-005 §5.2 / §5.3 구현.
 *
 * <h3>독립 TX (ADR-001 §6 - 6)</h3>
 * 재처리 실패가 원본 로그에 영향을 주지 않도록 본 트랜잭션은 부모 로그를 SELECT만 한다.
 * 새 RUNNING 로그는 별도 INSERT로 spawn된다 (parent_log_id + root_log_id 캐스케이드).
 *
 * <h3>평가 순서 (ADR-005 §5.3)</h3>
 * <ol>
 *   <li>EXECUTION_NOT_FOUND (404) — findRetryGuardSnapshot 미존재</li>
 *   <li>advisory lock(interface_config_id) → 실패 시 DUPLICATE_RUNNING (409, ADR-005 Q4)</li>
 *   <li>advisory lock(parent_log_id)      → 실패 시 RETRY_CHAIN_CONFLICT (409, ADR-005 Q4)</li>
 *   <li>RetryGuard.verify() — 5종 코드 단일 평가</li>
 *   <li>uk_log_running 정상 경로 방어선 (DUPLICATE_RUNNING)</li>
 *   <li>uk_log_parent  정상 경로 방어선 (RETRY_CHAIN_CONFLICT)</li>
 *   <li>spawnRetry INSERT</li>
 * </ol>
 *
 * <h3>lock 획득 순서 (ADR-005 §5.3)</h3>
 * 항상 interface_config_id → parent_log_id (deadlock 방지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryService {

    private final ExecutionLogRepository    logRepository;
    private final AdvisoryLockProperties    lockProps;
    private final RetryGuard                retryGuard;
    private final ExecutionEventPublisher   eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecutionTriggerResponse retry(Long parentLogId,
                                          String sessionActor,
                                          String clientIp,
                                          String userAgent) {
        // 1. 단일 SELECT — 부모 + 인터페이스 + 루트 actor 한 번에 (ADR-005 §5.2)
        RetryGuardSnapshot snap = logRepository.findRetryGuardSnapshot(parentLogId)
                .map(RetryGuardSnapshot::fromRow)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EXECUTION_NOT_FOUND));

        // 2. advisory lock 1차 — 일반 실행과 동일 도메인 (interface_config_id)
        // 부모 로그를 통해 인터페이스 ID를 얻기 위해 부모 Entity를 한 번 더 fetch
        ExecutionLog parent = logRepository.findById(parentLogId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EXECUTION_NOT_FOUND));
        Long configId = parent.getInterfaceConfig().getId();

        boolean lock1 = Boolean.TRUE.equals(
                logRepository.tryAdvisoryLock(lockProps.namespace(), configId));
        if (!lock1) {
            log.debug("재처리 lock1(config) 실패: configId={} → DUPLICATE_RUNNING", configId);
            throw new ConflictException(ErrorCode.DUPLICATE_RUNNING);
        }

        // 3. advisory lock 2차 — 재처리 도메인 (parent_log_id)
        boolean lock2 = Boolean.TRUE.equals(
                logRepository.tryAdvisoryLock(lockProps.retryNamespace(), parentLogId));
        if (!lock2) {
            log.debug("재처리 lock2(parent) 실패: parentLogId={} → RETRY_CHAIN_CONFLICT", parentLogId);
            throw new ConflictException(ErrorCode.RETRY_CHAIN_CONFLICT);
        }

        // 4. RetryGuard 5종 평가 (FORBIDDEN_ACTOR / INACTIVE / NOT_LEAF / TRUNCATED / LIMIT)
        retryGuard.verify(snap, sessionActor);

        // 5. uk_log_running 정상 경로 방어선
        logRepository.findRunningByConfig(configId).ifPresent(running -> {
            log.debug("재처리 진입 시 RUNNING 발견: configId={} runningLogId={}",
                    configId, running.getId());
            throw new ConflictException(ErrorCode.DUPLICATE_RUNNING);
        });

        // 6. uk_log_parent 정상 경로 방어선
        logRepository.findChildOf(parentLogId).ifPresent(child -> {
            log.debug("재처리 진입 시 자식 발견: parentLogId={} childLogId={}",
                    parentLogId, child.getId());
            throw new ConflictException(ErrorCode.RETRY_CHAIN_CONFLICT);
        });

        // 7. spawn — root_log_id 캐스케이드 + max_retry_snapshot 캐스케이드
        ExecutionLog retryEntry = ExecutionLog.spawnRetry(parent, sessionActor, clientIp, userAgent);
        ExecutionLog saved = logRepository.save(retryEntry);

        // afterCommit RUNNING SSE emit
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishStarted(saved);
            }
        });

        log.debug("재처리 spawn logId={} parentId={} retryCount={} maxRetrySnapshot={}",
                saved.getId(), parentLogId, saved.getRetryCount(), saved.getMaxRetrySnapshot());
        return ExecutionTriggerResponse.from(saved);
    }
}
