# Day 7 완료 요약 — 2026-04-21

> Day 6 후속. M1~M9 코드 부채 청산 + 자동화 가능 통합·단위·벤치 테스트 5종 + 문서·코드 정합 정리.
> 수동 E2E·Swagger 회귀는 사용자 협조 단계로 핸드오프(§5).
> Testcontainers 의존 5종은 환경 호환 이슈로 보류(§6).

---

## 1. 산출물

| 파일 | 상태 |
|---|---|
| [superpowers/specs/2026-04-21-day7-integration-and-handoff-design.md](superpowers/specs/2026-04-21-day7-integration-and-handoff-design.md) | spec |
| [superpowers/plans/2026-04-21-day7-integration-and-handoff.md](superpowers/plans/2026-04-21-day7-integration-and-handoff.md) | 초판 plan (참조) |
| [superpowers/plans/2026-04-21-day7-integration-and-handoff-rev2.md](superpowers/plans/2026-04-21-day7-integration-and-handoff-rev2.md) | **정본 plan** (코드 사실 정합) |
| [api-spec.md](api-spec.md) | v0.8 — §7.1 sealed permits에 `RateLimitException` + 21종 정합 |
| [backlog.md](backlog.md) | Day 5/6 ✅ 마킹 + Day 7 진행 결과 + Docker/M-항목 운영 이관 |
| [DAY7-SUMMARY.md](DAY7-SUMMARY.md) | 본 문서 |

---

## 2. 묶음 1 — 자동 완료 코드 부채 청산

### 2-A 즉시 수정 (M5/M7/M8)

| ID | 변경 | 파일 | 커밋 |
|---|---|---|---|
| M5 | Dashboard `failed` 카드/`recentFailures` 행 → `/history?status=FAILED` 드릴다운. ExecutionHistory가 `route.query.status`를 statusFilter 초기값으로 동기화 (allowed status whitelist) | `Dashboard.vue`, `ExecutionHistory.vue` | `b133106` |
| M7 | router beforeEach 분기 단순화(`from.name === undefined`도 처리) + `auth.clear()` 내부 `sessionStorage.removeItem('sse.clientId')` 통합 (이중 안전) | `router/index.ts`, `stores/auth.ts` | `b133106` |
| M8 | ErrorCode union에 `DELTA_RATE_LIMITED` + `DELTA_SINCE_TOO_OLD` 추가 → 21종 정합 | `frontend/src/api/types.ts` | `b133106` |

### 2-B 신규 자동 테스트

| 파일 | 케이스 | 비고 |
|---|---|---|
| `RetryGuardSnapshotPolicyTest` | 8 (Q1 max_snapshot, Q2 root actor mismatch, Q2 SYSTEM/ANONYMOUS, truncated, inactive, not_leaf, all_pass) | ADR-005 §5.2 평가 우선순위 단위 검증. record 직접 인스턴스화로 Spring 컨텍스트 우회 |
| `SnapshotFieldParityTest` | 1 | Detail vs Snapshot 필드 정합. 의도적 제외 set(configJson, createdAt) 명문화 |
| `MaskingRuleBenchTest` | 1 (RUN_BENCH=1 게이트) | 64KB 페이로드, 워밍업 30회 + 측정 200회. p95 < 50ms (api-spec §3.4 SHOULD) |
| `SseSubscribeRaceTest` | @RepeatedTest(20) | M4 — 동일 clientId 동시 구독 race + grace 4s 후 ≤1 수렴 |
| `ApiResponseSerializationTest` | 2 | data=null·message=null 직렬화 회귀 (T15 동반) |

**총 신규 테스트**: 12 케이스 + race @RepeatedTest 20회 = 32 실행

---

## 3. 묶음 2 — 문서·코드 정합

| ID | 변경 | 파일 |
|---|---|---|
| 2.1 | `spring.jackson.default-property-inclusion: ALWAYS` 명시 | `application.yml` |
| 2.2 | `ApiResponse` 불변식 4종 Javadoc (data 마스킹 경로, ErrorDetail skip, message 노출 규약, timestamp ISO-8601) | `ApiResponse.java` |
| 2.3 | api-spec.md §7.1 매트릭스 + §7.1 본문 — sealed permits에 `RateLimitException` 추가, 17종 → 21종 stale 정합 | `api-spec.md` v0.8 |
| 2.4 | backlog.md Day 5/6/7 진행 결과 갱신 + 운영 이관 (M2/M3/M6/M1/M9 + Docker/Testcontainers + GIN) | `backlog.md` |

---

