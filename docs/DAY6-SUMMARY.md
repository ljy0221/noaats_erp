# Day 6 완료 요약 — 2026-04-20

> Day 5 후속. ExecutionHistory·Dashboard 화면 + SSE 재동기화·세션 경계 프로토콜(ADR-007) 완결. 수동 E2E 8 시나리오는 통합 검증 단계에서 실행(본 문서 §5 참조).

---

## 1. 문서 산출물

| 파일 | 상태 |
|---|---|
| [adr/ADR-007-sse-resync-session-boundary.md](adr/ADR-007-sse-resync-session-boundary.md) | 신규 — R1·R2·R3·R5 묶음 결정 |
| [api-spec.md](api-spec.md) | §1.3 ErrorCode 21종, §3.3 `/api/executions/delta` 섹션, §6.1 SSE 재할당·UNAUTHORIZED 공식화 |
| [backlog.md](backlog.md) | Day 4 이월 SSE 3종 회수 + Day 5/6 스트라이크 |
| [DAY5-SUMMARY.md](DAY5-SUMMARY.md) | 역구축 (Day 5 작성 누락 보강) |
| [superpowers/specs/2026-04-20-day6-monitor-dashboard-design.md](superpowers/specs/2026-04-20-day6-monitor-dashboard-design.md) | spec |
| [superpowers/plans/2026-04-20-day6-monitor-dashboard.md](superpowers/plans/2026-04-20-day6-monitor-dashboard.md) | 구현 플랜 25 Task |

---

## 2. 백엔드 신규/변경

### 2-A delta API (ADR-007 R1·R2)

| 파일 | 역할 |
|---|---|
| `domain/execution/service/DeltaCursor.java` | base64url(ISO-8601 OffsetDateTime) 단일 커서 유틸 + VALIDATION_FAILED 예외 2종 |
| `domain/execution/service/DeltaRateLimiter.java` | actor별 60s/10회 슬라이딩 윈도우 (ConcurrentHashMap + ArrayDeque) |
| `domain/execution/service/DeltaService.java` | since 하한 24h + rate limit + 커서 복호화 + `findDeltaSince` limit+1 호출 + truncated 판정 + 감사 로그 1줄 |
| `domain/execution/service/ExecutionQueryService.java` | 리스트·상세 조회 (읽기 전용 @Transactional) |
| `domain/execution/dto/ExecutionLogResponse.java` | 이미 존재 확인(Day 5에 생성) — 필드 그대로 사용 |
| `domain/execution/dto/DeltaResponse.java` | `{items, truncated, nextCursor}` |
| `domain/execution/dto/ExecutionListParams.java` | `(status, interfaceConfigId, pageable)` |
| `domain/execution/repository/ExecutionLogRepository.java` | `findDeltaSince(since, Pageable)` · `findList(status, configId, Pageable)` 2개 JPQL 추가 |
| `domain/execution/controller/ExecutionController.java` | `GET /api/executions{,/{id},/delta}` 3 엔드포인트 추가 (기존 retry 보존) |
| `global/exception/ErrorCode.java` | `DELTA_SINCE_TOO_OLD(400)` · `DELTA_RATE_LIMITED(429)` 2종 → **21종** |
| `global/exception/RateLimitException.java` | **신규** — ApiException 429 강제 서브클래스 (DELTA_RATE_LIMITED 수용) |
| `global/exception/ApiException.java` | sealed permits에 RateLimitException 한 줄 추가 |
| `global/config/ClockConfig.java` | **신규** — `systemClock()` @Bean (DeltaRateLimiter 테스트 대체 가능하도록) |
| `resources/application.yml` | `ifms.delta.*` 5개 키 + `ifms.sse.reassign-grace: PT2S` 추가 |

### 2-B SSE 세션 경계 (ADR-007 R3·R5)

