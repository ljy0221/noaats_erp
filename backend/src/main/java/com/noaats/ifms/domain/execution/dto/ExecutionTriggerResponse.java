package com.noaats.ifms.domain.execution.dto;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.domain.TriggerType;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * `POST /api/interfaces/{id}/execute` · `POST /api/executions/{id}/retry` 응답 (api-spec.md §4.5 / §5.3).
 *
 * TX1 커밋 직후의 RUNNING 로그 정보만 포함한다 — 실제 실행 결과는 SSE로 받는다.
 *
 * <h3>타임존 처리</h3>
 * Entity는 LocalDateTime으로 저장되지만(서버 KST), 응답은 api-spec §1.4
 * "offset 포함 ISO-8601"을 따라 OffsetDateTime으로 직렬화한다.
 * (Asia/Seoul 기준 +09:00 부여)
 *
 * @param logId        새로 생성된 ExecutionLog ID
 * @param interfaceId  대상 인터페이스 ID
 * @param parentLogId  재처리인 경우 직전 부모 ID, 원본 실행이면 null
 * @param status       항상 RUNNING (TX1 커밋 시점)
 * @param triggeredBy  MANUAL / SCHEDULER / RETRY
 * @param retryCount   본 로그 기준 누적 재처리 횟수 (원본=0)
 * @param startedAt    TX1 진입 시각 (KST offset 포함)
 */
public record ExecutionTriggerResponse(
        Long logId,
        Long interfaceId,
        Long parentLogId,
        ExecutionStatus status,
        TriggerType triggeredBy,
        Integer retryCount,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        OffsetDateTime startedAt
) {

    /** 서버 운영 타임존 — application.yml jdbc.time_zone과 일치. */
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Seoul");

    public static ExecutionTriggerResponse from(ExecutionLog log) {
        return new ExecutionTriggerResponse(
                log.getId(),
                log.getInterfaceConfig().getId(),
                log.getParent() != null ? log.getParent().getId() : null,
                log.getStatus(),
                log.getTriggeredBy(),
                log.getRetryCount(),
                log.getStartedAt().atZone(SERVER_ZONE).toOffsetDateTime()
        );
    }
}
