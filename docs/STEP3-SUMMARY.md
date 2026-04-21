# Day 3 완료 요약 — 2026-04-20

> Day 1·2·3 통합 산출물 대시보드. Day 4 착수 전 참조용.

---

## 1. 문서 산출물 (Day 3 추가)

| 문서 | 변경 | 합의 |
|---|---|---|
| [adr/ADR-005-retry-policy.md](adr/ADR-005-retry-policy.md) | **신규 확정** (Q1=C, Q2=C, Q3=A, Q4=A) | 4-에이전트 회의: Q3·Q4 만장일치, Q1·Q2 충돌 → Architect 판정 (옵션 C 채택) |
| [erd.md](erd.md) v0.10 | `max_retry_snapshot`·`root_log_id` 컬럼·CHECK·FK·idx_log_root 추가, 재처리 체인 규칙 6항 갱신, §13 체크리스트 5건 추가 | ADR-005 동기화 |
| [api-spec.md](api-spec.md) v0.7 | §1.3 우선순위·§5.3 재처리 본문·§9.1 lock 2-도메인 분리 | ADR-005 동기화 |
| [adr/ADR-004-concurrent-execution-prevention.md](adr/ADR-004-concurrent-execution-prevention.md) §5 | "lock key 2-도메인 분리" 1줄 보강 | ADR-005 Q4 cross-link |
| [DAY2-SUMMARY.md](DAY2-SUMMARY.md) | (변경 없음, 본 파일이 누적 대체) | |

---

## 2. 코드 산출물 (Day 3)

### 2-A 스키마

- [backend/src/main/resources/schema.sql](../backend/src/main/resources/schema.sql) — `execution_log` CHECK 8종 + `uk_log_running` partial UNIQUE + `uk_log_parent` UNIQUE + `idx_log_root` partial + `idx_log_status_started` + `idx_log_config_started`

### 2-B 도메인 (execution 패키지 신규)

| 카테고리 | 파일 | 역할 |
|---|---|---|
| domain | `TriggerType`, `ExecutionStatus`, `PayloadFormat`, `ExecutionErrorCode` | enum 4종 |
| domain | `ExecutionResult` | Mock→Service 경계 record |
| domain | **`ExecutionLog`** | append-only Entity, `start()`/`spawnRetry()`/`complete()`/`markRecovered()` 정적 팩토리 + 비즈니스 메서드 |
| repository | **`ExecutionLogRepository`** | 8개 쿼리 (advisory lock, RetryGuard 단일 SELECT, OrphanRunning native 등) |
| dto | `ExecutionTriggerResponse` | 201 응답 record (OffsetDateTime KST) |

### 2-C Mock 실행기 (8파일)

- `MockExecutor` (SPI) + `MockExecutorSupport` (헬퍼) + `MockExecutorFactory` (라우터)
- **5 프로토콜 구현체**: `RestMockExecutor` (15% 실패), `SoapMockExecutor` (8% XML), `MqMockExecutor` (5%), `BatchMockExecutor` (3%, 1~3s), `SftpMockExecutor` (10%)

### 2-D Service 레이어 (ADR-001 2-TX + ADR-005)

| 파일 | 책임 | ADR 정렬 |
|---|---|---|
| `PayloadFormatResolver` | ProtocolType→PayloadFormat 매핑 | erd ck_log_payload_xor |
| `ExecutionTriggerService` | **TX1** — advisory lock + RUNNING INSERT + afterCommit RUNNING SSE | ADR-001 §6 - 1·7 |
| `AsyncExecutionRunner` | `@Async` 진입점 — Mock 호출 + 마스킹 (non-TX) | ADR-001 §6 - 2·3·4 |
| `ExecutionResultPersister` | **TX2** — UPDATE + afterCommit SUCCESS/FAILED SSE | ADR-001 §6 - 1·7 (self-invocation 차단) |
| `RetryGuardSnapshot` | ADR-005 단일 SELECT 결과 record | ADR-005 §5.2 |
| `RetryGuard` | 5종 코드 평가 (FORBIDDEN_ACTOR/INACTIVE/NOT_LEAF/TRUNCATED/LIMIT) | ADR-005 §5.3 |
| `RetryService` | 재처리 트리거 — lock 2-도메인 + Guard + spawnRetry | ADR-005 Q1·Q2·Q3·Q4 |
| `OrphanRunningWatchdog` | 5분 sweep + ApplicationReadyEvent 일괄 복구 | erd §8.3, ADR-001 §6 - 5 |

