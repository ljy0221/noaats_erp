package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.global.exception.BusinessException;
import com.noaats.ifms.global.exception.ConflictException;
import com.noaats.ifms.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 재처리 검증 — ADR-005 §5.2 평가 순서 강제.
 *
 * <h3>평가 순서</h3>
 * api-spec §1.3 우선순위 표 그대로:
 * <ol>
 *   <li>RETRY_FORBIDDEN_ACTOR (403)  — 체인 루트 actor 기준 (ADR-005 Q2)</li>
 *   <li>INTERFACE_INACTIVE (400)     — 신규 실행과 동일 정책 (ADR-005 Q3)</li>
 *   <li>RETRY_NOT_LEAF (409)         — 부모가 RUNNING/SUCCESS면 리프 아님</li>
 *   <li>RETRY_TRUNCATED_BLOCKED (409)</li>
 *   <li>RETRY_LIMIT_EXCEEDED (409)   — max_retry_snapshot 기준 (ADR-005 Q1)</li>
 * </ol>
 *
 * DUPLICATE_RUNNING / RETRY_CHAIN_CONFLICT는 advisory lock 단계에서 평가하므로
 * 본 Guard에는 포함하지 않는다 (api-spec §1.3 9·10번).
 */
@Component
public class RetryGuard {

    /**
     * @param snap        단일 SELECT 결과
     * @param sessionActor 현재 세션 actor (api-spec §2.3 추출 결과)
     * @throws BusinessException / ConflictException 위반 시 첫 위반 단일 코드만 throw
     */
    public void verify(RetryGuardSnapshot snap, String sessionActor) {
        // 1. RETRY_FORBIDDEN_ACTOR — 체인 루트 actor 기준
        if (!isRootActorAllowed(snap.rootActorId(), sessionActor)) {
            throw new BusinessException(ErrorCode.RETRY_FORBIDDEN_ACTOR);
        }

        // 2. INTERFACE_INACTIVE — 신규 실행과 동일 정책 (ADR-005 Q3)
        if (snap.interfaceStatus() != InterfaceStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INTERFACE_INACTIVE);
        }

        // 3. RETRY_NOT_LEAF — 부모가 FAILED여야 재처리 가능
        if (snap.parentStatus() != ExecutionStatus.FAILED) {
            throw new ConflictException(ErrorCode.RETRY_NOT_LEAF);
        }

        // 4. RETRY_TRUNCATED_BLOCKED
        if (snap.payloadTruncated()) {
            throw new ConflictException(ErrorCode.RETRY_TRUNCATED_BLOCKED);
        }

        // 5. RETRY_LIMIT_EXCEEDED — max_retry_snapshot 기준 (ADR-005 Q1)
        if (snap.parentRetryCount() + 1 > snap.maxRetrySnapshot()) {
            throw new ConflictException(ErrorCode.RETRY_LIMIT_EXCEEDED);
        }
    }

    /**
     * 체인 루트 actor 매칭 (api-spec §5.3 예외 규칙).
     * <ul>
     *   <li>SYSTEM 원본은 모든 OPERATOR가 재처리 가능</li>
     *   <li>ANONYMOUS_* 원본은 재처리 금지 (원 소유자 확인 불가)</li>
     *   <li>그 외에는 actor 일치 필수</li>
     * </ul>
     * Day 4 Role=ADMIN 도입 시 본 메서드 확장 (api-spec §5.3 라인 608).
     */
    private boolean isRootActorAllowed(String rootActor, String sessionActor) {
        if (rootActor == null || sessionActor == null) {
            return false;
        }
        if ("SYSTEM".equals(rootActor)) {
            return true;
        }
        if (rootActor.startsWith("ANONYMOUS_")) {
            return false;
        }
        return rootActor.equals(sessionActor);
    }
}
