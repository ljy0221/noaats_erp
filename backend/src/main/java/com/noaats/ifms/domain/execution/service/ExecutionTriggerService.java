package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.domain.PayloadFormat;
import com.noaats.ifms.domain.execution.domain.TriggerType;
import com.noaats.ifms.domain.execution.dto.ExecutionTriggerResponse;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
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
 * 실행 트리거 서비스 — ADR-001 TX1 담당.
 *
 * <h3>책임 (ADR-001 §6 - 1: 클래스 분리)</h3>
 * <ol>
 *   <li>인터페이스 검증 (존재·ACTIVE)</li>
 *   <li>advisory lock(interface_config_id) 획득 → 실패 시 DUPLICATE_RUNNING (ADR-004 §C)</li>
 *   <li>중복 RUNNING 존재 확인 (advisory lock의 정상 경로 방어선)</li>
 *   <li>RUNNING 로그 INSERT (TX1)</li>
 *   <li>TX1 커밋 후 RUNNING SSE emit + AsyncExecutionRunner 트리거</li>
 * </ol>
 *
 * <h3>설계 제약</h3>
 * <ul>
 *   <li>본 클래스는 {@link AsyncExecutionRunner}를 직접 호출하지 않는다 — Controller가 두 빈을 직접 호출</li>
 *   <li>TX1 내부에서는 Mock 호출 금지 (커넥션 점유 시간 최소화, ADR-001 §3 근거 2)</li>
 *   <li>SSE emit은 {@code afterCommit}에서만 (ADR-001 §6 - 7)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionTriggerService {

    private final InterfaceConfigRepository configRepository;
    private final ExecutionLogRepository    logRepository;
    private final AdvisoryLockProperties    lockProps;
    private final ExecutionEventPublisher   eventPublisher;

    /**
     * 수동/스케줄러 실행 트리거 (재처리는 RetryService 사용).
     * @param interfaceId 대상 인터페이스 ID
     * @param triggeredBy MANUAL 또는 SCHEDULER
     * @param actorId     실행 주체 (api-spec §2.3)
     * @param clientIp    호출자 IP (스케줄러는 null)
     * @param userAgent   호출자 UA (스케줄러는 null)
     * @return RUNNING 로그 정보 (logId 포함)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecutionTriggerResponse trigger(Long interfaceId,
                                            TriggerType triggeredBy,
                                            String actorId,
                                            String clientIp,
                                            String userAgent) {
        if (triggeredBy == TriggerType.RETRY) {
            throw new IllegalArgumentException("RETRY는 RetryService를 사용하세요");
        }

        InterfaceConfig config = configRepository.findById(interfaceId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.INTERFACE_NOT_FOUND));

        if (!config.isActive()) {
            throw new ConflictException(ErrorCode.INTERFACE_INACTIVE);
        }

        // ADR-004 §C - (a): advisory lock 시도 (TX 종료까지 자동 보유)
        boolean lockAcquired = Boolean.TRUE.equals(
                logRepository.tryAdvisoryLock(lockProps.namespace(), interfaceId));
        if (!lockAcquired) {
            log.debug("advisory lock 획득 실패: interfaceId={} → DUPLICATE_RUNNING", interfaceId);
            throw new ConflictException(ErrorCode.DUPLICATE_RUNNING);
        }

        // ADR-004 §C - (b): 정상 경로 방어선 (uk_log_running 확인)
        logRepository.findRunningByConfig(interfaceId).ifPresent(running -> {
            log.debug("이미 RUNNING 존재: interfaceId={} runningLogId={}", interfaceId, running.getId());
            throw new ConflictException(ErrorCode.DUPLICATE_RUNNING);
        });

        PayloadFormat fmt = PayloadFormatResolver.defaultFor(config.getProtocol());
        ExecutionLog entry = ExecutionLog.start(config, triggeredBy, actorId, clientIp, userAgent, fmt);
        ExecutionLog saved = logRepository.save(entry);

        // ADR-001 §6 - 7: TX1 커밋 후 RUNNING SSE emit (롤백 시 유령 이벤트 방지)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishStarted(saved);
            }
        });

        log.debug("TX1 INSERT logId={} configId={} triggeredBy={}",
                saved.getId(), interfaceId, triggeredBy);
        return ExecutionTriggerResponse.from(saved);
    }
}
