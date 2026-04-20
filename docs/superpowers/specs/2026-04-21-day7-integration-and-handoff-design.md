# Day 7 — 통합 테스트 · 부채 청산 · 제출 패키징 (Design Spec)

> 신규 기능 설계가 아닌, **DAY6-SUMMARY §9-2 Known Issues + backlog.md §"Day 7" 항목의 청산 + 제출 핸드오프** 작업.
> 사용자 승인 흐름: 묶음 1·2 자동 진행 → 묶음 3 사용자 협조 단계로 핸드오프.

---

## 1. 목적

1주일 프로토타입(노아에이티에스 공채 15기 과제)의 **마지막 통합·정리** 단계. Day 6까지 기능 구현은 완료(`DAY6-SUMMARY.md`). 본 문서는:
- 인지된 부채(M1~M9) 중 **자동화 가능한 코드 수정**을 모두 처리한다
- **자동화 가능한 통합 테스트**를 추가한다 (Testcontainers · RetryService · Watchdog · Snapshot parity · Masking 벤치 · Race)
- **문서·코드 정합**을 정리한다 (api-spec ErrorCode 21종, Jackson 정책, ApiResponse Javadoc)
- **사용자 협조가 필요한 검증**(수동 E2E 8 + Day 5 회귀 5 + Swagger 회귀)은 핸드오프 형태로 분리한다
- 최종 `DAY7-SUMMARY.md` + 제출용 README를 작성한다

---

## 2. 범위

### 2-A 묶음 1 — 자동화된 코드 부채 청산 (자동 진행)

| ID | 항목 | 출처 | 예상 변경 |
|---|---|---|---|
| 1.1 M5 | Dashboard 카드/리스트 클릭 → `/history?status=FAILED` 드릴다운 | DAY6 §9-2 M5 | `Dashboard.vue` router-link 추가, `ExecutionHistory.vue` query 동기화 |
| 1.2 M7 | 새 탭 첫 방문 `/login`에서 sessionStorage clientId 정리 누락 | DAY6 §9-2 M7 | `auth.logout()` 내부로 clientId 정리 이동, router guard 단일화 |
| 1.3 M8 | 프런트 ErrorCode union에 `DELTA_SINCE_TOO_OLD` · `DELTA_RATE_LIMITED` 누락 | DAY6 §9-2 M8 | `frontend/src/api/types.ts` union 2종 추가 |
| 1.4 | Testcontainers PostgreSQL 통합 테스트 (JSONB `@>` · GIN 적중 · advisory lock) | erd §13 · backlog | `IntegrationTest` 베이스 + `JsonbGinIntegrationTest` + `AdvisoryLockIntegrationTest` |
| 1.5 | RetryService 5종 에러 분기 통합 테스트 (ADR-005 §5) | DAY3 · backlog | `RetryServiceIntegrationTest` — RUNNING/SUCCESS/FORBIDDEN_ACTOR/MAX/CHAIN_FROM_NON_TERMINAL |
| 1.6 | ADR-005 Q1 — `max_retry_count` PATCH 후 진행 중 체인이 옛 `max_retry_snapshot` 유지 | erd §13 · backlog | `RetryMaxSnapshotPolicyTest` (단위) |
| 1.7 | ADR-005 Q2 — 멀티홉 체인 루트 actor 기준 `RETRY_FORBIDDEN_ACTOR` | erd §13 · backlog | `RetryRootActorPolicyTest` (단위) |
| 1.8 | OrphanRunningWatchdog 5분 sweep — Mock 무한 sleep 후 회수 | DAY3 · backlog | `OrphanRunningWatchdogIntegrationTest` (Testcontainers + 짧은 timeout 주입) |
| 1.9 | SnapshotFieldParityTest — `InterfaceConfigDetailResponse` ↔ `InterfaceConfigSnapshot` 필드 일치 (configJson만 차이) | Architect Day 2-B SHOULD | `SnapshotFieldParityTest` (리플렉션 기반) |
| 1.10 | DefensiveMaskingFilter p95 < 50ms (size=100 × 64KB) | api-spec §3.4 · backlog | `DefensiveMaskingFilterBenchTest` (JMH 없이 단순 시간 측정) |
| 1.11 M4 | `SseEmitterService.subscribe` find+unregister+register TOCTOU race 시나리오 테스트 | DAY6 §9-2 M4 | `SseSubscribeRaceTest` (CountDownLatch 2 스레드) |

