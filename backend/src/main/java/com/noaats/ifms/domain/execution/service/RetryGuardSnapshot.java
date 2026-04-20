package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;

/**
 * 재처리 검증을 위한 단일 SELECT 결과 (ADR-005 §5.2).
 * {@code ExecutionLogRepository.findRetryGuardSnapshot}의 native query 컬럼 매핑.
 *
 * @param parentId          부모 로그 ID
 * @param parentActorId     부모의 actor (체인 루트와 다를 수 있음 — RETRY_FORBIDDEN_ACTOR는 root 기준)
 * @param parentRetryCount  부모의 누적 재처리 횟수
 * @param maxRetrySnapshot  체인 루트 등록 시점 max_retry 스냅샷 (ADR-005 Q1)
 * @param payloadTruncated  부모가 truncate 됐는지
 * @param parentStatus      부모 상태 (FAILED만 재처리 허용)
 * @param rootId            COALESCE(parent.root_log_id, parent.id) — 체인 루트 ID
 * @param rootActorId       체인 루트의 actor (RETRY_FORBIDDEN_ACTOR 평가 기준, ADR-005 Q2)
 * @param interfaceStatus   인터페이스의 현재 상태 (INACTIVE면 재처리 차단, ADR-005 Q3)
 */
public record RetryGuardSnapshot(
        Long parentId,
        String parentActorId,
        int parentRetryCount,
        int maxRetrySnapshot,
        boolean payloadTruncated,
        ExecutionStatus parentStatus,
        Long rootId,
        String rootActorId,
        InterfaceStatus interfaceStatus
) {
    /**
     * Repository native query Object[] → record 변환.
     * 컬럼 순서는 {@code findRetryGuardSnapshot} JPQL의 SELECT 절과 1:1 매칭.
     */
    public static RetryGuardSnapshot fromRow(Object[] row) {
        return new RetryGuardSnapshot(
                ((Number) row[0]).longValue(),
                (String) row[1],
                ((Number) row[2]).intValue(),
                ((Number) row[3]).intValue(),
                (boolean) row[4],
                ExecutionStatus.valueOf((String) row[5]),
                ((Number) row[6]).longValue(),
                (String) row[7],
                InterfaceStatus.valueOf((String) row[8])
        );
    }
}
