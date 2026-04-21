package com.noaats.ifms.domain.execution.repository;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import jakarta.persistence.Tuple;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * ExecutionLog Repository.
 * 정본 쿼리 패턴: erd.md §4.2 / §5 Q1~Q8.
 *
 * <h3>주요 쿼리</h3>
 * <ul>
 *   <li>findRunningByConfig: ADR-004 §C 동시 실행 차단 — uk_log_running partial UNIQUE 활용</li>
 *   <li>findChildOf: ADR-005 체인 분기 차단 — uk_log_parent UNIQUE seek</li>
 *   <li>findOrphanRunning: erd.md §8.3 OrphanRunningWatchdog 5분 주기 회수</li>
 * </ul>
 *
 * 페이지네이션은 OFFSET 기반 (erd.md §7.2). 100만 건 초과 시 cursor 전환은 운영 전환 시 (backlog).
 */
public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, Long> {

    /**
     * 동일 인터페이스의 RUNNING 1건 조회 (ADR-004 §C 정상 경로 방어선).
     * uk_log_running partial UNIQUE가 자동 B-tree 인덱스 → seek O(log N).
     */
    @Query("""
        SELECT el
        FROM   ExecutionLog el
        WHERE  el.interfaceConfig.id = :configId
          AND  el.status = com.noaats.ifms.domain.execution.domain.ExecutionStatus.RUNNING
        """)
    Optional<ExecutionLog> findRunningByConfig(@Param("configId") Long configId);

    /**
     * 부모의 자식(=재처리) 존재 확인 (ADR-005 체인 분기 차단의 정상 경로 방어선).
     * uk_log_parent UNIQUE 자동 B-tree 활용.
     */
    @Query("SELECT el FROM ExecutionLog el WHERE el.parent.id = :parentId")
    Optional<ExecutionLog> findChildOf(@Param("parentId") Long parentId);

    /**
     * 인터페이스별 이력 조회 (실행 이력 화면). idx_log_config_started 활용.
     */
    @Query("""
        SELECT el
        FROM   ExecutionLog el
        WHERE  el.interfaceConfig.id = :configId
        """)
    Page<ExecutionLog> findByConfigId(@Param("configId") Long configId, Pageable pageable);

    /**
     * 상태 필터 시계열 조회 (대시보드 최근 실패 N건). idx_log_status_started 활용.
     */
    Page<ExecutionLog> findByStatusOrderByStartedAtDesc(ExecutionStatus status, Pageable pageable);

    /**
     * 고아 RUNNING 회수 (erd.md §8.3 OrphanRunningWatchdog).
     * timeout_seconds + 60s를 초과한 RUNNING을 반환 → 호출자가 FAILED + STARTUP_RECOVERY로 마감.
     * 60초 grace는 Mock 실행기 평균 지연(~1s) + JVM GC 여유.
     *
     * <h3>표현식 형태</h3>
     * 의미: {@code started_at < now - 60s - timeout_seconds*1s}.
     * 좌변에 양수 합으로 옮긴 형태({@code started_at + 60s + timeout_seconds*1s < now})를 사용한다.
     * 이전 형태({@code now - INTERVAL - INTERVAL})는 PostgreSQL 직접 실행은 정상이지만
     * Hibernate 6의 native SQL prepared-statement 변환 단계에서
     * {@code timestamp < interval} 비교로 해석되는 회귀가 관측됨 (T21 §4.1).
     */
    @Query(value = """
        SELECT el.*
        FROM   execution_log    el
        JOIN   interface_config ic ON ic.id = el.interface_config_id
        WHERE  el.status = 'RUNNING'
          AND  el.started_at + INTERVAL '60 seconds'
                             + (ic.timeout_seconds * INTERVAL '1 second') < :now
        """, nativeQuery = true)
    List<ExecutionLog> findOrphanRunning(@Param("now") LocalDateTime now);

    /**
     * advisory lock 시도 (ADR-004 §C, ADR-005 §5.3).
     * key1=namespace(int), key2={interface_config_id 또는 parent_log_id}.
     *
     * <h3>PostgreSQL 함수 시그니처 호환</h3>
     * {@code pg_try_advisory_xact_lock}의 2-arg 형식은 {@code (int, int)}만 존재한다
     * ({@code (int, bigint)}는 없음). 따라서 키 2를 명시적으로 INT로 cast해 호출한다.
     * IDENTITY BIGSERIAL이 INT 범위(~21억)를 초과할 가능성은 본 프로토타입에서 무시 가능.
     * 운영 전환 시 1-arg bigint 인코딩(`(namespace << 32) | (id & 0xFFFFFFFF)`)으로 전환 권장.
     *
     * @return true=lock 획득 성공 (TX 종료까지 보유), false=다른 TX가 보유 중
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key1, CAST(:key2 AS INTEGER))",
           nativeQuery = true)
    Boolean tryAdvisoryLock(@Param("key1") int key1, @Param("key2") long key2);

    /**
     * 재처리 검증용 단일 SELECT (ADR-005 §5.2).
     * 부모 로그 + 인터페이스 상태 + 체인 루트 actor를 한 번에 가져온다.
     *
     * Native query 사용 이유: 루트 actor를 서브쿼리로 가져오는 데 JPQL이 표현 부족.
     *
     * 반환 타입: {@link Tuple} (이름 기반 추출). 9개 컬럼 모두 AS alias 필수 —
     * alias가 {@code RetryGuardSnapshot#fromTuple} 추출 키와 1:1 매칭.
     * (T21 회의 결정: Object[] 위치 기반은 컬럼 순서 변경 시 권한 경계 우회 리스크)
     *
     * 호출 후 Service 레이어에서 분기 — RetryGuard가 책임.
     */
    @Query(value = """
        SELECT p.id                                                     AS parent_id,
               p.actor_id                                               AS parent_actor,
               p.retry_count                                            AS parent_retry_count,
               p.max_retry_snapshot                                     AS max_retry_snapshot,
               p.payload_truncated                                      AS payload_truncated,
               p.status                                                 AS parent_status,
               COALESCE(p.root_log_id, p.id)                            AS root_id,
               (SELECT actor_id
                  FROM execution_log
                 WHERE id = COALESCE(p.root_log_id, p.id))              AS root_actor,
               ic.status                                                AS ic_status
        FROM   execution_log    p
        JOIN   interface_config ic ON ic.id = p.interface_config_id
        WHERE  p.id = :parentId
        """, nativeQuery = true)
    Optional<Tuple> findRetryGuardSnapshot(@Param("parentId") Long parentId);

    /**
     * 시작 시 1회 전수 고아 복구 (ADR-001 §5, erd §8.3).
     * ApplicationReadyEvent에서 호출 — 이전 인스턴스 비정상 종료로 남은 RUNNING 일괄 회수.
     */
    @Modifying
    @Query("""
        UPDATE ExecutionLog el
        SET    el.status        = com.noaats.ifms.domain.execution.domain.ExecutionStatus.FAILED,
               el.finishedAt    = CURRENT_TIMESTAMP,
               el.durationMs    = 0,
               el.errorCode     = com.noaats.ifms.domain.execution.domain.ExecutionErrorCode.STARTUP_RECOVERY,
               el.errorMessage  = 'ApplicationReadyEvent: 이전 인스턴스 비정상 종료로 인한 일괄 복구'
        WHERE  el.status        = com.noaats.ifms.domain.execution.domain.ExecutionStatus.RUNNING
        """)
    int recoverAllRunningOnStartup();

    // ========== Day 4 대시보드 집계 (api-spec.md §6.2) ==========

    /**
     * 총 실행 건수 + 상태별 집계. since 이후 started_at 기준.
     * FILTER 절은 PostgreSQL 9.4+ 지원 — SQL 표준 `FILTER (WHERE ...)`.
     */
    @Query(value = """
        SELECT
          COALESCE(COUNT(*) FILTER (WHERE status = 'SUCCESS'), 0) AS success,
          COALESCE(COUNT(*) FILTER (WHERE status = 'FAILED'),  0) AS failed,
          COALESCE(COUNT(*) FILTER (WHERE status = 'RUNNING'), 0) AS running,
          COUNT(*)                                                AS total
        FROM   execution_log
        WHERE  started_at >= :since
        """, nativeQuery = true)
    DashboardTotalsProjection aggregateTotals(@Param("since") LocalDateTime since);

    /** 프로토콜별 상태 집계. */
    @Query(value = """
        SELECT
          ic.protocol                                             AS protocol,
          COALESCE(COUNT(*) FILTER (WHERE el.status = 'SUCCESS'), 0) AS success,
          COALESCE(COUNT(*) FILTER (WHERE el.status = 'FAILED'),  0) AS failed,
          COALESCE(COUNT(*) FILTER (WHERE el.status = 'RUNNING'), 0) AS running
        FROM   execution_log    el
        JOIN   interface_config ic ON ic.id = el.interface_config_id
        WHERE  el.started_at >= :since
        GROUP  BY ic.protocol
        ORDER  BY ic.protocol
        """, nativeQuery = true)
    List<DashboardProtocolProjection> aggregateByProtocol(@Param("since") LocalDateTime since);

    /** 최근 실패 N건 (interfaceName JOIN). idx_log_status_started 활용. */
    @Query(value = """
        SELECT
          el.id              AS id,
          ic.name            AS interfaceName,
          el.error_code      AS errorCode,
          el.started_at      AS startedAt
        FROM   execution_log    el
        JOIN   interface_config ic ON ic.id = el.interface_config_id
        WHERE  el.status     = 'FAILED'
          AND  el.started_at >= :since
        ORDER  BY el.started_at DESC
        LIMIT  :limit
        """, nativeQuery = true)
    List<DashboardRecentFailureProjection> findRecentFailures(
            @Param("since") LocalDateTime since, @Param("limit") int limit);

    interface DashboardTotalsProjection {
        long getSuccess();
        long getFailed();
        long getRunning();
        long getTotal();
    }

    interface DashboardProtocolProjection {
        String getProtocol();
        long getSuccess();
        long getFailed();
        long getRunning();
    }

    interface DashboardRecentFailureProjection {
        Long getId();
        String getInterfaceName();
        String getErrorCode();
        LocalDateTime getStartedAt();
    }

    // ========== Day 6 delta + 리스트 페이지네이션 (ADR-007 R1) ==========

    /**
     * delta 조회 — started_at >= since, ASC 정렬, Pageable로 limit+1 건 제한 (ADR-007 R1).
     */
    @Query("""
            SELECT el FROM ExecutionLog el
            WHERE el.startedAt >= :since
            ORDER BY el.startedAt ASC
            """)
    List<ExecutionLog> findDeltaSince(@Param("since") java.time.LocalDateTime since,
                                      Pageable pageable);

    /**
     * 리스트 페이지네이션 조회 — ExecutionHistory.vue에서 사용.
     * status·interfaceConfigId 필터 지원. 필터 null 시 전체.
     */
    @Query("""
            SELECT el FROM ExecutionLog el
            WHERE (:status IS NULL OR el.status = :status)
              AND (:configId IS NULL OR el.interfaceConfig.id = :configId)
            """)
    Page<ExecutionLog> findList(@Param("status") ExecutionStatus status,
                                @Param("configId") Long configId,
                                Pageable pageable);
}
