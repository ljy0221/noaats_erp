# ADR-001: ExecutionLog 트랜잭션 범위

**상태**: 결정됨
**날짜**: 2026-04-20

---

## 컨텍스트

수동 실행 트리거(`POST /api/interfaces/{id}/execute`) 요청 처리 시, `ExecutionLog` INSERT 시점과 Mock 실행 구간(`Thread.sleep` 포함)을 **같은 트랜잭션으로 묶을지 분리할지** 결정해야 한다. 이 선택은 감사 누락 확률, 수동 실행 TPS, SSE 실시간성, 고아 `RUNNING` 발생 빈도, 동시 실행 차단(ADR-004) 구현 가능성에 동시에 영향을 준다.

검토된 3개 옵션:
- **A안**: 단일 TX — Mock 실행 완료까지 트랜잭션 유지 후 일괄 커밋
- **B안**: 2-TX 분리 — TX1(RUNNING INSERT + 커밋) → @Async 스레드 → TX2(SUCCESS/FAILED UPDATE)
- **C안**: 비트랜잭션 — INSERT도 Mock 밖에서 non-TX로 처리

---

## 결정

**B안 (2-TX 분리) 채택.** @DevilsAdvocate의 6개 안전장치 중 4개를 필수 제약으로 편입하되, 2개는 기존 확정 문서(`planning.md §5.5`, `erd.md §8.3`)와의 일관성을 위해 조율한다.

---

## 근거

1. **감사 누락 0건 (Security MUST)**: A안은 Mock 중 JVM crash 시 RUNNING 기록 전체 소실 → 금융 감사요건 위반. B안은 TX1 커밋 직후부터 감사 추적 가능.
2. **성능 10배 차이 (DBA 정량)**: Mock 평균 1초 기준 A안은 커넥션 점유로 초당 10건 한계, B안은 초당 1000건+. 수동 실행 TPS 목표(planning.md §3.2) 달성 가능.
3. **SSE 실시간성 (DBA)**: A안은 RUNNING이 커밋 전이라 `afterCommit` 훅에서 emit 불가. B안은 TX1 커밋 직후 `RUNNING` 이벤트를 즉시 브로드캐스트할 수 있다.
4. **동시 실행 차단 정합성 (erd.md §8.1)**: `idx_log_running` 부분 인덱스와 advisory lock 조합은 RUNNING 레코드가 **즉시 가시화**되어야 성립. A안은 불가.
5. **고아 RUNNING 부하 무시 가능**: 연 0.1건 추정, `OrphanRunningWatchdog`(erd.md §8.3) 5분 주기로 이미 해소.

---

## 트레이드오프

| 옵션 | 장점 | 단점 |
|---|---|---|
| A (단일 TX) | 구현 단순, 고아 RUNNING 0건 | 감사 누락 리스크, TPS 10건 한계, SSE 불가 |
| **B (2-TX)** | **감사 무결, 1000 TPS+, SSE 실시간, 차단 정합성** | **@Async 풀 튜닝·self-invocation·sleep 위치 주의 필요** |
| C (non-TX) | 최고 성능 | UPDATE 스킵 시 감사 붕괴, ACID 포기 |

**장점**: 감사·성능·실시간성·중복차단 4개 요구가 동시 충족.
**수용 리스크**: 아래 "수용한 리스크" 섹션 참조.

---

## 기각된 대안

- **A안 (단일 TX)**: JVM crash 시 감사 전체 소실은 금융 IT에서 수용 불가. Mock 1초 동안 DB 커넥션 점유 → 풀(HikariCP default 10) 고갈 리스크. SSE `RUNNING` emit 불가로 "실시간 모니터링" 기획 목표(planning.md F-10~F-12) 미달.
- **C안 (비트랜잭션)**: Mock 중 예외 유실 시 UPDATE 스킵 → RUNNING이 영구 고아. B안 대비 성능 이득이 미미하고 정합성 손실이 과도.

---

## 수용한 리스크

