package com.noaats.ifms.domain.interface_.domain;

import com.noaats.ifms.global.audit.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 인터페이스 정의 Entity.
 * 정본 스키마는 erd.md §3.1 / §10.1.
 *
 * 원칙
 * - Setter 금지, 상태 변경은 비즈니스 메서드(update/deactivate/activate)로만
 * - 낙관적 락은 @Version (erd.md §8.2)
 * - configJson은 JSONB + Map 매핑 (Hibernate 6 네이티브 @JdbcTypeCode)
 * - ConfigJsonValidator는 Service 레이어에서 create/update 시 호출 (EntityListener 미사용)
 */
@Entity
@Table(
        name = "interface_config",
        // 제약명을 `uk_ifc_name`으로 고정. GlobalExceptionHandler가 DataIntegrityViolationException
        // 분기에서 이 이름을 문자열 매칭하여 DUPLICATE_NAME으로 변환하므로 Hibernate 기본 네이밍 대신 명시 (DBA 지적).
        uniqueConstraints = @UniqueConstraint(name = "uk_ifc_name", columnNames = "name")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterfaceConfig extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // unique=true 제거 (uniqueConstraints로 명시 이전)
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol", nullable = false, length = 10)
    private ProtocolType protocol;

    @Column(name = "endpoint", nullable = false, length = 500)
    private String endpoint;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    /**
     * 프로토콜별 추가 설정. JSONB 저장.
     * 시크릿 평문 저장 금지 — Service 레이어 ConfigJsonValidator가 차단.
     *
     * Hibernate 6 네이티브 @JdbcTypeCode(SqlTypes.JSON):
     * PostgreSQL Dialect가 jsonb 컬럼에 자동 매핑하며 Jackson으로 Map ↔ JSONB 변환.
     * 외부 라이브러리(hypersistence-utils) 불필요.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> configJson = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 10)
    private ScheduleType scheduleType;

    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds;

    @Column(name = "max_retry_count", nullable = false)
    private Integer maxRetryCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private InterfaceStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    /**
     * CRON 스케줄러 직전 발화 시각. InterfaceCronScheduler가 cronExpression.next(lastScheduledAt) 평가에 사용.
     * MANUAL 스케줄 타입은 항상 NULL. 재기동 시 NULL이면 첫 폴링 tick 시점을 기준으로 다음 발화를 계산(catch-up 안 함).
     */
    @Column(name = "last_scheduled_at")
    private LocalDateTime lastScheduledAt;

    @Builder
    private InterfaceConfig(String name,
                            String description,
                            ProtocolType protocol,
                            String endpoint,
                            String httpMethod,
                            Map<String, Object> configJson,
                            ScheduleType scheduleType,
                            String cronExpression,
                            Integer timeoutSeconds,
                            Integer maxRetryCount,
                            InterfaceStatus status) {
        this.name = name;
        this.description = description;
        this.protocol = protocol;
        this.endpoint = endpoint;
        this.httpMethod = httpMethod;
        this.configJson = configJson != null ? configJson : new HashMap<>();
        this.scheduleType = scheduleType != null ? scheduleType : ScheduleType.MANUAL;
        this.cronExpression = cronExpression;
        this.timeoutSeconds = timeoutSeconds != null ? timeoutSeconds : 30;
        this.maxRetryCount = maxRetryCount != null ? maxRetryCount : 3;
        this.status = status != null ? status : InterfaceStatus.ACTIVE;
    }

    /**
     * 부분 필드 수정. null 필드는 미변경.
     * @Version 기반 낙관적 락은 JPA가 자동 증가.
     */
    public void update(String description,
                       String endpoint,
                       String httpMethod,
                       Map<String, Object> configJson,
                       ScheduleType scheduleType,
                       String cronExpression,
                       Integer timeoutSeconds,
                       Integer maxRetryCount) {
        if (description != null) this.description = description;
        if (endpoint != null) this.endpoint = endpoint;
        if (httpMethod != null) this.httpMethod = httpMethod;
        if (configJson != null) this.configJson = configJson;
        if (scheduleType != null) this.scheduleType = scheduleType;
        if (cronExpression != null) this.cronExpression = cronExpression;
        if (timeoutSeconds != null) this.timeoutSeconds = timeoutSeconds;
        if (maxRetryCount != null) this.maxRetryCount = maxRetryCount;
    }

    public void deactivate() {
        this.status = InterfaceStatus.INACTIVE;
    }

    public void activate() {
        this.status = InterfaceStatus.ACTIVE;
    }

    /**
     * 스케줄러가 방금 트리거했음을 기록. CronExpression.next(lastScheduledAt) 평가 기준점 갱신.
     * @param firedAt InterfaceCronScheduler tick 시각 (LocalDateTime.now())
     */
    public void markScheduled(LocalDateTime firedAt) {
        this.lastScheduledAt = firedAt;
    }

    public boolean isActive() {
        return status == InterfaceStatus.ACTIVE;
    }
}
