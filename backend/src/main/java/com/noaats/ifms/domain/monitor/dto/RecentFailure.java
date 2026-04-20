package com.noaats.ifms.domain.monitor.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;

/**
 * 대시보드 최근 실패 엔트리 (api-spec.md §6.2).
 */
public record RecentFailure(
        long id,
        String interfaceName,
        String errorCode,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        OffsetDateTime startedAt) {
}
