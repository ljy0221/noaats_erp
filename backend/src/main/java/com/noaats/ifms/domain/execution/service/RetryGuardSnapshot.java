package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import jakarta.persistence.Tuple;

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
     * Repository native query Tuple → record 변환 (이름 기반).
     *
     * <p>T21 회의 결정으로 {@code Object[]} 위치 기반에서 전환.
     * 추출 키는 {@code findRetryGuardSnapshot} SELECT의 AS alias와 1:1 매칭.
     * 위치가 아닌 alias 이름에 의존하므로 컬럼 순서 재배치·신규 컬럼 삽입에 면역.
     */
    public static RetryGuardSnapshot fromTuple(Tuple row) {
        return new RetryGuardSnapshot(
                ((Number)  row.get("parent_id")).longValue(),
                (String)   row.get("parent_actor"),
                ((Number)  row.get("parent_retry_count")).intValue(),
                ((Number)  row.get("max_retry_snapshot")).intValue(),
                (Boolean)  row.get("payload_truncated"),
                ExecutionStatus.valueOf((String) row.get("parent_status")),
                ((Number)  row.get("root_id")).longValue(),
                (String)   row.get("root_actor"),
                InterfaceStatus.valueOf((String) row.get("ic_status"))
        );
    }
}
