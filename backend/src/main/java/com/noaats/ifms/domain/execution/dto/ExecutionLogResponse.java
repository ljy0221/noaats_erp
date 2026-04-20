package com.noaats.ifms.domain.execution.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.domain.TriggerType;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 실행 로그 응답 DTO — 리스트/델타/상세 공통 사용 (Day 6 플랜).
 *
 * <h3>엔티티 매핑 주의</h3>
 * <ul>
 *   <li>{@code interfaceConfigId}는 엔티티의 {@link com.noaats.ifms.domain.interface_.domain.InterfaceConfig}
 *       연관에서 추출한다 ({@code log.getInterfaceConfig().getId()}).</li>
 *   <li>{@code parentLogId}는 엔티티의 {@code parent} 연관에서 추출한다 — 원본이면 null.</li>
 *   <li>엔티티의 {@link java.time.LocalDateTime}은 서버 운영 존(Asia/Seoul) 기준 {@link OffsetDateTime}으로 변환한다
 *       (api-spec §1.4: offset 포함 ISO-8601).</li>
 *   <li>TriggerType이 실제 enum 이름 (MANUAL / SCHEDULER / RETRY).</li>
 * </ul>
 *
 * @param id                ExecutionLog PK
 * @param interfaceConfigId 대상 인터페이스 설정 ID
 * @param interfaceName     대상 인터페이스 이름 (조회 성능을 위해 호출자가 주입)
 * @param status            RUNNING / SUCCESS / FAILED
 * @param triggeredBy       MANUAL / SCHEDULER / RETRY
 * @param startedAt         시작 시각 (KST offset)
 * @param finishedAt        종료 시각 (KST offset), RUNNING이면 null
 * @param durationMs        실행 소요 ms, RUNNING이면 null
 * @param retryCount        누적 재처리 횟수 (원본=0)
 * @param parentLogId       재처리인 경우 직전 부모 ID, 원본이면 null
 * @param errorMessage      실패 메시지, 성공/진행 중이면 null
 */
public record ExecutionLogResponse(
        Long id,
        Long interfaceConfigId,
        String interfaceName,
        ExecutionStatus status,
        TriggerType triggeredBy,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        OffsetDateTime startedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        OffsetDateTime finishedAt,
        Long durationMs,
        Integer retryCount,
        Long parentLogId,
        String errorMessage
) {

    /** 서버 운영 타임존 — application.yml jdbc.time_zone과 일치. */
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 엔티티 → DTO 변환. interfaceName은 N+1 회피를 위해 호출자가 명시적으로 주입한다.
     */
    public static ExecutionLogResponse of(ExecutionLog e, String interfaceName) {
        return new ExecutionLogResponse(
                e.getId(),
                e.getInterfaceConfig() != null ? e.getInterfaceConfig().getId() : null,
                interfaceName,
                e.getStatus(),
                e.getTriggeredBy(),
                e.getStartedAt() == null ? null : e.getStartedAt().atZone(SERVER_ZONE).toOffsetDateTime(),
                e.getFinishedAt() == null ? null : e.getFinishedAt().atZone(SERVER_ZONE).toOffsetDateTime(),
                e.getDurationMs(),
                e.getRetryCount(),
                e.getParent() != null ? e.getParent().getId() : null,
                e.getErrorMessage()
        );
    }
}
