package com.noaats.ifms.domain.execution.mock;

import com.noaats.ifms.domain.execution.domain.ExecutionResult;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;

/**
 * 프로토콜별 Mock 실행기 SPI (planning.md §5.5, mock-executor 스킬).
 *
 * 구현체는 절대 외부 네트워크를 호출하지 않는다 (CLAUDE.md 제약).
 * 응답 payload는 마스킹 적용 전 원본을 반환하며, 마스킹은
 * {@link com.noaats.ifms.domain.execution.service.AsyncExecutionRunner}가
 * {@link com.noaats.ifms.global.masking.MaskingRule}로 일괄 처리한다 (ADR-001 §4).
 */
public interface MockExecutor {

    /**
     * 인터페이스 정의를 받아 Mock 호출을 수행하고 결과를 반환한다.
     * <ul>
     *   <li>실행 시간은 Thread.sleep으로 시뮬레이션 (timeout_seconds 초과 금지)</li>
     *   <li>실패율은 구현체별로 결정. timeout 시뮬레이션 시 TIMEOUT_EXCEEDED 코드 반환</li>
     *   <li>InterruptedException은 호출 스레드 인터럽트 플래그를 복원하고 FAILED로 반환</li>
     * </ul>
     */
    ExecutionResult execute(InterfaceConfig config);
}