| 파일 | 역할 |
|---|---|
| `domain/monitor/sse/SseEmitterRegistry.java` | `findOtherSessionByClientId` · `snapshotBySession` · `SessionEmitter` record 추가 |
| `domain/monitor/sse/SseReassignmentScheduler.java` | **신규** — 단일 스레드 ScheduledExecutor + `@Value` grace 2s + 예외 swallow |
| `domain/monitor/sse/SseEmitterService.java` | `subscribe` 진입 시 재할당 분기 + `publishUnauthorizedAndClose(sessionId)` 추가 + 감사 로그 2종 |
| `domain/monitor/sse/SseSessionExpiryListener.java` | **신규** — HttpSessionListener가 `publishUnauthorizedAndClose` 호출 |
| `global/config/WebMvcConfig.java` | `ServletListenerRegistrationBean<SseSessionExpiryListener>` 명시 등록 (Spring Boot 3 안전장치) |

### 2-C 백엔드 테스트

| 파일 | 케이스 |
|---|---|
| `DeltaCursorTest` | 4 (roundtrip·base64url 문자 검증·invalid base64·invalid iso) |
| `DeltaRateLimiterTest` | 3 (10회 허용/11회 거절·actor 독립 버킷·60s 슬라이딩) |
| `DeltaServiceTest` | 4 (since 24h 초과·rate limit 차단·truncated=true·truncated=false) |
| `SseReassignmentSchedulerTest` | 2 (grace 후 complete·이중 complete 멱등) |
| `SseSessionExpiryListenerTest` | 1 (세션 파괴 시 publish 호출) |
| **Day 6 신규 테스트 총** | **14** |
| 전체 테스트 (누적) | **17 tests, 0 failures, 0 errors** |
| ArchUnit | 3 PASS (`ArchitectureTest`) |

---

## 3. 프런트엔드 신규/변경

### 3-A 공용

| 파일 | 역할 |
|---|---|
| `plugins/vuetify.ts` | 팔레트 재도색 — `primary #1E4FA8` 외 7색 (금융 톤) |
| `api/types.ts` | append-only — `ExecutionStatus`·`TriggerType`·`ExecutionLogResponse`·`ExecutionListParams`·`DeltaResponse`·`DashboardResponse`·하위 3 record |
| `api/executions.ts` | **신규** — `listExecutions`·`getExecution`·`retryExecution`·`fetchDelta` |
| `api/dashboard.ts` | **신규** — `getDashboard(since?)` |
| `components/StatusChip.vue` | **신규** — 4 상태 semantic 색 + mdi 아이콘 |
| `composables/useExecutionStream.ts` | **신규** — EventSource 구독 + sessionStorage clientId + Last-Event-ID 내장 + RESYNC_REQUIRED 시 fetchDelta 병합 + 1000 LRU dedup + UNAUTHORIZED 시 close+logout |
| `composables/useDashboardPolling.ts` | **신규** — debounce 1s refresh + 60s 폴백 polling + SSE open/error 기반 토글 |

### 3-B 화면

| 파일 | 역할 |
|---|---|
| `pages/Dashboard.vue` | **신규** — totals 4 카드 + byProtocol 테이블 + recentFailures 리스트 + SSE state chip + sseConnections 표시 |
| `pages/ExecutionHistory.vue` | **신규** — v-data-table + 필터(상태·정렬) + 수동 페이지네이션 + 1p 기본뷰 prepend + 비기본뷰 배너 + in-place 상태 전이 + 재처리 다이얼로그(5 에러 맞춤 토스트) + 상세 다이얼로그 |
| `router/index.ts` | `/dashboard`·`/history` 라우트 추가 + 루트 redirect `/dashboard` + 로그인 이동 시 sessionStorage clientId 정리 |
| `components/AppShell.vue` | `navItems` 갱신 — Dashboard → 인터페이스 → 실행 이력 순 |

---

## 4. 빌드 상태

