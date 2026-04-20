package com.noaats.ifms.domain.interface_.dto;

import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ProtocolType;
import com.noaats.ifms.domain.interface_.domain.ScheduleType;
import java.time.LocalDateTime;

/**
 * 낙관적 락 충돌(409 OPTIMISTIC_LOCK_CONFLICT) 응답 전용 경량 스냅샷.
 *
 * {@link InterfaceConfigDetailResponse}와 달리 `configJson`을 **의도적으로 제외**한다:
 * - 409 UX는 name/status/version/updatedAt 대조가 핵심
 * - configJson 제외로 마스킹 재실행·PII 노출 리스크 감소
 * - DefensiveMaskingFilter가 ErrorDetail 응답을 skip하므로 이 DTO는 내부에 민감값이 없어야 함
 */
public record InterfaceConfigSnapshot(
        Long id,
        String name,
        String description,
        ProtocolType protocol,
        String endpoint,
        String httpMethod,
        ScheduleType scheduleType,
        String cronExpression,
        Integer timeoutSeconds,
        Integer maxRetryCount,
        InterfaceStatus status,
        Long version,
        LocalDateTime updatedAt) {

    public static InterfaceConfigSnapshot from(InterfaceConfig e) {
        return new InterfaceConfigSnapshot(
                e.getId(),
                e.getName(),
                e.getDescription(),
                e.getProtocol(),
                e.getEndpoint(),
                e.getHttpMethod(),
                e.getScheduleType(),
                e.getCronExpression(),
                e.getTimeoutSeconds(),
                e.getMaxRetryCount(),
                e.getStatus(),
                e.getVersion(),
                e.getUpdatedAt());
    }
}
