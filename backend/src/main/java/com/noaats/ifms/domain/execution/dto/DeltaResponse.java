package com.noaats.ifms.domain.execution.dto;

import java.util.List;

/**
 * {@code GET /api/executions/delta} 응답 (ADR-007 R1).
 *
 * <p>SSE 폴백용 증분 조회. {@code truncated=true}면 클라이언트는
 * {@code nextCursor}로 이어받아야 한다.</p>
 *
 * @param items       증분 ExecutionLog 리스트 (최신→과거 순서는 상위 계약 참조)
 * @param truncated   결과가 서버 limit에 걸려 잘렸는지 여부
 * @param nextCursor  다음 페이지 커서 (truncated=true일 때만 의미 있음), 없으면 null
 */
public record DeltaResponse(
        List<ExecutionLogResponse> items,
        boolean truncated,
        String nextCursor
) {}