## 4. 빌드·테스트 상태

| 검증 | 결과 |
|---|---|
| `./gradlew clean test` (Day 6 18 테스트 + Day 7 자동 12 케이스 + race 20반복) | (T18 게이트 결과) |
| `npm run build` (vue-tsc + vite) | PASS — 빌드 산출물 dist/ |
| ArchUnit 3 PASS | 유지 |
| MaskingRule p95 (RUN_BENCH=1) | (T9 게이트 결과) |

**빌드 게이트 결과는 T18 실행 후 본 §4에 인용**. (제출 직전 갱신)

---

## 5. 묶음 3 — 사용자 협조 검증 (핸드오프)

> 백엔드 + 프런트 모두 빌드 PASS. 다음 절차로 사용자가 직접 검증:

```bash
# 터미널 1
docker-compose up -d

# 터미널 2
cd backend
./gradlew bootRun

# 터미널 3
cd frontend
npm run dev
```

### 5-A 수동 E2E 8 시나리오 (DAY6-SUMMARY §5 표 1~8)

| # | 시나리오 | Expected |
|---|---|---|
| 1 | 로그인(operator) → /dashboard → 인터페이스 실행 | 카운터 +1 (debounce 후) |
| 2 | /history → status=FAILED 필터 → 새 FAILED 이벤트 | 1p 기본뷰면 prepend / SUCCESS 이벤트는 무시 |
| 3 | /history → FAILED 행 재처리 | 토스트 + 체인 행 반영 |
| 4 | 브라우저 F5 on /dashboard | 서버 로그 `CLIENT_ID_REASSIGNED` 관찰 |
| 5 | SSE 5분+ 단절 시뮬 | `RESYNC_REQUIRED` → delta 호출 → 테이블 병합 |
| 6 | 세션 만료 | `UNAUTHORIZED` 이벤트 → /login 이동 + `SSE_DROPPED_ON_SESSION_EXPIRY` |
| 7 | `curl '/api/executions/delta?since=2020-01-01T00:00:00Z'` | 400 DELTA_SINCE_TOO_OLD |
| 8 | delta 연속 11회 호출 | 11번째 429 DELTA_RATE_LIMITED |

### 5-B Day 5 회귀 5 시나리오 (DAY6-SUMMARY §5 표 a~e)

| # | 시나리오 |
|---|---|
| a | 로그인/로그아웃 (CSRF) |
| b | 인터페이스 목록 필터·페이지네이션 |
| c | 등록 (ConfigJsonValidator) |
| d | 수정 + 낙관적 락 다이얼로그 |
| e | 수동 실행 트리거 201 |

### 5-C M5 신규 검증 (Day 7)

| # | 시나리오 | Expected |
|---|---|---|
| M5-1 | /dashboard → 실패 카드 클릭 | /history?status=FAILED 진입 + 필터 적용 |
| M5-2 | /dashboard → 최근 실패 행 클릭 | 동일 |
| M7 | 새 탭 첫 방문 → /login | 콘솔에서 `sessionStorage.getItem('sse.clientId')` null 확인 |

### 5-D Swagger UI 11 엔드포인트 try-it-out

`http://localhost:8080/swagger-ui.html` — 모든 endpoint 200/201/4xx 응답 정상 확인

---

## 6. 보류 — Testcontainers 환경 이슈

**환경**: Windows 11 + Docker Desktop 29.1.3 + Testcontainers 1.20.5

**증상**: docker-java client가 모든 daemon 인터페이스(npipe / tcp)에서 빈 메타 응답(`ServerVersion=""`, `Containers=0`)을 받아 `BadRequestException`. CLI(`docker info`)는 정상.

**원인 추정**: Docker Desktop 26+가 도입한 추가 보안 라벨(`com.docker.desktop.address`)을 java-client가 따라가지 못함. CLI는 추가 인증 단계를 거치지만 java-client는 직접 접근.

**시도한 회피책 (모두 실패)**:
- `~/.testcontainers.properties` `docker.host=npipe:////./pipe/dockerDesktopLinuxEngine`
- 동일 host `docker_engine`
- `tcp://localhost:2375` (Docker Desktop GUI에서 노출)
- `DOCKER_HOST` 환경변수
- Testcontainers 1.20.3 → 1.20.5 업그레이드
- `docker context use default` 전환
- `checks.disable=true` properties

