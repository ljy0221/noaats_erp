# ADR-005: 재처리 정책 (max_retry 효력 시점·체인 루트 식별·INACTIVE 처리·평가 순서)

**상태**: 결정됨
**날짜**: 2026-04-20

---

## 결정 (먼저)

| 쟁점 | 결정 | 한 줄 근거 |
|---|---|---|
| **Q1** `max_retry_count` 효력 시점 | **C안 — `execution_log.max_retry_snapshot` 컬럼 (체인 루트 INSERT 시점 스냅샷)** | PATCH–retry race(`@Version`은 PATCH만 보호)로 옛 정책 통과 retry가 신 정책 박제되는 위협 차단 + append-only 감사 무결성 일관 유지 |
| **Q2** 체인 루트 식별 | **C안 — `execution_log.root_log_id` 비정규화 컬럼 (`COALESCE(parent.root_log_id, parent.id)`)** | 부모 1단계로는 멀티홉 체인의 루트 actor_id 검증(api-spec §5.3) 우회 가능, RECURSIVE는 30× 비용·JPA 비호환 |
| **Q3** INACTIVE 인터페이스 재처리 | **A안 — 차단 (`INTERFACE_INACTIVE` 5순위 그대로)** | 격리 채널 재가동은 전금감 §15 통제 우회, 정책 분기 단순화 |
| **Q4** `RETRY_CHAIN_CONFLICT` vs `DUPLICATE_RUNNING` 평가 순서 | **A안 — advisory lock 우선, lock key 2-도메인(`interface_config_id`·`parent_log_id`) 분리** | ADR-004 패턴 재사용, JPA 세션 오염 회피, 감사 분기 응집 |

---

## 컨텍스트

ADR-001/004 + erd.md/api-spec.md에서 재처리 골격은 결정되었으나 4개 미결 쟁점이 남았다:

1. 운영자가 PATCH로 `max_retry_count`를 변경했을 때 진행 중 체인이 따르는 값(스냅샷 vs 라이브)
2. 체인 "루트"의 정의가 필요한 검증(actor_id, max_retry)에서 루트를 찾는 방법
3. 인터페이스가 `INACTIVE`로 전환되었을 때 진행 중 체인의 재처리 허용 여부
4. 동일 부모에 대한 동시 재처리 요청이 들어왔을 때 평가 순서

각 쟁점은 **위협 모델(보안)·정합성(DB)·운영 직관(UX)·일정 현실(프로토타입)**의 4축에서 트레이드오프가 다르다.

---

## 근거

### Q1. 스냅샷 컬럼 (C안) 채택

@DBA는 라이브 옵션(B)이 `interface_config` 페이지 캐시 100% 상주·JOIN 비용 0이라 운영자 직관에 부합한다고 주장. 그러나 @DevilsAdvocate가 제기한 **PATCH–retry race**는 기술적 사실이다:

1. TX-A가 `UPDATE interface_config SET max_retry_count=2, version=version+1` 커밋 직전.
2. TX-B(retry)는 동일 행을 `SELECT`만 함 → `@Version` 낙관락은 TX-A를 보호하지만 TX-B는 막지 않음.
3. TX-B는 옛 max=7 기준으로 `retry_count=4`인 체인을 통과시켜 INSERT.
4. TX-A 커밋 후 운영자 화면은 "한도 초과인데 RUNNING 살아있음" 모순 박제.

해결책으로 (a) B′ `SELECT ... FOR SHARE`는 ADR-004 advisory lock 도메인과 충돌, (b) C′ Optimistic은 결국 스냅샷과 등가 비용 + 구현 복잡, (c) 스냅샷은 **체인 생성 시점 정책의 결정론적 보존**이라는 append-only 감사 무결성 원칙(ADR-001/003/004 일관)과 정렬.

스토리지 비용은 1주일 프로토타입 운영 기준 0에 수렴(연 18만 재처리 가정해도 ~3.6MB).

### Q2. `root_log_id` 비정규화 (C안) 채택

@DBA는 부모 1단계(A)로 충분하다고 평가했으나 **api-spec §5.3 line 596 "원본 로그의 `actor_id` 매칭"은 부모 actor_id가 아니라 루트 actor_id 기준**이다. 멀티홉 체인에서 부모 actor_id ≠ 루트 actor_id인 경우(`SYSTEM`/`ANONYMOUS_*` 예외 분기 모두 루트 기준)가 발생하므로 A안은 **체인 소유권 모델을 무력화**한다.

