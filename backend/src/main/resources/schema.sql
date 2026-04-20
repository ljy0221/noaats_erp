-- =============================================================================
-- IFMS schema.sql
-- =============================================================================
-- 이 파일은 Hibernate `ddl-auto=update`가 생성하지 못하는 요소들을
-- 보완 선언한다 (CHECK 제약, 부분/조건부 인덱스, 부분 UNIQUE).
--
-- 실행 순서: application.yml `defer-datasource-initialization=true`에 의해
--            Hibernate DDL → 이 파일 순으로 실행됨.
--
-- 주의: Spring Boot `ScriptUtils`는 단순 `;` 기반 statement splitter라
--       `DO $$ ... $$` 블록을 쓰면 "Unterminated dollar quote" 오류 발생.
--       따라서 각 CHECK/INDEX를 독립 statement로 작성하고,
--       `spring.sql.init.continue-on-error=true`로 중복 실행 시 오류를 무시한다.
--       PostgreSQL 16의 `CREATE INDEX IF NOT EXISTS`는 안전하나
--       `ALTER TABLE ADD CONSTRAINT IF NOT EXISTS`는 존재하지 않으므로
--       continue-on-error에 의존한다.
--
-- 경로 컨벤션: src/main/resources/schema.sql — Spring Boot 자동 실행.
--              Testcontainers에서는 @Sql 또는 컨테이너 init script로 재사용.
-- =============================================================================

-- =============================================================================
-- interface_config : CHECK 제약
-- =============================================================================
ALTER TABLE interface_config
    ADD CONSTRAINT ck_ifc_protocol
    CHECK (protocol IN ('REST','SOAP','MQ','BATCH','SFTP'));

ALTER TABLE interface_config
    ADD CONSTRAINT ck_ifc_status
    CHECK (status IN ('ACTIVE','INACTIVE'));

ALTER TABLE interface_config
    ADD CONSTRAINT ck_ifc_schedule_type
    CHECK (schedule_type IN ('MANUAL','CRON'));

ALTER TABLE interface_config
    ADD CONSTRAINT ck_ifc_timeout
    CHECK (timeout_seconds BETWEEN 1 AND 600);

ALTER TABLE interface_config
    ADD CONSTRAINT ck_ifc_max_retry
    CHECK (max_retry_count BETWEEN 0 AND 10);

ALTER TABLE interface_config
    ADD CONSTRAINT ck_ifc_http_method
    CHECK (protocol <> 'REST'
           OR http_method IN ('GET','POST','PUT','PATCH','DELETE'));

ALTER TABLE interface_config
    ADD CONSTRAINT ck_ifc_cron_required
    CHECK (schedule_type <> 'CRON' OR cron_expression IS NOT NULL);

-- =============================================================================
-- interface_config : 인덱스 (erd.md §4.1)
-- =============================================================================
CREATE INDEX IF NOT EXISTS idx_ifc_status_name
    ON interface_config(status, name);

CREATE INDEX IF NOT EXISTS idx_ifc_protocol
    ON interface_config(protocol)
    WHERE status = 'ACTIVE';

-- =============================================================================
-- execution_log : CHECK 제약 (erd.md §3.2)
-- =============================================================================
-- 주의: triggered_by/status/payload_format은 JPA가 VARCHAR로 매핑한다(@Enumerated(STRING)).
-- DB CHECK으로 enum 외 값 INSERT를 거부하여 애플리케이션 버그 회귀를 차단.

ALTER TABLE execution_log
    ADD CONSTRAINT ck_log_triggered_by
    CHECK (triggered_by IN ('MANUAL','SCHEDULER','RETRY'));

ALTER TABLE execution_log
    ADD CONSTRAINT ck_log_status
    CHECK (status IN ('RUNNING','SUCCESS','FAILED'));

ALTER TABLE execution_log
    ADD CONSTRAINT ck_log_payload_format
    CHECK (payload_format IN ('JSON','XML'));

ALTER TABLE execution_log
    ADD CONSTRAINT ck_log_retry_count
    CHECK (retry_count >= 0);

-- ADR-005 Q1: max_retry_snapshot 범위 (interface_config.max_retry_count 와 동일 0~10)
ALTER TABLE execution_log
    ADD CONSTRAINT ck_log_max_retry_snapshot
    CHECK (max_retry_snapshot BETWEEN 0 AND 10);

ALTER TABLE execution_log
    ADD CONSTRAINT ck_log_duration
    CHECK (duration_ms IS NULL OR duration_ms >= 0);

ALTER TABLE execution_log
    ADD CONSTRAINT ck_log_finish_time
    CHECK (finished_at IS NULL OR finished_at >= started_at);

-- triggered_by=RETRY는 parent_log_id 필수
ALTER TABLE execution_log
    ADD CONSTRAINT ck_log_retry_has_parent
    CHECK (triggered_by <> 'RETRY' OR parent_log_id IS NOT NULL);

-- 종료 상태(SUCCESS/FAILED)는 finished_at·duration_ms 채워져 있어야, RUNNING은 비어 있어야
ALTER TABLE execution_log
    ADD CONSTRAINT ck_log_terminal_fields
    CHECK (
        (status = 'RUNNING' AND finished_at IS NULL AND duration_ms IS NULL)
        OR (status IN ('SUCCESS','FAILED') AND finished_at IS NOT NULL AND duration_ms IS NOT NULL)
    );

-- payload_format에 따른 JSON/XML 컬럼 배타적 사용 (erd.md §3.2 ck_log_payload_xor)
ALTER TABLE execution_log
    ADD CONSTRAINT ck_log_payload_xor
    CHECK (
        (payload_format = 'JSON' AND request_payload_xml IS NULL AND response_payload_xml IS NULL)
        OR (payload_format = 'XML'  AND request_payload IS NULL AND response_payload IS NULL)
    );

-- =============================================================================
-- execution_log : UNIQUE / 인덱스 (ADR-004 + ADR-005)
-- =============================================================================
-- ADR-004: 동일 인터페이스의 RUNNING은 최대 1건 (advisory lock의 safety net)
-- partial UNIQUE 인덱스가 자동 B-tree를 생성하여 idx_log_running 대체
CREATE UNIQUE INDEX IF NOT EXISTS uk_log_running
    ON execution_log(interface_config_id)
    WHERE status = 'RUNNING';

-- ADR-004 §5: 한 부모당 자식 1건 (체인 분기 차단). UNIQUE 자동 B-tree로 체인 조회도 커버
-- JPA가 execution_log(parent_log_id)를 nullable BIGINT로 만들었기에 partial 불필요
-- (PostgreSQL UNIQUE는 NULL을 서로 다른 값으로 취급 → 원본 N건 공존 가능)
ALTER TABLE execution_log
    ADD CONSTRAINT uk_log_parent
    UNIQUE (parent_log_id);

-- ADR-005 Q2: 체인 루트 집계용 부분 인덱스. 자식 로그만 색인하여 인덱스 크기 절감
CREATE INDEX IF NOT EXISTS idx_log_root
    ON execution_log(root_log_id)
    WHERE root_log_id IS NOT NULL;

-- erd.md §4.2: 상태별 시계열 조회 (대시보드 최근 실패 N건, 페이지네이션)
CREATE INDEX IF NOT EXISTS idx_log_status_started
    ON execution_log(status, started_at DESC);

-- erd.md §4.2: 인터페이스별 이력 조회 (실행 이력 화면)
CREATE INDEX IF NOT EXISTS idx_log_config_started
    ON execution_log(interface_config_id, started_at DESC);
