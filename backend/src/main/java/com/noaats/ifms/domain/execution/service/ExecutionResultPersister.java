package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.domain.ExecutionResult;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.monitor.sse.ExecutionEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * TX2 영속화 전용 빈 — ADR-001 §6 - 1 self-invocation 차단을 위해
 * {@link AsyncExecutionRunner}와 분리된다.
 *
 * <h3>왜 분리인가</h3>
 * Spring AOP는 같은 빈 내부 메서드 호출(self-invocation)에 프록시를 적용하지 않는다.
 * AsyncExecutionRunner.runAsync() → this.persistResult() 형태로 두면
 * {@code @Transactional}이 발동하지 않아 TransactionSynchronization 등록이 실패한다
 * (실제 운영 중 발견된 IllegalStateException: "Transaction synchronization is not active").
 * 별도 빈으로 분리하여 항상 프록시를 거치게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionResultPersister {

    private final ExecutionLogRepository    logRepository;
    private final ExecutionEventPublisher   eventPublisher;

    /**
     * 짧은 TX2 — UPDATE만. ExecutionLog.complete()로 상태 전이 + afterCommit SSE.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(Long logId, ExecutionResult masked) {
        ExecutionLog entry = logRepository.findById(logId)
                .orElseThrow(() -> new IllegalStateException(
                        "TX2: ExecutionLog 부재 (TX1 롤백/삭제?) logId=" + logId));
        entry.complete(masked);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (entry.isFailed()) {
                    eventPublisher.publishFailed(entry);
                } else {
                    eventPublisher.publishSucceeded(entry);
                }
            }
        });
    }
}