옵션 B(WITH RECURSIVE)는 30× 비용 + 플래너 회귀 위험 + JPA 우회 강제. 프로토타입 부적합.

옵션 C(`root_log_id` 비정규화)는:
- INSERT 시 `COALESCE(parent.root_log_id, parent.id)` — 부모 row를 어차피 fetch하므로 추가 비용 0
- 모든 검증(actor_id, max_retry_snapshot, retry_count)을 **단일 SELECT**로 해결
- `idx_log_root` 부분 인덱스 1개로 체인 전체 집계 커버

### Q3. INACTIVE 차단 (A안) 만장일치

세 에이전트 모두 차단 권고. 격리 채널 재가동(전금감 §15 통제 우회), api-spec §1.3 5순위 `INTERFACE_INACTIVE`의 의도(신규/재처리 모두 차단), 정책 분기 단순화(공격 표면 축소) 일치.

운영자의 "장애 복구 중 일시 INACTIVE → 긴급 재처리" 시나리오는 `MAINTENANCE` 별도 상태로 Future Work 분리.

### Q4. advisory lock 우선 (A안) 만장일치 + lock 도메인 분리

ADR-004 §C 패턴 그대로 재사용. uk_log_parent INSERT 우선 평가는 (a) 23505 catch → JPA 세션 오염, (b) audit 분기 분산, (c) ADR-004 결정 모순 → 기각.

@DevilsAdvocate가 제안한 **lock key 2-도메인 분리**(`interface_config_id` vs `parent_log_id`)를 채택:
- 일반 실행 동시성과 재처리 동시성을 독립 lock 도메인으로 처리
- 동일 인터페이스의 일반 실행과 재처리가 서로 차단하지 않음
- 두 lock은 항상 **동일 순서로 획득**(`interface_config_id` → `parent_log_id`) → deadlock 방지

---

## 트레이드오프

### Q1
| 옵션 | 장점 | 단점 |
|---|---|---|
| A. 스냅샷 단독 | 위협 모델 일치, 감사 무결, retry SELECT 0추가 | 0회→3회 상향 시 기존 체인 동결 |
| B. 라이브 단독 | 운영자 직관, 스토리지 0 | **PATCH–retry race로 옛 정책 통과** |
| B′. 라이브 + FOR SHARE | race 차단 | ADR-004 lock 도메인 충돌 |
| **C. 스냅샷 컬럼 (채택)** | A의 모든 이점 + 결정론적 감사 | 컬럼 1개, 스토리지 ~3.6MB/년 |

### Q2
| 옵션 | 장점 | 단점 |
|---|---|---|
| A. 부모 1단계 | 단순 | **루트 actor_id 검증 우회 (RETRY_FORBIDDEN_ACTOR 무력화)** |
| B. WITH RECURSIVE | 항상 정확 | 30× 비용, 플래너 회귀, JPA 비호환 |
| **C. `root_log_id` 비정규화 (채택)** | O(1) 조회, 단일 SELECT 검증 | 컬럼 1개, 인덱스 1개 |

### Q3·Q4: 만장일치 — 별도 표 생략

---

## 기각된 대안

- **Q1-B (라이브 단독)**: PATCH–retry race 사실. DBA의 "INACTIVE 체크에서 같은 row 읽으니 비용 0" 논거는 맞지만, 그 SELECT가 락이 아니므로 race 창은 존재.
- **Q1-B′ (라이브 + FOR SHARE)**: ADR-004 advisory lock과 lock 도메인 중첩, 운영자 PATCH 일시 블록.
- **Q2-A (부모 1단계)**: api-spec §5.3 루트 actor_id 검증 위반. @DBA가 "범위 밖"이라 평가한 것은 사실 오인.
- **Q2-B (WITH RECURSIVE)**: 비용·JPA 호환성 모두 부적합.
- **Q4-B (uk_log_parent INSERT 우선)**: ADR-004 §"D안 단독 기각 사유" 그대로 적용 — 예외 정상흐름화 안티패턴.

---

## 수용한 리스크

