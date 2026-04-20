# ADR-004: 동시 실행 중복 방지 전략

**상태**: 결정됨
**날짜**: 2026-04-20

---

## 결정 (먼저)

**C안 유지 — advisory lock(2-arg, 네임스페이스 주입형) + 부분 UNIQUE 제약 `uk_log_running` 2중 방어.** erd.md v0.4 §8.1의 기존 방향을 **번복하지 않는다**. 단, DB 제약 이름은 `uk_log_running`으로 확정하고 중복 인덱스 `idx_log_running`은 **제거**한다(UNIQUE가 자동 B-tree 인덱스를 생성하므로 §4.2 재처리 체인 사례와 동일 패턴).

---

## 컨텍스트

동일 `interface_config_id`에 대한 `POST /api/interfaces/{id}/execute` 동시 요청이 두 건의 `status=RUNNING` 로그를 만들면 (a) 외부 시스템 중복 호출, (b) 감사 로그 모호, (c) §8.3 고아 복구 쿨다운 오작동이 발생한다. 3개 후보(A: 상태 체크만, B: advisory lock만, D: UNIQUE만)에 대해 회의가 열렸고, @DBA와 @DevilsAdvocate가 **D안의 안전성**을 놓고 정반대 평가를 냈다.

---

## 근거

### D안의 상태 전이 타이밍 문제 — 기술 판정

**판정: @DevilsAdvocate가 옳다. D안은 유령 예외를 발생시키지 않는다. 단, 그 이유로 C안을 버릴 만큼 결정적이지는 않다.**

PostgreSQL READ COMMITTED에서 부분 UNIQUE 인덱스 `WHERE status='RUNNING'`의 실제 동작:

1. TX-A가 기존 `RUNNING` 로그를 `SUCCESS`로 UPDATE하고 **아직 커밋 전**.
2. TX-B가 동일 `interface_config_id`로 새 `RUNNING` INSERT 시도.
3. PostgreSQL은 UNIQUE 위반 후보를 발견하면 **즉시 23505를 던지지 않고** 해당 튜플의 `xmax` 트랜잭션 종료를 **XactLockTableWait**으로 대기한다(`_bt_check_unique` → `XactLockTableWait`).
4. TX-A 커밋 → 갱신된 행은 더 이상 `status='RUNNING'`을 만족하지 않아 **partial index predicate에서 탈락** → TX-B는 UNIQUE 충돌 없이 INSERT 성공.
5. TX-A 롤백 → 구 튜플 dead, TX-B 역시 성공.

따라서 DBA가 우려한 "정상 상태 전이 구간에 UNIQUE 위반이 500으로 튄다"는 시나리오는 **부분 인덱스 + READ COMMITTED 조합에서는 성립하지 않는다**. TX-B는 TX-A의 짧은 UPDATE 지연(ADR-001 §3의 짧은 TX2, p50 < 5ms)만큼만 블록된다.

**그러나 D안 단독 채택을 기각하는 별개 사유 3가지**:

1. **예외 기반 정상 흐름**: 23505가 발생하는 실제 케이스(ms 단위 동시 INSERT 2건)는 드물지만 존재. `ConstraintViolationException` → 409 변환 경로가 **유일한 방어선**이 되면 JPA 세션 오염(`EntityManager` flush 예외 후 rollback-only 전이), `@Transactional` 경계에서의 rethrow 처리, Hibernate vs 직접 SQL 경로 차이 등 **프레임워크 레이어 복잡도**가 증가한다. C안은 advisory lock 획득 실패를 `boolean false`로 받아 정상 분기하므로 예외 경로가 최후의 safety net으로만 남는다.
2. **멀티 인스턴스 확장성**: advisory lock은 PostgreSQL 전역이라 앱 인스턴스가 2대 이상으로 확장되어도 동일 키가 경합한다. UNIQUE 단독은 DB 제약에 의존하되 lock 대기가 xid 단위라 짧고 균일. 두 메커니즘은 **상호 보완**.
3. **감사 가시성**: @Security의 "차단 시도 감사 기록" 요구를 advisory lock 획득 실패 분기에서 깔끔히 수행 가능(UNIQUE 위반 catch보다 코드 응집도 높음).

결론: D의 MVCC 근거는 맞지만, D를 **단독**으로 쓸 근거는 안 된다. C는 D를 제약으로 **포함**한다.

---

## 트레이드오프

| 옵션 | 장점 | 단점 |
|---|---|---|
| A (상태 체크만) | 단순 | 5% race 실측, 감사 누락 |
| B (advisory lock만) | 키 기반 차단 명료 | `@Async` 구간 락 공백, TX 경계 벗어남 |
| D (UNIQUE만) | 스키마 수준 보증, 코드 간결 | 예외 기반 흐름, JPA 세션 오염 리스크, 감사 분기 지저분 |
| **C (advisory lock + UNIQUE)** | **정상 경로 깨끗 + safety net + 감사 분기 명료 + 다중 인스턴스 정합** | **오버헤드 ~2–3ms, 인덱스 1개** |

---

