package com.noaats.ifms.domain.interface_.dto;

import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ProtocolType;
import com.noaats.ifms.domain.interface_.domain.ScheduleType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 인터페이스 등록/수정 공용 요청 DTO (api-spec.md §8.1).
 *
 * - POST /api/interfaces 등록 시: name·protocol·endpoint·scheduleType 필수 (Service 수동 검증).
 * - PATCH /api/interfaces/{id} 수정 시: version 필수, 나머지는 변경할 필드만 전달.
 *
 * DTO 레벨 `@NotNull`/`@NotBlank`는 의도적으로 최소화:
 * POST/PATCH 공용 DTO라 @Valid만으로 POST 필수를 강제하면 PATCH 부분 수정이 깨진다.
 * 필수 필드 검증은 {@code InterfaceConfigService.validateRequiredForCreate()}가 담당.
 */
@Getter
@Setter
@NoArgsConstructor
public class InterfaceConfigRequest {

    @Size(max = 100)
    private String name;

    @Size(max = 500)
    private String description;

    private ProtocolType protocol;

    @Size(max = 500)
    private String endpoint;

    @Size(max = 10)
    private String httpMethod;

    private Map<String, Object> configJson;

    private ScheduleType scheduleType;

    @Size(max = 100)
    private String cronExpression;

    @Min(1)
    @Max(600)
    private Integer timeoutSeconds;

    @Min(0)
    @Max(10)
    private Integer maxRetryCount;

    /**
     * 낙관적 락 (@Version) 대조용. PATCH 시 필수, POST 시 null.
     * api-spec.md §3.5 / §4.4 참조.
     */
    private Long version;

    /**
     * PATCH 시 상태 변경 (activate/deactivate 통합). null이면 상태 미변경.
     * POST에서는 서버가 ACTIVE로 고정하므로 무시된다.
     */
    private InterfaceStatus statusChange;

    /**
     * CRON ↔ cronExpression 교차 검증.
     * - scheduleType이 null이면 본 제약은 통과 (Service가 null 자체를 400으로 잡는다).
     * - scheduleType=CRON이면 cronExpression 필수.
     */
    @AssertTrue(message = "scheduleType=CRON이면 cronExpression 필수")
    public boolean isCronExpressionValid() {
        if (scheduleType == null) return true;
        return scheduleType != ScheduleType.CRON
                || (cronExpression != null && !cronExpression.isBlank());
    }
}