- `./gradlew clean build` — **BUILD SUCCESSFUL** in 10s
- ArchUnit 3종 PASS (Repository 주입 범위 / EntityManager.merge 금지 / @Modifying Repository 한정)
- `npm run build` — vue-tsc 타입체크 PASS + vite 744ms (407 modules)
- Day 6 청크: `Dashboard-4.77kB`, `ExecutionHistory-6.71kB`, `StatusChip-3.14kB`

---

## 5. 수동 E2E 8 시나리오 (미실행 — 다음 세션에 통합 검증 예정)

수동 브라우저 검증이 필요해 implementer-subagent 범위 밖으로 분리. 아래 절차로 실행:

```bash
# 터미널 1
docker-compose up -d

# 터미널 2
cd backend && ./gradlew bootRun

# 터미널 3
cd frontend && npm run dev
```

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

Day 5 후속 점검 (회귀 방지):
| # | 시나리오 |
|---|---|
| a | 로그인/로그아웃 (CSRF) |
| b | 인터페이스 목록 필터·페이지네이션 |
| c | 등록 (ConfigJsonValidator) |
| d | 수정 + 낙관적 락 다이얼로그 |
| e | 수동 실행 트리거 201 |

---

## 6. 주요 설계 결정 (ADR-007 요약)

| 영역 | 결정 | 근거 |
|---|---|---|
| delta 커서 | `startedAt` 단독 base64 | Day 6 범위 준수 — 복합 인덱스 신설 회피. 경계 μs 충돌 1건 유실 수용(원본 DB 보존) |
| delta 보안 | since 24h 하한 + actor 60s/10회 rate limit + 감사 1줄 | 조회 행위도 append-only 감사 대상 |
| rate limit 구현 | in-memory ConcurrentHashMap | 단일 인스턴스 전제, 분산 전환은 운영 이관 |
| 예외 클래스 | 429 전용 `RateLimitException` 신설 | 기존 `ConflictException`은 409 강제라 부적합 |
| clientId 재할당 | grace 2s + 서버 주도 complete + 감사 | F5 UX 확보, 하이재킹 탐지 가능 |
| UNAUTHORIZED 전송 | 서버 이벤트 프레임 후 complete | EventSource.onerror가 HTTP 상태 미노출(WHATWG) → 자동 재연결 루프 방지 |
| Listener 등록 | `ServletListenerRegistrationBean` 명시 | Spring Boot 3에서 `@Component` 자동 등록 미보장 |
| 프런트 dedup | `Map<log_id, {status, createdAt}>` + 1000 LRU | 상태 전이 반영, Set dedup 대신 |
| Dashboard polling 토글 | SSE open 시 OFF / error 시 ON | 중복 호출 제거, `useExecutionStream.onOpen/onError` 훅 |

---

## 7. 누적 통계

- Java 파일 75+ (Day 6 신규 10 + 수정 6)
- Vue 파일 12 (Day 6 신규 2 pages + 1 component)
- TS 파일 14 (Day 6 신규 2 composables + 2 api)
- ADR 7종 (ADR-007 확정)
- 엔드포인트 11개 (실행 이력 list·detail·delta 3종 추가)
- ErrorCode **21종** (DELTA 2종 추가)
- 백엔드 테스트 17 (Day 6 신규 14)
- ArchUnit 3 PASS 유지

---

## 8. Day 6 커밋 이력 (20건)