- **Q1-C 동결 효과**: `max_retry_count`를 0→3 상향 후에도 기존 체인의 `max_retry_snapshot=0`은 그대로 → 신규 원본 실행부터만 새 정책 적용. UI 툴팁 "이 체인은 등록 시점 정책(max=N)으로 평가됩니다" 명시.
- **Q1-C 운영자 인지 부담**: PATCH 변경이 활성 체인에 즉시 미반영. Future Work로 "활성 체인 정책 분포" 대시보드 패널.
- **Q2-C INSERT 시 부모 read 필수**: JPA에선 어차피 부모 fetch 중이므로 추가 비용 0. 부모가 `ON DELETE SET NULL`로 단절되어도 자식의 `root_log_id`는 보존(이미 비정규화).
- **Q2-C 스토리지**: 컬럼 1개(BIGINT 8B) + `idx_log_root` 부분 인덱스 → ~14MB/년 추가.
- **Q4-A safety net 분기 2종**: `uk_log_running` 23505 → `POST_HOC_RUNNING` audit, `uk_log_parent` 23505 → `POST_HOC_CHAIN` audit. 회귀 탐지 신호 분리.

---

## 구현 제약 (필수)

### 1. 스키마 변경 (erd.md §3.2)

```sql
ALTER TABLE execution_log
    ADD COLUMN max_retry_snapshot INT    NOT NULL,
    ADD COLUMN root_log_id        BIGINT;
ALTER TABLE execution_log
    ADD CONSTRAINT ck_log_max_retry_snapshot
        CHECK (max_retry_snapshot BETWEEN 0 AND 10);
ALTER TABLE execution_log
    ADD CONSTRAINT fk_log_root
        FOREIGN KEY (root_log_id) REFERENCES execution_log(id) ON DELETE SET NULL;
CREATE INDEX idx_log_root ON execution_log(root_log_id) WHERE root_log_id IS NOT NULL;

COMMENT ON COLUMN execution_log.max_retry_snapshot IS
    '체인 루트 INSERT 시점의 interface_config.max_retry_count 스냅샷. PATCH 후행 적용 차단 (ADR-005)';
COMMENT ON COLUMN execution_log.root_log_id IS
    '체인 루트 로그 ID. 원본은 NULL, 재처리는 COALESCE(parent.root_log_id, parent.id) (ADR-005)';
```

### 2. Service 레이어 불변식

- **원본 INSERT** (MANUAL/SCHEDULER): `max_retry_snapshot = ic.max_retry_count`, `root_log_id = NULL`.
- **재처리 INSERT** (RETRY): `max_retry_snapshot = parent.max_retry_snapshot` (루트에서 캐스케이드), `root_log_id = COALESCE(parent.root_log_id, parent.id)`.
- **단일 SELECT 검증** (advisory lock 획득 후, FOR UPDATE 불필요):
  ```sql
  SELECT p.id, p.actor_id, p.retry_count, p.max_retry_snapshot, p.payload_truncated, p.status,
         COALESCE(p.root_log_id, p.id) AS root_id,
         (SELECT actor_id FROM execution_log WHERE id = COALESCE(p.root_log_id, p.id)) AS root_actor_id,
         ic.status AS ic_status
  FROM execution_log p
  JOIN interface_config ic ON ic.id = p.interface_config_id
  WHERE p.id = :parentId;
  ```
- 검증 분기:
  - `ic_status <> 'ACTIVE'` → 409 `INTERFACE_INACTIVE` (Q3)
  - `root_actor_id`와 세션 actor_id 매칭 (api-spec §5.3 예외 규칙 포함) → 403 `RETRY_FORBIDDEN_ACTOR` (Q2)
  - `p.retry_count + 1 > p.max_retry_snapshot` → 409 `RETRY_LIMIT_EXCEEDED` (Q1, body에 `{"limit", "current"}`)
  - `p.payload_truncated` → 409 `RETRY_TRUNCATED_BLOCKED`
  - `p.status <> 'FAILED'` → 400/409 (LEAF·SUCCESS 분기)

### 3. 동시성 (Q4)

- **lock key 2-도메인**:
  - `pg_try_advisory_xact_lock(:namespace, :interface_config_id)` — 일반 실행 차단(ADR-004)
  - `pg_try_advisory_xact_lock(:namespace, :parent_log_id)` — 재처리 체인 차단 (본 ADR)
- **획득 순서**: `interface_config_id` lock → `parent_log_id` lock (deadlock 방지)
- **평가 순서**:
  1. advisory lock(`interface_config_id`) 시도 → 실패 시 409 `DUPLICATE_RUNNING` + audit `CONCURRENT_EXECUTION_BLOCKED`
  2. advisory lock(`parent_log_id`) 시도 → 실패 시 409 `RETRY_CHAIN_CONFLICT` + audit `RETRY_CHAIN_BLOCKED`
  3. 위 단일 SELECT로 정책 검증
  4. INSERT
  5. safety net: 23505 of `uk_log_running` → `DUPLICATE_RUNNING` + `POST_HOC_RUNNING`, 23505 of `uk_log_parent` → `RETRY_CHAIN_CONFLICT` + `POST_HOC_CHAIN`