- **고아 RUNNING 레코드 발생 가능성**: 연 0.1건 추정. `OrphanRunningWatchdog`(5분 주기, `timeout_seconds+60s` JOIN 공식, erd.md §8.3)로 자동 복구. `ApplicationReadyEvent` 시작 시 1회 전수 복구 병행.
- **self-invocation 버그 리스크**: 클래스 분리(아래 제약 1)로 원천 차단. 통합 테스트 필수.
- **큐 포화 시 호출 스레드 블로킹**: `CallerRunsPolicy` 채택으로 요청은 유실되지 않지만 최악의 경우 Controller 스레드가 Mock을 직접 실행 → 레이턴시 급증. 모니터링 알람으로 감지(운영 이관 시 조치).

---

## 구현 제약 (필수)

1. **클래스 분리 (DevilsAdvocate-1 채택)**: `ExecutionTriggerService`(TX1, INSERT) ↔ `AsyncExecutionRunner`(@Async, TX2, UPDATE) 별도 Bean으로 분리하여 self-invocation으로 인한 프록시 우회·트랜잭션/비동기 미적용을 차단한다.
2. **@Async 스레드풀 파라미터**: `corePoolSize=4, maxPoolSize=8, queueCapacity=50, CallerRunsPolicy` 를 **유지한다** (planning.md §5.5 확정값). DevilsAdvocate-2의 `core=5/max=10/queue=20 + AbortPolicy + 503` 제안은 **기각**. 근거: (a) 프로토타입 단계에서 503 반환은 F-04(수동 실행) 성공률 목표를 위협, (b) 요청 유실 방지가 감사 추적보다 우선, (c) 수정 시 §5.5·`mock-executor.md` 동기 수정 비용 발생. 단, 큐 포화 알람(`ThreadPoolExecutor.getQueue().size() > 40`)은 추가한다.
3. **Mock `Thread.sleep`은 TX2 바깥 (DevilsAdvocate-4 채택, DBA-2 조율)**: DBA의 "@Async + @Transactional 자동 분리" 의견은 "TX1/TX2가 분리된다"는 뜻이지 "TX2 내부에서 sleep 해도 안전"이란 뜻이 아니다. 장시간 sleep이 TX2 안에 들어가면 UPDATE 구간 커넥션 점유로 성능이 다시 A안 수준으로 회귀한다. 따라서 `AsyncExecutionRunner` 내부에서 **Mock 호출(sleep 포함)은 non-TX 메서드로 수행**하고, **결과 객체만 받아 짧은 TX2로 UPDATE**한다.
4. **payload 마스킹은 TX2 진입 전 완료 (DevilsAdvocate-5)**: 마스킹 미적용 원본이 DB에 영속화되지 않도록 `ExecutionResult` 생성 지점에서 마스킹 파이프라인(erd.md §3.2.1) 수행 후 UPDATE 수행.
5. **OrphanRunningWatchdog 필수 (DevilsAdvocate-6)**: erd.md §8.3의 5분 주기 스케줄러 + `ApplicationReadyEvent` 복구 핸들러를 Day 2 내 구현. ADR 승인 조건.
6. **재처리 독립 TX**: `POST /api/executions/{logId}/retry`는 체인 루트를 참조만 하고 새 `RUNNING` 레코드를 별도 TX1으로 생성 (`parent_log_id` 세팅). 재처리 실패가 원본 로그 상태에 영향 주지 않도록 완전 독립.
7. **SSE emit 타이밍**: TX1 커밋 후 `TransactionSynchronization.afterCommit`에서 `RUNNING` emit. TX2 커밋 후 동일 훅으로 `SUCCESS/FAILED` emit. TX 내부 emit 금지(롤백 시 유령 이벤트).

---

## 관련 문서

- `docs/planning.md` v0.3 §5.5 (비동기 실행 흐름, 스레드풀 파라미터)
- `docs/erd.md` v0.4 §3.2 (`execution_log` 스키마, 상태 전이 무결성), §6 (CHECK 제약), §8.1 (동시 실행 차단 + advisory lock), §8.3 (고아 RUNNING 복구)
- `.claude/skills/mock-executor.md` (AsyncConfig, MockExecutor 구현)
- ADR-004 (동시 실행 중복 방지 전략 — Day 3 예정)
- ADR-005 (재처리 최대 횟수 정책 — Day 3 예정)