```
cdf5e65 feat(frontend): /dashboard·/history 라우트 + 내비 갱신 + 로그아웃 시 clientId 정리
34d870f feat(frontend): ExecutionHistory.vue — 필터·페이지·재처리·SSE in-place·배너
99d1810 feat(frontend): Dashboard.vue — 카드 + 프로토콜·실패 리스트 + SSE 실시간
077b58b feat(frontend): useDashboardPolling — debounce refresh + SSE 상태 기반 폴백
5d2253a feat(frontend): useExecutionStream — SSE 구독·RESYNC·dedup·UNAUTHORIZED 처리
00c273c feat(ui): StatusChip 공용 상태 뱃지 컴포넌트
7389b5d feat(frontend): executions·dashboard API 모듈 + types 확장
75d3eb0 feat(ui): Vuetify 테마 금융 톤 재도색 (primary #1E4FA8 + 상태 4색)
46cf57a feat(sse): HttpSessionListener로 세션 만료 시 UNAUTHORIZED 이벤트 송출
67a3ec3 feat(sse): subscribe clientId 재할당 흐름 + publishUnauthorizedAndClose (ADR-007)
81038c7 feat(sse): SseReassignmentScheduler 단일 스레드 grace complete + 2 테스트
eb556d6 feat(sse): Registry findOtherSessionByClientId + snapshotBySession + SessionEmitter
78ed4de feat(api): GET /api/executions{,/{id},/delta} 3종 엔드포인트 (ADR-007)
f5c16a7 feat(execution): ExecutionQueryService (리스트·상세 조회)
76a7f0e feat(delta): DeltaService 쿼리+감사+rate limit+since 하한 (4 테스트)
22c50ee feat(execution): Repository findDeltaSince + findList 쿼리 추가
338ebdb feat(execution): ExecutionLogResponse + DeltaResponse record
14e6f31 feat(delta): in-memory 슬라이딩 윈도우 DeltaRateLimiter + 3 테스트
4aff08e feat(delta): base64 단일 커서 유틸 DeltaCursor + 4 테스트
821d82e feat(error-code): DELTA_SINCE_TOO_OLD · DELTA_RATE_LIMITED 2종 추가 (21종)
```

+ 문서 커밋 4건 (ADR-007, api-spec, backlog, DAY5-SUMMARY).

---

## 9. Phase 6 Peer Review + 4-에이전트 회의 결과 (2026-04-20 완료)

구현 완료 후 peer code reviewer + @Security + @DBA + @DevilsAdvocate 4명을 병렬 회의에 투입, @Architect가 통합 판정. Critical 4건 + Important 4건 **즉시 수정** / Minor 9건 Day 7 이월 결정.

### 9-1. 즉시 수정 완료 (8건)

| # | ID | 내용 | 커밋 |
|---|---|---|---|
| 1 | I4 | `DeltaService.query`에 `@Transactional(readOnly=true)` 추가 (DBA 권고) | 02d6a47 |
| 1 | I1 | delta 실패 경로(rate limit / since too old / validation)도 감사 로그 1줄 기록 try/finally 패턴 (ADR-007 R2 정확 이행) | 02d6a47 |
| 1 | I2 | delta 감사 로그의 actor를 추가 hex hash 적용하여 SseEmitterService와 일관성 확보 | 02d6a47 |
| 2 | C1 | `ExecutionEventPublisher.payload`를 프런트 `ExecutionLogResponse` 계약과 정합 — `logId`→`id`, `interfaceId`→`interfaceConfigId`, `startedAt`·`finishedAt`·`retryCount`·`parentLogId`·`errorMessage` 추가 (실시간 UI 상태 전이 복구) | 15cb3d1 |
| 3 | C2 | `publishUnauthorizedAndClose`가 ringBuffer.append를 우회하고 ephemeral SseEvent(id=0) 직접 송출 — 타 세션 강제 로그아웃 누출 차단 + `SseUnauthorizedIsolationTest` 추가 | b932fd7 |
| 4 | C3 | `useExecutionStream` 모듈 스코프 싱글턴 + refCount 리팩터 — Dashboard/ExecutionHistory 동시 마운트 시 EventSource 이중 연결 및 ADR-007 재할당 루프 방지. dedup 키에 terminal status rank 도입 (RUNNING<0, 그 외<1) | a7bc43d |
| 5 | C4 | `ExecutionHistory.handleIncoming`에서 upsert 후 필터 불일치 시 splice 제거 — RUNNING 필터 + SUCCESS 전이 이벤트에서 발생하던 유령 행 제거 | afa3683 |
| 6 | I3 | `useDashboardPolling.requestDebouncedRefresh`에 `maxWaitMs=5000` 도입 — trailing-only debounce가 연속 이벤트 20초 눌림으로 Dashboard가 멈추던 문제 해결 | a263101 |

