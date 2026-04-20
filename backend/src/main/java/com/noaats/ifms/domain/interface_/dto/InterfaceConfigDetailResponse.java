package com.noaats.ifms.domain.interface_.dto;

import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ProtocolType;
import com.noaats.ifms.domain.interface_.domain.ScheduleType;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * 단건 상세 조회 응답 DTO (api-spec.md §4.2 / §8.1).
 *
 * configJson 포함 — ConfigJsonValidator가 저장 시점에 평문 시크릿 차단하므로
 * 여기서 그대로 노출해도 안전. 단 api-spec.md §3.4 DefensiveMaskingFilter가
 * 응답 직전 2차 패턴 스캐너 역할 (우회 데이터 방어).
 *
 * version 필드 포함 — PATCH 요청 시 낙관적 락 대조용.
 */
@Getter
@Builder
public class InterfaceConfigDetailResponse {

    private Long id;
    private String name;
    private String description;
    private ProtocolType protocol;
    private String endpoint;
    private String httpMethod;
    private Map<String, Object> configJson;
    private ScheduleType scheduleType;
    private String cronExpression;
    private Integer timeoutSeconds;
    private Integer maxRetryCount;
    private InterfaceStatus status;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static InterfaceConfigDetailResponse from(InterfaceConfig e) {
        return InterfaceConfigDetailResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .protocol(e.getProtocol())
                .endpoint(e.getEndpoint())
                .httpMethod(e.getHttpMethod())
                // 저장본 자체가 MaskingRule(Service 1차) 적용본이므로 원본 그대로 노출.
                // 응답 직전 DefensiveMaskingFilter(2차)가 회귀 방어 재스캔 수행.
                .configJson(e.getConfigJson())
                .scheduleType(e.getScheduleType())
                .cronExpression(e.getCronExpression())
                .timeoutSeconds(e.getTimeoutSeconds())
                .maxRetryCount(e.getMaxRetryCount())
                .status(e.getStatus())
                .version(e.getVersion())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