### 2-E 인프라

- `AsyncConfig` — EXECUTION_POOL `core=4 max=8 queue=50 CallerRuns` (planning §5.5)
- `AdvisoryLockProperties` (record) — `namespace` + `retryNamespace` 분리 (ADR-005 Q4)
- `ActorContext` — Day 4 SecurityConfig 본 구현까지 `ANONYMOUS_LOCAL` 반환 stub
- `ExecutionEventPublisher` — Day 4 SseEmitterService 이관 대비 호출 지점 stub (4종 emit)

### 2-F Controller

- [`InterfaceController.execute`](../backend/src/main/java/com/noaats/ifms/domain/interface_/controller/InterfaceController.java) — 501 stub → 201 실 구현 교체
- [`ExecutionController.retry`](../backend/src/main/java/com/noaats/ifms/domain/execution/controller/ExecutionController.java) — `POST /api/executions/{id}/retry` 신규

### 2-G 엔트리포인트

- `IfmsApplication` — `@EnableScheduling` + `@ConfigurationPropertiesScan` 추가, `@EnableAsync` 유지

---

## 3. 빌드 + 실 기동 검증

### 빌드
- `compileJava` BUILD SUCCESSFUL (Gradle 8.10.2, JDK 17, Spring Boot 3.3.5)
- 신규/수정 파일 23개

### 실 기동 (PostgreSQL 16-alpine + bootRun)
- `Started IfmsApplication in 7.189 seconds`
- `AsyncConfig EXECUTION_POOL initialized: core=4 max=8 queue=50 policy=CallerRuns`
- `ApplicationReadyEvent: 잔재 RUNNING 없음` (Watchdog OK)
- Hibernate가 `execution_log` 테이블 + 3개 FK 자동 생성, schema.sql이 CHECK·UNIQUE·인덱스 후행 보강

### 엔드포인트 검증

| 시나리오 | 결과 |
|---|---|
| `POST /api/interfaces/1/execute` | **201** + `{logId, status:"RUNNING", triggeredBy:"MANUAL", startedAt:"...+09:00"}` |
| 동시 호출 2건 (백그라운드 chain) | 1번째 **201**, 2번째 **409 DUPLICATE_RUNNING** ✅ |
| 콘솔 SSE-stub 로그 | TX1 INSERT → `EXECUTION_STARTED` → 200~800ms 후 `EXECUTION_SUCCESS`/`_FAILED` |

---

## 4. Day 3 디버깅 기록 (실 기동 발견 버그 2건)

### Bug A — `pg_try_advisory_xact_lock(int, bigint)` 함수 부재

- **증상**: 첫 execute 호출 시 `SQLState 42883: function pg_try_advisory_xact_lock(integer, bigint) does not exist`
- **원인**: PostgreSQL 2-arg 시그니처는 `(int, int)`만 존재. JPA가 Long 파라미터를 BIGINT로 바인딩.
- **수정**: [ExecutionLogRepository.tryAdvisoryLock](../backend/src/main/java/com/noaats/ifms/domain/execution/repository/ExecutionLogRepository.java) 쿼리에 `CAST(:key2 AS INTEGER)` 명시. 운영 전환 시 1-arg bigint 인코딩(`(namespace << 32) | (id & 0xFFFFFFFFL)`)으로 전환 권장 — 백로그.

### Bug B — `Transaction synchronization is not active`

- **증상**: TX2 진입 시점 `IllegalStateException`. SSE-stub `EXECUTION_STARTED`는 정상이나 SUCCESS/FAILED 안 찍힘.
- **원인**: `AsyncExecutionRunner.runAsync()` → `this.persistResult()` self-invocation으로 Spring AOP 프록시 우회 → `@Transactional` 미적용.
- **수정**: TX2 본체를 [`ExecutionResultPersister`](../backend/src/main/java/com/noaats/ifms/domain/execution/service/ExecutionResultPersister.java) 별도 빈으로 분리. ADR-001 §6 - 1 "클래스 분리" 원칙을 AsyncRunner↔Persister에도 적용.