**수정 루프 통계**:
- 7 커밋 추가, 백엔드 `clean build` SUCCESSFUL, 프런트 `npm run build` 성공
- 백엔드 테스트 **18 tests / 0 failures / 0 errors** (Day 6 post-review 신규 1: `SseUnauthorizedIsolationTest`)
- ArchUnit 3종 PASS 유지

### 9-2. Known Issues (Day 7 이월 — 인지된 부채)

| # | 출처 | 내용 | 판정 근거 |
|---|---|---|---|
| M1 | Peer Minor | delta 커서 부등호 `>=` vs 프런트 dedup 의존 경계 중복 — nextCursor를 dropped row 기준으로 변경 검토 | 프런트 dedup이 은폐 중, Day 7 cursor 리팩터와 함께 |
| M2 | Security E | `DeltaCursor` HMAC 서명 부재 — 공격자가 `t=now-23h59m` 위조 가능 (DoS 표면) | 운영 이관 — 프로토타입 범위 외 |
| M3 | Peer Minor | `DeltaRateLimiter.buckets` Map TTL cleanup 없음 — 장기 운영 시 메모리 압박 | 운영 이관 — 분산 rate limit 전환 시 일괄 해결 |
| M4 | Peer Minor | `SseEmitterService.subscribe` find+unregister+register TOCTOU — 동시 subscribe race | Day 7 통합 테스트에서 시나리오 추가만. 프로토타입 확률 낮음 |
| M5 | Devils H | Dashboard 카드 클릭 드릴다운(/history?status=FAILED) 미지원 — UX 감점 | Day 7 UX 작업 5분 |
| M6 | Devils D | SSE `reconnecting` 상태에서 polling + SSE 이중 refresh 가능 | C3 싱글턴화로 일부 완화됨. Day 7 관찰만 |
| M7 | Devils G | 새 탭 첫 방문 `/login`에서 sessionStorage clientId 정리 안 됨 (`from.name === undefined` 분기 누락) | Day 7 1줄 수정 — auth.logout() 내부로 이동 |
| M8 | Peer Minor | 프런트 `ErrorCode` union 타입에 `DELTA_SINCE_TOO_OLD`·`DELTA_RATE_LIMITED` 누락 | Day 7 타입 정확도 |
| M9 | Peer Minor | 리스트 sort=ASC 상태에서 delta 병합 시 prepend 순서 반전 가능 | Day 7 관찰 — 실 재현 확률 낮음 |

### 9-3. Day 7 이월 원래 항목 (유지)

- Testcontainers 통합 테스트 (JSONB GIN, advisory lock, 재처리 체인)
- RetryService 통합 테스트 (ADR-005 5종 에러 분기)
- DefensiveMaskingFilter p95 < 50ms 벤치
- SnapshotFieldParityTest (Architect Day 2-B SHOULD)
- Swagger UI try-it-out 회귀
- 수동 E2E 8 시나리오 + Day 5 회귀 5 시나리오 실행
- 복합 커서 `(started_at, id)` + `idx_log_started_at_id_asc` 인덱스 (운영 이관)
- 분산 rate limit (Redis/Bucket4j) — 운영 이관
- api-spec.md §7.1 matrix·§1.1 changelog의 "17종" stale 표기 → "21종" 정리

---

**Day 6 구현 완료 + Phase 6 리뷰 수정 루프 종결.** 백엔드 18 tests / ArchUnit 3 PASS, 프런트 빌드 PASS. 수동 E2E 8 시나리오 + Day 5 회귀 5 시나리오는 Day 7에 실행. Known Issues 9건은 인지된 부채로 Day 7 이월.
