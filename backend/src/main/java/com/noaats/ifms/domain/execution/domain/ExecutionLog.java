package com.noaats.ifms.domain.execution.domain;

import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 인터페이스 실행 이력 — append-only 금융권 감사 로그.
 * 정본 스키마: erd.md §3.2 / §10.2.
 *
 * <h3>설계 원칙</h3>
 * <ul>
 *   <li><b>append-only</b>: BaseTimeEntity 미상속. updated_at 의미 없음, 종료 시점은 finished_at</li>
 *   <li><b>상태 전이는 비즈니스 메서드만</b>: complete()로 한 번만 종료, 이후 변경 금지</li>
 *   <li><b>마스킹 사전 완료</b>: 본 Entity에 들어오는 payload는 이미 MaskingRule 적용본 (ADR-001 §3, §4)</li>
 * </ul>
 *
 * <h3>재처리 체인 (ADR-005)</h3>
 * <ul>
 *   <li>원본: parent=null, root=null, retryCount=0, maxRetrySnapshot=ic.maxRetryCount</li>
 *   <li>재처리: parent=직전 부모, root=COALESCE(parent.root, parent), retryCount=parent.retryCount+1,
 *       maxRetrySnapshot=parent.maxRetrySnapshot (루트 캐스케이드)</li>
 * </ul>
 *
 * <h3>동시성 (ADR-004)</h3>
 * uk_log_running 부분 UNIQUE + uk_log_parent UNIQUE가 advisory lock의 safety net 역할.
 */