### 2-B 묶음 2 — 문서·코드 정합 (자동 진행)

| ID | 항목 | 출처 |
|---|---|---|
| 2.1 | `api-spec.md §1.1 changelog` · `§7.1 matrix` "17종" stale 표기 → "21종" | DAY6 §9-2 운영 이관 |
| 2.2 | `application.yml`에 `spring.jackson.default-property-inclusion: ALWAYS` 명시 | DevilsAdvocate Day 2-A · backlog |
| 2.3 | `ApiResponse<T>` 불변식 Javadoc 추가 (`success=true`일 때만 `data` 경로 마스킹) | DevilsAdvocate Day 2-A · backlog |
| 2.4 | M2 (HMAC 서명) · M3 (TTL cleanup) · M6 (이중 refresh 관찰) 운영 이관 사유 명문화 | DAY6 §9-2 |
| 2.5 | `backlog.md` 정리 — Day 6 완료 항목 ✅ 마킹 + Day 7 완료 항목 제거 + 운영 이관 항목 분리 | 원칙 |

### 2-C 묶음 3 — 제출 핸드오프 (사용자 협조 단계)

| ID | 항목 | 책임 |
|---|---|---|
| 3.1 | 수동 E2E 8 시나리오 (DAY6 §5) 실행 — 사용자 브라우저 | 사용자 |
| 3.2 | Day 5 회귀 5 시나리오 실행 (로그인/CSRF·필터·등록·낙관락·수동 트리거) | 사용자 |
| 3.3 | Swagger UI try-it-out 회귀 (`/swagger-ui.html` 11 엔드포인트) | 사용자 |
| 3.4 | `DAY7-SUMMARY.md` 작성 (1·2 결과 + 3 체크리스트 + 누적 통계) | Claude |
| 3.5 | 최종 README (실행 방법 · 폴더 구조 · 제출 항목 인덱스) | Claude |
| 3.6 | 제출용 ZIP 패키지 (선택) | 사용자 |

### 2-D 명시적 범위 제외

- **운영 이관**: M2 HMAC, M3 분산 rate limit, OFFSET→Keyset, 월 파티션, RealExecutor 등 (`backlog.md §"운영 전환"` 보존)
- **M1·M9 cursor 리팩터**: 프런트 dedup이 은폐 중. 프로토타입 범위 외, Known Issues 보존
- **Role 분리·Vault·Slack 알림**: planning §12 운영 항목

---

## 3. 아키텍처 영향

전부 **기존 모듈 내부 변경 + 테스트 신설**. 신규 패키지·신규 도메인 없음.

| 영역 | 영향 |
|---|---|
| `domain/execution` | 테스트 추가만 (`RetryServiceIntegrationTest`, `RetryMaxSnapshotPolicyTest`, `RetryRootActorPolicyTest`, `OrphanRunningWatchdogIntegrationTest`) |
| `domain/monitor/sse` | 테스트 추가만 (`SseSubscribeRaceTest`) |
| `global/web` | 테스트 추가만 (`DefensiveMaskingFilterBenchTest`) |
| `global/response` | Javadoc 추가 (`ApiResponse`) |
| `frontend/src/api` | `types.ts` ErrorCode union 2종 |
| `frontend/src/pages` | `Dashboard.vue` router-link, `ExecutionHistory.vue` query→state 동기화 |
| `frontend/src/stores` | `auth.logout()`에 sessionStorage clearing 통합 |
| `resources/application.yml` | `spring.jackson.default-property-inclusion` 1줄 |
| `docs/` | api-spec, backlog, DAY7-SUMMARY, README |