## 기각된 대안

- **A 단독**: 5% race 실측으로 불합격.
- **B 단독**: advisory lock이 TX 커밋 시 자동 해제 → `@Async` 구간 무방비. RUNNING 레코드가 실제 차단 주체여야 함.
- **D 단독 (DevilsAdvocate 원안)**: MVCC 분석은 정확하나, (1) 23505 → 409 변환 유일 경로화, (2) 감사 분기 응집도 저하, (3) 멀티 인스턴스 전환 시 advisory lock 재도입 비용. C가 D를 **포함**하므로 D의 모든 보증을 유지하면서 약점만 제거.

---

## 수용한 리스크

- **오버헤드 2–3ms/요청**: `pg_try_advisory_xact_lock` 1회 + partial UNIQUE index seek 1회. 수동 실행 TPS 목표(planning.md §3.2) 대비 무시 가능.
- **JVM crash 후 watchdog(5분) 복구 전까지 동일 인터페이스 차단**: 명세로 수용. erd.md §8.3 `OrphanRunningWatchdog` + `ApplicationReadyEvent` 복구로 상한 5분.
- **advisory lock namespace 충돌**: `ifms.advisory-lock.namespace` 주입형(api-spec.md §9.1)으로 운영 DB 공유 시 재할당 가능.

---

## 구현 제약

1. **평가 순서**: (a) advisory lock 시도 → 실패 시 즉시 409 `DUPLICATE_RUNNING` + `audit_log`에 `CONCURRENT_EXECUTION_BLOCKED` 기록. (b) 획득 성공 시 `SELECT 1 FROM execution_log WHERE interface_config_id=? AND status='RUNNING'` 확인 → 존재 시 409. (c) INSERT. (d) 만일 UNIQUE 위반 23505가 발생하면 `ConstraintViolationException` → 409 `DUPLICATE_RUNNING`으로 변환(safety net, 정상 경로 아님).
2. **DB 제약**: `execution_log`에 `CREATE UNIQUE INDEX uk_log_running ON execution_log(interface_config_id) WHERE status = 'RUNNING'` 추가. 기존 비-UNIQUE `idx_log_running`은 **제거**(UNIQUE가 B-tree 인덱스 자동 생성, 중복).
3. **advisory lock**: 2-arg 버전, key1=`ifms.advisory-lock.namespace`, key2=`interface_config_id`. TX 범위(`pg_try_advisory_xact_lock`)로 커밋/롤백 시 자동 해제.
4. **트랜잭션 경계**: advisory lock 획득 ~ INSERT ~ 커밋을 ADR-001 TX1과 동일 트랜잭션으로 묶는다. `@Async` 경계는 TX1 커밋 이후.
5. **재처리 경로 공유**: `POST /api/executions/{id}/retry`도 동일 2중 방어 경로 재사용. `RETRY_CHAIN_CONFLICT`(`uk_log_parent`)와 `DUPLICATE_RUNNING`(`uk_log_running`)은 별도 제약. **재처리 경로의 advisory lock은 2-도메인 분리** (`interface_config_id`·`parent_log_id`) — 상세는 ADR-005 §5.3 / api-spec §9.1.
6. **감사 로그**: advisory lock 실패 시 `audit_log`(actor·ip·target_interface_id·blocker_log_id)에 기록. 23505 safety-net 경로도 별도 `code=POST_HOC_UNIQUE_BLOCK`으로 구분 기록(회귀 탐지 신호).

---

## erd.md 동기화 필요성

**있음 — v0.5로 갱신 필요**.

- §4.2: `idx_log_running` 정의를 **삭제**하고 §3.2 테이블 정의에 `UNIQUE (interface_config_id) WHERE status='RUNNING'` 제약(`uk_log_running`)을 추가. §4.3·§5 Q7의 "partial index seek" 표기는 `uk_log_running`으로 대체.
- §8.1 플로우 다이어그램의 "SELECT ... status='RUNNING'" 단계는 유지(정상 경로 방어선). 하단 주석에 "23505 safety net 경로"를 추가.
- §13 체크리스트: "uk_log_running 중복 INSERT 시 2번째 요청 DB 제약 위반" 항목 신설, "idx_log_running 부분 인덱스" 항목 제거.
- v0.5 변경 로그 작성.

api-spec.md §4.5 `DUPLICATE_RUNNING` 스키마는 변경 없음(응답 계약 동일).

---

## 관련 문서

- `docs/erd.md` v0.4 §4.2 §8.1 §8.3 (→ v0.5 동기화 예정)
- `docs/adr/ADR-001-execution-log-transaction.md` (TX1/TX2 분리, 본 ADR의 트랜잭션 경계 전제)
- `docs/api-spec.md` §1.3 §4.5 (409 `DUPLICATE_RUNNING` 응답), §9.1 (advisory lock 네임스페이스 주입)
- `docs/planning.md` §3.2 §5.5 (TPS 목표, @Async 풀)
- ADR-005 재처리 최대 횟수 정책 (Day 3 예정, `uk_log_parent`와의 상호작용 참조)