### Bug C — `LocalDateTime` + `XXX` 패턴 직렬화 실패

- **증상**: `HttpMessageNotWritableException: Unsupported field: OffsetSeconds`
- **원인**: `LocalDateTime`엔 offset 정보 없는데 `@JsonFormat(pattern="...XXX")`이 offset 출력을 요구.
- **수정**: `ExecutionTriggerResponse.startedAt`을 `OffsetDateTime`으로 변경, `from()` 팩토리에서 `Asia/Seoul` zone 부여. api-spec §1.4와 정합.

---

## 5. 주요 설계 결정 요약 (Day 3 추가)

| 영역 | 결정 | 근거 |
|---|---|---|
| 재처리 max_retry 효력 | **스냅샷 컬럼** `max_retry_snapshot` | PATCH–retry race 차단 (ADR-005 Q1) |
| 체인 루트 식별 | **`root_log_id` 비정규화** + `COALESCE` | 멀티홉 actor 검증 우회 차단 (Q2) |
| INACTIVE 인터페이스 재처리 | **차단** | 격리 채널 재가동 방지 (Q3) |
| advisory lock 도메인 | **2-domain** (`namespace`/`retry-namespace`) | 일반 실행과 재처리 lock 독립 (Q4) |
| SSE emit 타이밍 | TX 커밋 후 `afterCommit` only | ADR-001 §6 - 7 (롤백 시 유령 이벤트 방지) |
| TX2 분리 단위 | TriggerService↔AsyncRunner↔Persister **3-bean** | self-invocation AOP 우회 차단 (Bug B 학습) |
| Mock 실패율 | REST 15%, SOAP 8%, MQ 5%, BATCH 3%, SFTP 10% | 다양한 시나리오 + 대시보드 시각적 식별 |
| advisory lock SQL | `CAST(:key2 AS INTEGER)` | PostgreSQL `(int,int)` 시그니처 호환 (Bug A) |

---

## 6. Day 3 끝나도 남은 검증

| 항목 | 상태 | 책임 |
|---|---|---|
| `retry` 엔드포인트 실 호출 통합 테스트 | ⏳ Day 7 이월 | FAILED 로그 시드 후 Postman 시퀀스 |
| 재처리 체인 actor 우회 시나리오 단위 테스트 | ⏳ Day 7 이월 | erd §13 ADR-005 Q2 항목 |
| OrphanRunningWatchdog 5분 주기 동작 | ⏳ 운영 중 자연 발견 | 또는 Mock 무한 sleep 주입 테스트 |
| SSE 실 emit (현재 stub 로그만) | ⏳ Day 4 본 구현 | SseEmitterService |
| Mock JSON Map → JSONB 직렬화 정상 | ⏳ Day 7 이월 | Testcontainers (요청 본문에 마스킹 키 포함 시 검증) |
| ArchUnit 3종 (ADR-006 후속) | ⏳ Day 4 이월 | |

---

## 7. Day 4 착수 준비

Day 4 우선순위 (planning §11):
1. **SecurityConfig 완전판** — permitAll 임시 제거, 세션 기반 + SSE 필터
2. **SseEmitterService** + `Last-Event-ID` + 링버퍼 1,000건/5분 (ExecutionEventPublisher stub 교체)
3. **MonitorController** — `GET /api/monitor/stream` (SSE), 대시보드 집계 API
4. **ConnectionLimitFilter** — 세션 3 / 계정 10
5. **ArchUnit 3종 규칙** — Repository 주입, merge 금지, @Modifying 제한 (ADR-006 후속)
6. **`traceId` 통합 필터** — `OncePerRequestFilter`

상세 Day 4+ 이월 항목은 [backlog.md](backlog.md) 참조.

---

## 8. 누적 빌드 상태

**Day 1+2+3 통합 BUILD SUCCESSFUL** + 실 기동 검증 통과 (수동 실행 + 동시 차단 양호).

다음 세션은 Day 4 — SecurityConfig 본 구현 또는 Vue 스파이크 선행 중 사용자 결정 대기.