---

## 4. 테스트 인프라 결정

### 4-A Testcontainers vs H2

| 기준 | 결정 |
|---|---|
| JSONB `@>` · GIN 인덱스 동작 검증 | **Testcontainers PostgreSQL 16** 필수 (H2는 JSONB 미지원) |
| advisory lock `pg_try_advisory_xact_lock(int,int)` | **Testcontainers** 필수 (H2 시그니처 부재) |
| 일반 단위 테스트 | **기존 @SpringBootTest + H2** 유지 (속도) |
| 베이스 클래스 | `AbstractPostgresIntegrationTest` (`@Testcontainers` + `@Container static PostgreSQLContainer`) — 컨테이너 1회 부팅 재사용 |

**중요 함정 회피** (project_day3_pitfalls 참조):
- advisory lock 시그니처는 `(int,int)` — 키를 `int`로 인코딩
- `@Async` self-invocation 회피
- `LocalDateTime+XXX` 패턴

### 4-B 벤치 — JMH vs 단순 측정

DefensiveMaskingFilter 벤치는 **JMH 없이** `System.nanoTime()` 100회 워밍업 + 1000회 측정으로 p95만 산출. 1주일 프로토타입에서 JMH 도입은 과잉.

### 4-C M4 race 재현

`CountDownLatch(2)`로 두 subscribe 스레드를 동시에 시작 → 동일 clientId로 race 발생 → registry 상태가 정확히 1개 emitter만 남는지 검증. flaky 가능성이 있으면 `@RepeatedTest(50)`.

---

## 5. 리스크 · 트레이드오프

| 리스크 | 완화 |
|---|---|
| Testcontainers Docker 풀링 시간 | 기존 `docker-compose.yml`의 PostgreSQL 16 이미지와 동일 버전 사용 → 재사용 |
| OrphanRunningWatchdog 통합 테스트 flaky | watchdog 주기를 `@TestPropertySource`로 1초로 단축, awaitility 사용 |
| Masking 벤치가 CI에서 실패 | local-only 프로파일 (`@EnabledIfEnvironmentVariable(named="RUN_BENCH")`) — 결과는 `DAY7-SUMMARY`에 기록 |
| M4 race가 잘 재현 안 됨 | flaky하면 `@Disabled` + 시나리오 문서화로 hand-off |
| 묶음 1+2 진행 중 빌드 깨짐 | 각 묶음 끝에 `./gradlew clean build` + `npm run build` 게이트 |

---

## 6. Done 정의

### 묶음 1 완료
- 신규 테스트 파일 9~11개 추가, 모두 통과
- `./gradlew clean build` SUCCESSFUL, 누적 테스트 ≥27 (Day 6 18 + Day 7 ≥9)
- ArchUnit 3 PASS 유지
- M5/M7/M8 프런트 수정, `npm run build` PASS

### 묶음 2 완료
- api-spec.md ErrorCode 표 21종 일관, "17종" 표기 0건
- application.yml `default-property-inclusion: ALWAYS` 적용 후 백엔드 기동 PASS
- ApiResponse Javadoc 4줄 이상
- backlog.md 정리 (Day 5/6 ✅ + Day 7 완료 항목 제거)

### 묶음 3 완료 (사용자 협조)
- 수동 E2E 8 + Day 5 회귀 5 + Swagger 11 — 체크리스트 사용자 확인
- DAY7-SUMMARY.md 커밋
- 최종 README 커밋

---

## 7. 추적

- spec: `docs/superpowers/specs/2026-04-21-day7-integration-and-handoff-design.md` (본 문서)
- plan: `docs/superpowers/plans/2026-04-21-day7-integration-and-handoff.md` (다음 단계)
- summary: `docs/DAY7-SUMMARY.md` (제출 직전)
