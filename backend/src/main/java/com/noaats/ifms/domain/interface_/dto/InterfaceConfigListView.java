package com.noaats.ifms.domain.interface_.dto;

import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ProtocolType;
import com.noaats.ifms.domain.interface_.domain.ScheduleType;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

/**
 * 목록 조회 전용 DTO (api-spec.md §4.1).
 *
 * configJson은 의도적으로 제외 — 목록 API는 다건이라 민감정보(인증 헤더, 시크릿 ref) 노출
 * 표면적이 크므로 제외하고, 상세 조회(InterfaceConfigDetailResponse)에서만 노출.
 */
@Getter
@Builder
public class InterfaceConfigListView {

    private Long id;
    private String name;
    private String description;
    private ProtocolType protocol;
    private String endpoint;
    private String httpMethod;
    private ScheduleType scheduleType;
    private String cronExpression;
    private Integer timeoutSeconds;
    private Integer maxRetryCount;
    private InterfaceStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static InterfaceConfigListView from(InterfaceConfig e) {
        return InterfaceConfigListView.builder()
                .id(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .protocol(e.getProtocol())
                .endpoint(e.getEndpoint())
                .httpMethod(e.getHttpMethod())
                .scheduleType(e.getScheduleType())
                .cronExpression(e.getCronExpression())
                .timeoutSeconds(e.getTimeoutSeconds())
                .maxRetryCount(e.getMaxRetryCount())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
