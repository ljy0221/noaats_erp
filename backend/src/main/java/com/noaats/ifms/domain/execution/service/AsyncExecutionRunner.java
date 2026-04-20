package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.ExecutionResult;
import com.noaats.ifms.domain.execution.domain.PayloadFormat;
import com.noaats.ifms.domain.execution.mock.MockExecutor;
import com.noaats.ifms.domain.execution.mock.MockExecutorFactory;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import com.noaats.ifms.global.config.AsyncConfig;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.global.exception.NotFoundException;
import com.noaats.ifms.global.masking.MaskingRule;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 비동기 실행 러너 — ADR-001 TX2 담당.
 *
 * <h3>핵심 제약 (ADR-001 §6)</h3>
 * <ul>
 *   <li>1: ExecutionTriggerService와 별도 빈, 또한 TX2 본체({@link ExecutionResultPersister})와도
 *       별도 빈으로 분리하여 self-invocation 차단</li>
 *   <li>2: AsyncConfig EXECUTION_POOL 사용</li>
 *   <li>3: Mock 호출(Thread.sleep 포함)은 TX2 바깥. 결과만 받아 짧은 TX2로 UPDATE</li>
 *   <li>4: 마스킹은 TX2 진입 전 완료 — 본 클래스 {@link #applyMasking(ExecutionResult)}에서 처리</li>
 *   <li>7: TX2 커밋 후 SUCCESS/FAILED SSE emit (ExecutionResultPersister에서 등록)</li>
 * </ul>
 *
 * <h3>실행 흐름</h3>
 * <ol>
 *   <li>{@link #runAsync(Long, InterfaceConfig)} 진입 (별도 스레드, non-TX)</li>
 *   <li>MockExecutorFactory.get(protocol).execute(config) — sleep 허용</li>
 *   <li>MaskingRule 적용 (1차 방어)</li>
 *   <li>{@link ExecutionResultPersister#persist} 호출 — 프록시 경유 TX2 진입</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncExecutionRunner {

    private final MockExecutorFactory       mockFactory;
    private final MaskingRule               maskingRule;
    private final ExecutionResultPersister  persister;
    private final InterfaceConfigRepository configRepository;

    /**
     * 비동기 진입점. ExecutionTriggerService TX1 커밋 직후 호출.
     *
     * <p>Day 4부터 {@code configId}만 받고 내부에서 InterfaceConfig를 조회한다. Controller가
     * Repository를 직접 주입하던 우회 경로(ADR-006 Repository 주입 범위 위반)를 차단하기 위함.</p>
     *
     * @param logId    TX1에서 생성된 RUNNING 로그 ID
     * @param configId 실행 대상 인터페이스 ID
     */
    @Async(AsyncConfig.EXECUTION_POOL)
    public CompletableFuture<Void> runAsync(Long logId, Long configId) {
        try {
            InterfaceConfig config = configRepository.findById(configId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.INTERFACE_NOT_FOUND));
            MockExecutor mock = mockFactory.get(config.getProtocol());
            ExecutionResult raw = mock.execute(config);     // non-TX, sleep OK
            ExecutionResult masked = applyMasking(raw);     // TX2 진입 전 마스킹
            persister.persist(logId, masked);               // 별도 빈 호출 → 프록시 경유 TX2
        } catch (Exception e) {
            log.error("AsyncExecutionRunner 예외 logId={}: {}", logId, e.getMessage(), e);
            // 예외로 TX2 미진입 시 RUNNING이 그대로 남음 → OrphanRunningWatchdog가 회수
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * MaskingRule로 payload 마스킹 (ADR-001 §6 - 4).
     * JSON: Map 재귀 마스킹. XML: 값 정규식만 적용 (XML StAX는 Day 4 백로그).
     * 실패 분기는 payload가 없으므로 마스킹 불필요 — 그대로 반환.
     */
    @SuppressWarnings("unchecked")
    private ExecutionResult applyMasking(ExecutionResult r) {
        if (!r.success()) {
            return r;
        }
        if (r.payloadFormat() == PayloadFormat.JSON) {
            Map<String, Object> req = r.requestPayload() != null
                    ? (Map<String, Object>) maskingRule.mask(new LinkedHashMap<>(r.requestPayload()))
                    : null;
            Map<String, Object> res = r.responsePayload() != null
                    ? (Map<String, Object>) maskingRule.mask(new LinkedHashMap<>(r.responsePayload()))
                    : null;
            return ExecutionResult.successJson(r.durationMs(), req, res, r.payloadTruncated());
        }
        String reqXml = r.requestPayloadXml() != null ? maskingRule.maskString(r.requestPayloadXml()) : null;
        String resXml = r.responsePayloadXml() != null ? maskingRule.maskString(r.responsePayloadXml()) : null;
        return ExecutionResult.successXml(r.durationMs(), reqXml, resXml, r.payloadTruncated());
    }
}