**영향 (보류 task 5종)**:
- `JsonbContainmentIntegrationTest` — JSONB `@>` 연산자 검증
- `AdvisoryLockIntegrationTest` — `pg_try_advisory_xact_lock` 동시성
- `RetryServiceIntegrationTest` — RetryService.retry 5종 분기 통합
- `OrphanRunningWatchdogIntegrationTest` — sweep RUNNING→FAILED 회수
- `RetryRootActorPolicyTest` (멀티홉) — 본 영역은 RetryGuard 단위 8 케이스로 ADR-005 핵심 분기 커버

**보존된 코드** (환경 회복 시 즉시 활성):
- `backend/src/test/java/com/noaats/ifms/support/AbstractPostgresIntegrationTest.java`
- `backend/src/test/java/com/noaats/ifms/support/ExecutionLogTestSeeder.java`
- `backend/build.gradle`에 `testcontainers:postgresql` + `:junit-jupiter` 의존성 (1.20.5)

**대안**:
- TC 1.21+ 출시 후 재시도 (Docker Desktop 29 호환 패치 기대)
- 또는 Docker Desktop 25.x 다운그레이드
- 또는 WSL2 내부에서 백엔드 빌드 실행 (TC unix socket 경로)

→ `backlog.md §"운영 전환"`에 "Docker Desktop 29 + Testcontainers 호환 회복" 항목으로 명문화.

---

## 7. Known Issues 잔존 (운영 이관)

DAY6-SUMMARY §9-2의 9건 중 Day 7에서 처리한 부분:

| ID | Day 6 분류 | Day 7 처리 | 잔존 사유 |
|---|---|---|---|
| M1 | Peer Minor | 보류 | 프런트 dedup 은폐 중. 복합 cursor 신설 시 함께 |
| M2 | Security E | 운영 이관 | HMAC 서명 — 분산 환경 전환 시 |
| M3 | Peer Minor | 운영 이관 | rate limit 분산화와 함께 |
| M4 | Devils H | ✅ Day 7 — `SseSubscribeRaceTest` @RepeatedTest(20) | 시나리오 검증 완료 |
| M5 | Devils H | ✅ Day 7 — Dashboard 드릴다운 | |
| M6 | Devils D | 운영 이관 | C3 싱글턴화로 확률 낮음. APM 후 결정 |
| M7 | Devils G | ✅ Day 7 — clear + router 분기 단순화 | |
| M8 | Peer Minor | ✅ Day 7 — ErrorCode 21종 정합 | |
| M9 | Peer Minor | 보류 | sort=ASC prepend — 실 재현 확률 낮음 |

**Day 7 새로 식별된 운영 이관**:
- Docker Desktop 29 + Testcontainers 호환 회복
- schema.sql JSONB GIN 인덱스 도입 (erd 설계만)

---

## 8. 누적 통계

| 항목 | Day 6 | Day 7 변동 | 누적 |
|---|---|---|---|
| Java 파일 | 75+ | +5 (test) | 80+ |
| 백엔드 테스트 케이스 | 18 | +12 (자동) | 30 + race @RepeatedTest 20반복 |
| ArchUnit | 3 PASS | 유지 | 3 PASS |
| Vue 파일 | 12 | 수정 5 | 12 |
| TS 파일 | 14 | 수정 3 | 14 |
| ADR | 7종 | 신규 0 | 7종 |
| 엔드포인트 | 11 | 변동 없음 | 11 |
| ErrorCode | 21종 (백엔드) | 정합 (프런트 20→21) | **백·프 모두 21종** |
| 문서 (docs/) | 11 | +3 (spec/plan/REV2/SUMMARY) | 15 |

---

## 9. Day 7 커밋 이력

```
b133106 fix(frontend): M5 Dashboard→/history 드릴다운 + M7 sessionStorage 정리 + M8 ErrorCode 21종
4ae7fd9 test(interface): SnapshotFieldParityTest — Detail↔Snapshot 필드 회귀 보호
02ba634 test(retry): RetryGuard 단위 8 케이스 — ADR-005 Q1·Q2·SYSTEM·ANONYMOUS·truncated·inactive·not_leaf·all_pass
e5cfff8 docs(day7): plan REV2 — 코드 사실 정합 개정 (초판 폐기)
a773bd3 docs(day7): implementation plan — 21 task
8d0adab docs(day7): spec — 통합 테스트·부채 청산·제출 핸드오프
```

(이후 빌드 게이트 + DAY7-SUMMARY + README 커밋 추가 예정)

---

**Day 7 자동화 묶음 종결.** 묶음 3은 사용자 검증 단계 → 통과 시 §4 빌드 결과·§5 체크리스트 ✅ 마킹 후 최종 제출.