### 4. 코드 분리

- `RetryService` (별도 Bean, ADR-001 §6 독립 TX 원칙 준수). lock·검증 응집 → `RetryGuard` 컴포넌트로 추출.
- `ExecutionLog.spawnRetry(actor, parent)` 정적 팩토리 — 위 불변식을 한 곳에서만 강제, JPA EntityListener 도입 금지(ADR-006 정신 일관).

---

## 문서 동기화 필요성

### erd.md (→ v0.6)
- §3.2 테이블 정의: `max_retry_snapshot`, `root_log_id` 컬럼 추가, `ck_log_max_retry_snapshot` CHECK 추가, `fk_log_root` FK 추가, `idx_log_root` 부분 인덱스 추가
- §3.2 라인 220: "체인 루트(원본)의 `interface_config.max_retry_count`" → "**체인 루트의 `execution_log.max_retry_snapshot`** (등록 시점 스냅샷, ADR-005)"
- §3.2 라인 219 직후 "체인 루트 식별: `COALESCE(self.root_log_id, self.id)` 단일 컬럼 비정규화" 보강
- §3.2 "재처리 제한 규칙" 블록(라인 226~): `INTERFACE_INACTIVE` 명시 추가
- §13 체크리스트: `max_retry_snapshot` 캐스케이드 단위 테스트, `root_log_id` 멀티홉 actor 검증 테스트 추가
- §14 v0.6 변경 로그

### api-spec.md (→ v0.7)
- §5.3 라인 571: "`retry_count < max_retry_count`" → "`retry_count < max_retry_snapshot` (체인 루트 등록 시점 스냅샷)"
- §5.3 라인 596: "원본 로그의 `actor_id`" → "**체인 루트의 `actor_id`** (`COALESCE(self.root_log_id, self.id)` 기준)"
- §5.3 라인 601 응답 본문: `{"errorCode": "RETRY_LIMIT_EXCEEDED", "limit": <max_retry_snapshot>, "current": <retry_count>}`
- §1.3 에러 코드 표: `RETRY_CHAIN_CONFLICT` 평가 순서를 "advisory lock(`parent_log_id`) 실패 시 우선" 명시
- §1.3 5순위 `INTERFACE_INACTIVE`: 재처리 경로에도 동일 적용 명시
- §9.1 advisory lock 네임스페이스 절: lock key 2종(`interface_config_id` / `parent_log_id`) 분리 + 획득 순서(`interface_config_id` → `parent_log_id`) 명시

### ADR 정합성
- **ADR-001 §6** (재처리 독립 TX·체인 루트 참조): 모순 없음. 본 ADR-005가 "루트"의 정의를 `root_log_id` 비정규화로 확정.
- **ADR-004 §5** (`uk_log_parent` vs `uk_log_running` 별도 제약): 모순 없음. 본 ADR Q4가 §C 패턴을 그대로 확장. ADR-004 §5에 "lock key 2-도메인 분리" 1줄 보강 권장.
- **ADR-003** (JSONB payload), **ADR-006** (validator 위치): 무관.

### Future Work (본 ADR 범위 외)
- INACTIVE 격리와 별개로 운영자 일시 점검용 `MAINTENANCE` 상태 분리
- `max_retry_snapshot` 정책 변경의 활성 체인 가시화 대시보드 패널
- Role=ADMIN 도입 시 actor 검증 무관 재처리 허용 (api-spec §5.3 라인 608 기존 명시)

---

## 관련 문서

- `docs/adr/ADR-001-execution-log-transaction.md` §6 (재처리 독립 TX, parent_log_id 세팅)
- `docs/adr/ADR-004-concurrent-execution-prevention.md` §5 (재처리 경로 공유, uk_log_parent 별도 제약)
- `docs/erd.md` v0.5 §3.2 라인 219·220 (재처리 누적 규칙·체인 루트 max_retry 비교) → v0.6 동기화 예정
- `docs/api-spec.md` v0.6 §1.3 §5.3 §9.1 → v0.7 동기화 예정
- `docs/planning.md` §13 ADR-005 회의 항목 (소집 완료)
