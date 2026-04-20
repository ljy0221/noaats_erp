package com.noaats.ifms.domain.monitor.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대시보드 응답 DTO (api-spec.md §6.2).
 */
public record DashboardResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        OffsetDateTime generatedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        OffsetDateTime since,
        TotalStats totals,
        List<ProtocolStats> byProtocol,
        List<RecentFailure> recentFailures,
        int sseConnections) {
}