@Entity
@Table(name = "execution_log")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "interface_config_id", nullable = false)
    private InterfaceConfig interfaceConfig;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_log_id")
    private ExecutionLog parent;

    /**
     * ADR-005 Q2: 체인 루트 비정규화. 자식 INSERT 시 COALESCE(parent.root, parent)로 캐스케이드.
     * 원본은 NULL (자기 자신이 루트). RETRY_FORBIDDEN_ACTOR · max_retry_snapshot 검증을
     * 단일 SELECT로 처리하기 위함.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "root_log_id")
    private ExecutionLog root;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false, length = 15)
    private TriggerType triggeredBy;

    @Column(name = "actor_id", nullable = false, length = 255)
    private String actorId;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ExecutionStatus status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private Map<String, Object> requestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb")
    private Map<String, Object> responsePayload;

    @Column(name = "request_payload_xml", columnDefinition = "text")
    private String requestPayloadXml;

    @Column(name = "response_payload_xml", columnDefinition = "text")
    private String responsePayloadXml;

    @Enumerated(EnumType.STRING)
    @Column(name = "payload_format", nullable = false, length = 10)
    private PayloadFormat payloadFormat;

    @Column(name = "payload_truncated", nullable = false)
    private boolean payloadTruncated;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code", length = 50)
    private ExecutionErrorCode errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    /**
     * ADR-005 Q1: 체인 루트 INSERT 시점 interface_config.max_retry_count 스냅샷.
     * PATCH 후행 적용 차단. 자식은 parent.maxRetrySnapshot 그대로 캐스케이드 (루트 동결).
     */
    @Column(name = "max_retry_snapshot", nullable = false)
    private Integer maxRetrySnapshot;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    /**
     * 원본 실행 (MANUAL/SCHEDULER) 정적 팩토리.
     * RUNNING 상태로 INSERT되며, 종료는 {@link #complete(ExecutionResult)} 호출.
     *
     * @param config       실행 대상 인터페이스
     * @param triggeredBy  MANUAL 또는 SCHEDULER (RETRY는 spawnRetry 사용)
     * @param actorId      실행 주체 (MANUAL=세션 actor, SCHEDULER=SYSTEM)
     * @param clientIp     IP (MANUAL만, SCHEDULER는 null)
     * @param userAgent    UA (MANUAL만, SCHEDULER는 null)
     * @param defaultFormat 인터페이스 프로토콜 기본 payload 포맷
     */
    public static ExecutionLog start(InterfaceConfig config,
                                     TriggerType triggeredBy,
                                     String actorId,
                                     String clientIp,
                                     String userAgent,
                                     PayloadFormat defaultFormat) {
        if (triggeredBy == TriggerType.RETRY) {
            throw new IllegalArgumentException("RETRY는 spawnRetry()로 생성하세요");
        }
        ExecutionLog log = new ExecutionLog();
        log.interfaceConfig = config;
        log.parent = null;
        log.root = null;
        log.triggeredBy = triggeredBy;
        log.actorId = actorId;
        log.clientIp = clientIp;
        log.userAgent = userAgent;
        log.status = ExecutionStatus.RUNNING;
        log.startedAt = LocalDateTime.now();
        log.payloadFormat = defaultFormat;
        log.payloadTruncated = false;
        log.retryCount = 0;
        log.maxRetrySnapshot = config.getMaxRetryCount();
        return log;
    }

    /**
     * 재처리 INSERT (ADR-005 Q2). 본 메서드만이 RETRY 로그의 불변식을 강제한다.
     * <ul>
     *   <li>parent.status = FAILED 보장 (호출자 책임 — RetryGuard에서 검증)</li>
     *   <li>root = COALESCE(parent.root, parent) — 루트 비정규화 캐스케이드</li>
     *   <li>retryCount = parent.retryCount + 1 — 누적</li>
     *   <li>maxRetrySnapshot = parent.maxRetrySnapshot — 루트 스냅샷 그대로 (PATCH 비반영)</li>
     * </ul>
     */
    public static ExecutionLog spawnRetry(ExecutionLog parent,
                                          String actorId,
                                          String clientIp,
                                          String userAgent) {
        ExecutionLog log = new ExecutionLog();
        log.interfaceConfig = parent.interfaceConfig;
        log.parent = parent;
        log.root = parent.root != null ? parent.root : parent;
        log.triggeredBy = TriggerType.RETRY;
        log.actorId = actorId;
        log.clientIp = clientIp;
        log.userAgent = userAgent;
        log.status = ExecutionStatus.RUNNING;
        log.startedAt = LocalDateTime.now();
        log.payloadFormat = parent.payloadFormat;
        log.payloadTruncated = false;
        log.retryCount = parent.retryCount + 1;
        log.maxRetrySnapshot = parent.maxRetrySnapshot;
        return log;
    }

    /**
     * 상태 전이: RUNNING → SUCCESS/FAILED. 재호출 금지 (멱등 아님).
     * payload는 ExecutionResult가 보유한 이미-마스킹된 값이 그대로 영속화된다.
     */
    public void complete(ExecutionResult r) {
        assertRunning();
        this.status = r.success() ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED;
        this.finishedAt = LocalDateTime.now();
        this.durationMs = r.durationMs();
        this.payloadFormat = r.payloadFormat();
        if (r.payloadFormat() == PayloadFormat.JSON) {
            this.requestPayload = r.requestPayload();
            this.responsePayload = r.responsePayload();
        } else {
            this.requestPayloadXml = r.requestPayloadXml();
            this.responsePayloadXml = r.responsePayloadXml();
        }
        this.payloadTruncated = r.payloadTruncated();
        this.errorCode = r.errorCode();
        this.errorMessage = r.errorMessage();
    }

    /**
     * OrphanRunningWatchdog 전용 강제 종료 (erd.md §8.3).
     * timeout_seconds + 60s를 초과한 RUNNING을 FAILED + STARTUP_RECOVERY로 마감.
     */
    public void markRecovered(long durationMs) {
        assertRunning();
        this.status = ExecutionStatus.FAILED;
        this.finishedAt = LocalDateTime.now();
        this.durationMs = durationMs;
        this.errorCode = ExecutionErrorCode.STARTUP_RECOVERY;
        this.errorMessage = "OrphanRunningWatchdog: timeout 초과로 강제 종료";
    }

    public boolean isRunning() {
        return status == ExecutionStatus.RUNNING;
    }

    public boolean isFailed() {
        return status == ExecutionStatus.FAILED;
    }

    /** 체인 루트 ID. 본인이 루트면 자기 ID, 자식이면 root_log_id. */
    public Long resolveRootId() {
        return root != null ? root.getId() : id;
    }

    private void assertRunning() {
        if (status != ExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                    "이미 종료된 로그는 상태 전이 불가: id=" + id + ", status=" + status);
        }
    }
}
