# Day 6 설계 — ExecutionHistory · Dashboard · SSE 재동기화 · 세션 경계

> 작성일: 2026-04-20 · 상태: 승인 · 다음 단계: 구현 플랜(`docs/superpowers/plans/2026-04-20-day6-monitor-dashboard.md`)
>
> 본 설계는 brainstorming 9문항 + 4-에이전트 기술 회의(R1~R6, ADR-007 후보)의 결론을 병합한 단일 spec이다.

---

## 1. 범위

Day 5가 부분 진행된 상태에서 Day 6에 다음을 완결한다.

- `DAY5-SUMMARY.md` 역구축(Day 5 산출물 문서화 누락 보강)
- 프런트엔드 화면: `ExecutionHistory.vue`, `Dashboard.vue` 신설 + Day 5 화면 점검
- 프런트엔드 공통: Vuetify 3 테마 커스터마이징 + `StatusChip` + composable 2종(`useExecutionStream`, `useDashboardPolling`)
- 백엔드 신규 API: `GET /api/executions/delta` (RESYNC 복구)
- 백엔드 세션 경계: SSE `clientId` 재할당(grace 2초) + `UNAUTHORIZED` 이벤트 전송(Day 7 이월 → Day 6 선행)
- 문서 동기화: ADR-007 · api-spec.md · backlog.md · DAY6-SUMMARY.md

**구현 제외 (Day 7 또는 운영 이관)**:
- `idx_log_started_at_id_asc` 복합 인덱스 및 `(started_at, id)` 복합 커서 — Architect 판정에 따라 Day 6 범위 밖
- 분산 rate limit(Redis/Bucket4j) — in-memory ConcurrentHashMap로 대체
- SSE 체인 계보 시각화 — `parent_log_id` 노출만, 재귀 조회 API 신설 안 함

---

## 2. 사용자 결정 요약 (brainstorming 1~9)

| # | 쟁점 | 결정 |
|---|---|---|
| 1 | 범위 확장 | B — 표준 + Day 5 요약 문서화 + backlog 동기화 묶음 처리 |
| 2 | ExecutionHistory 실시간 | B — 1p+기본필터 조건 prepend, 그 외 "새 실행 N건" 배너, RUNNING 행은 어디서든 id 매칭 in-place 갱신 |
| 3 | SSE 재연결 범위 | A — Last-Event-ID 재전송 + RESYNC_REQUIRED 시 `/api/executions/delta` 호출 |
| 4 | Dashboard 갱신 | B — SSE 이벤트 시 1초 debounce로 `/api/monitor/dashboard` 재호출 + 60초 폴백 polling |
| 5 | 재처리 UX | C — 행별 버튼 + 확인 다이얼로그 + payload·error 모달(체인 계보 없음) |
| 6 | clientId 스푸핑 | A(→회의 후 B로 조정) — 회의 판정 R3 참조 |
| 7 | delta 응답 형태 | B — 전용 엔드포인트, 단일 응답, `truncated`+`nextCursor` |
| 8 | 테마 | B — primary `#1E4FA8` + 상태 4색 팔레트 |
| 9 | Day 5 점검 | C — 클릭 테스트 5 시나리오, 문제 발견 시 즉시 수정 |

## 3. 4-에이전트 회의 결과 (R1~R6) — ADR-007 초안

| R | Architect 결정 |
|---|---|
| R1. delta 커서 | startedAt 단독 커서(base64 ISO-8601) + limit+1 truncated. 복합 인덱스는 Day 6 범위 외 |
| R2. delta 보안 | since 하한 24h + actor 60s/10회 in-memory rate limit + 감사 로그 1줄 |
| R3. clientId 스푸핑 | grace 2s + 서버 주도 이전 emitter complete + `CLIENT_ID_REASSIGNED` 감사. 409 거절 방식 기각 |
| R4. Dashboard polling | SSE open 시 polling OFF / closed·error 시 ON — `useExecutionStream` open/error 훅 토글 |
| R5. SSE UNAUTHORIZED | Day 7 이월 항목을 Day 6로 당김. 서버에서 `UNAUTHORIZED` 이벤트 송출 후 close, 프런트 onmessage에서 close+logout+router push. `SSE_DROPPED_ON_SESSION_EXPIRY` 감사 |
| R6. 프런트 dedup | log_id Map + 최신 createdAt 교체 + 1000건 LRU 상한 |

판정 축: (1) 명세 정합성 (2) 1주일 일정 (3) append-only 감사 무결성. R1에서 인덱스 신설을 포기한 여유를 R5 백엔드 분기에 투입해 총 작업량 균형 유지.

---

## 4. 백엔드 설계

### 4.1 `GET /api/executions/delta` 엔드포인트

**역할**: SSE 링버퍼(1000건/5분) 초과 단절 시 프런트가 누락 이벤트를 복구하는 공백 메꾸기.

**쿼리 파라미터**:
- `since` — ISO-8601 OffsetDateTime. 최초 호출 시 사용. 하한 **지금 - 24시간**, 초과 시 `400 DELTA_SINCE_TOO_OLD`.
- `cursor` — base64(`startedAt` ISO-8601). 2페이지 이후 호출 전용. `since`와 동시 사용 시 `cursor` 우선.
- `limit` — 기본 500, 최대 1000.

**응답** `ApiResponse<DeltaResponse>`:
```
{
  items: ExecutionLogResponse[],
  truncated: boolean,
  nextCursor: string | null
}
```
- `truncated=true`면 `nextCursor` 존재, 프런트는 1회 한정 전량 refetch 폴백을 선택할 수 있다(Day 6 단계).
- `truncated=false`면 `nextCursor=null`.

**쿼리**:
```sql
SELECT ... FROM execution_log
WHERE started_at >= :since
ORDER BY started_at ASC
LIMIT :limit + 1
```
`limit+1` 조회 후 `items.size() > limit`이면 `truncated=true`, 마지막 1건을 버린 뒤 `nextCursor = base64(items[limit-1].startedAt)`로 설정. 마이크로초 충돌 시 경계 행 1건 유실 가능 — 설계상 수용(원본은 DB에 append-only 보존).

**보안·감사**:
- 인증 필수 (세션). actor 필터 없음(운영자 전체 관측 허용).
- `since` 하한 24h 검증 실패 시 `400 DELTA_SINCE_TOO_OLD`.
- actor 기준 in-memory rate limit(ConcurrentHashMap, 슬라이딩 윈도우 60초 10회) 초과 시 `429 DELTA_RATE_LIMITED`.
- 성공·실패 모두 감사 로그 1줄: `actor={hash} since={iso} returned_count={n} truncated={bool} limit={n}`.

**ErrorCode 신규**: `DELTA_SINCE_TOO_OLD(400)`, `DELTA_RATE_LIMITED(429)` — 총 19종 → **21종**.

### 4.2 SSE clientId 재할당 (grace 2초)

**현재(Day 4)**: `SseEmitterRegistry.clientIdBoundToOtherSession(clientId, sessionId)` 메서드 존재, 호출 안 됨.

**Day 6 변경**:
- `SseEmitterService.subscribe(sessionId, clientId, lastEventId)` 진입 시:
  1. 동일 `clientId`가 다른 세션에 바인딩 중이면 이전 emitter를 **2초 delayed complete** 스케줄 + 새 세션에 즉시 재할당.
  2. 2초 grace 동안 이벤트는 **새 emitter에만** 라우팅 (이중 emit 금지).
  3. 감사 로그 1줄: `event=CLIENT_ID_REASSIGNED clientId={uuid} old_session={hash} new_session={hash} actor={hash}`.
- 이유: 브라우저 F5/탭 이동 시 이전 세션의 emitter complete 지연(~1s)과 새 세션 요청의 경쟁 상태에서 409 거짓양성 방지.

**감사 로그 파일**: application.log (현 구성) — SSE 감사 독립 파일은 Day 7+ 이월.

### 4.3 SSE UNAUTHORIZED 이벤트 (Day 7 → Day 6 당김)

**배경**: `EventSource.onerror`는 WHATWG 스펙상 HTTP 상태를 노출하지 않음. 세션 만료 시 서버가 401로 응답하면 브라우저가 3초 간격 자동 재연결 → 감사 로그 오염.

**설계**:
- Spring Security 필터가 `/api/monitor/stream`에 한해 401 대신 **SSE 프레임**으로 `event: UNAUTHORIZED` 송출 후 `SseEmitter.complete()`.
- 프런트 `useExecutionStream`이 `UNAUTHORIZED` 이벤트 리스너에서 `close()` + `authStore.logout()` + `router.push('/login')`.
- 추가 보조: `router.beforeEach`에서 `/login` 이동 시 모든 SSE 연결 명시 close (명시 로그아웃 케이스 커버).
- 감사 로그: `event=SSE_DROPPED_ON_SESSION_EXPIRY sessionId={hash} clientId={uuid} actor={hash}`.

**구현 위치**:
- `SseEmitterService` 또는 신규 `SseAuthEventPublisher`에 `publishUnauthorizedAndClose(sessionId)` 메서드.
- `HttpSessionListener` 구현으로 세션 만료 감지 → 해당 세션의 emitter 일괄 close 전 UNAUTHORIZED 이벤트 송출.

### 4.4 테스트

- `ExecutionDeltaTest` — (1) since 정상 조회, (2) since 하한 초과 400, (3) limit+1 truncated 플래그, (4) rate limit 11회 429.
- `SseReassignmentTest` — 세션 A로 clientId X 구독 → 세션 B로 clientId X 요청 → 2초 내 A의 emitter complete, B는 즉시 수신.
- `SseUnauthorizedTest` — 세션 만료 후 UNAUTHORIZED 이벤트 수신 + emitter complete.

---

## 5. 프런트엔드 설계

### 5.1 Vuetify 3 테마 (`frontend/src/plugins/vuetify.ts`)

```ts
createVuetify({
  theme: {
    defaultTheme: 'light',
    themes: {
      light: {
        colors: {
          primary:    '#1E4FA8',
          secondary:  '#3A6FCE',
          success:    '#1E8A4C',  // SUCCESS
          error:      '#C62828',  // FAILED
          warning:    '#ED8936',  // RECOVERED
          info:       '#1E88E5',  // RUNNING
          surface:    '#FFFFFF',
          background: '#F4F6FA',
        },
      },
    },
  },
})
```

Day 5 기존 화면은 primary·semantic 색상 참조만 유지(개별 색상 하드코딩은 회귀 대상).

### 5.2 `components/StatusChip.vue` — 공용

Props: `status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'RECOVERED'`. `v-chip` size="small" + mdi 아이콘 + semantic color 매핑.

### 5.3 `composables/useExecutionStream.ts`

책임:
- EventSource 생성·종료 (`/api/monitor/stream?clientId=<uuid>`)
- clientId는 `sessionStorage['sse.clientId']` 재사용, 없으면 `crypto.randomUUID()` 생성 후 저장
- Last-Event-ID는 EventSource 내장 동작에 위임
- 이벤트 리스너 등록 API: `onStarted/onSuccess/onFailed/onRecovered/onHeartbeat/onResync/onUnauthorized/onFullRefresh`
- **RESYNC_REQUIRED** 수신 시 → 내부 `lastSeenAt` 기준 `fetchDelta(since, 500)` → `truncated=true`면 `onFullRefresh` 발화, 아니면 `items`를 해당 타입 콜백으로 재주입
- **R4 Dashboard polling 토글** 훅 노출: `onOpen(cb) / onError(cb)` — `useDashboardPolling`에서 구독
- **R5 UNAUTHORIZED** 핸들러: `close()` + `authStore.logout()` + `router.push('/login')`
- **R6 dedup**: `Map<log_id, { status, createdAt }>` + 1000건 LRU. 이벤트 수신 시 `createdAt` 비교 후 최신만 교체

### 5.4 `composables/useDashboardPolling.ts`

- `refresh()` 공개 — `GET /api/monitor/dashboard` 호출
- 1초 debounce — SSE `EXECUTION_*` 이벤트 수신 시 예약
- **R4 상태 기반 폴백 polling**: `useExecutionStream.onOpen(() => stopPolling())` / `onError(() => startPolling(60_000))` — SSE 정상 수신 중엔 polling OFF
- `onUnmounted`에서 타이머·listener 해제

### 5.5 `pages/ExecutionHistory.vue`

- v-data-table-server + 페이지네이션(백엔드 offset) + 필터 `status / protocol / name`
- 행별 "재처리" 버튼 → v-dialog 확인 → `POST /api/executions/{id}/retry` → 5종 에러 코드 맞춤 토스트
- 행 클릭 → 단순 payload·errorMessage 모달(체인 계보 없음)
- **R2 prepend 3-조건**: `page==1 && sort==startedAt_DESC && 이벤트.status가 현재 filter와 일치` → 낙관적 prepend, 외에는 "새 실행 N건" 배너
- **필터 불일치 이벤트는 무시** (배너도 X) — Devils 지적 반영
- 기존 RUNNING 행의 상태 전이(RUNNING→SUCCESS/FAILED/RECOVERED)는 페이지 무관하게 `id` 매칭 in-place 갱신
- `onFullRefresh` 콜백 시 현 페이지 재조회

### 5.6 `pages/Dashboard.vue`

- 총 카운트 카드 4개 (total/running/success/failed) — 숫자 굵은 헤드라인 + accent bar
- byProtocol 테이블 (프로토콜별 running/success/failed)
- recentFailures 리스트 (최근 10건, StatusChip + errorMessage 요약)
- sseConnections 인디케이터 (현재 활성 SSE 연결 수)
- `useDashboardPolling` + `useExecutionStream` 구독으로 실시간 반영

### 5.7 `api/executions.ts` 확장

- `listExecutions(params: { page, size, status?, protocol?, name? })`
- `fetchDelta(since: string, limit = 500)` — 신규
- `retryExecution(id: number)` — 신규(Day 5 미존재 시)
- `getExecutionById(id: number)` — 상세 모달용

### 5.8 Day 5 점검 시나리오 (30분 캡)

1. 로그인/로그아웃 (CSRF 동작)
2. 인터페이스 목록 조회·필터·페이지네이션
3. 인터페이스 등록 (ConfigJsonValidator 동작)
4. 인터페이스 수정 + 낙관적 락 다이얼로그 트리거
5. 수동 실행 트리거 → 201 확인

문제 발견 시 즉시 수정, 무사통과면 그대로 둔다.

---

## 6. 문서 동기화

| 파일 | 변경 |
|---|---|
| `docs/adr/ADR-007-sse-resync-session-boundary.md` | **신규** — R1·R2·R3·R5 묶음 결정 |
| `docs/api-spec.md` | §1.3 ErrorCode 21종(+2) / §3.3 `/api/executions/delta` 섹션 신설 / §6.1 SSE UNAUTHORIZED·clientId 재할당 공식화 |
| `docs/backlog.md` | Day 5·6 완료 항목 스트라이크 / Day 4 이월 "SSE UNAUTHORIZED" 회수 표기 |
| `docs/DAY5-SUMMARY.md` | **신규** — 역구축, Day 5 산출물 목록 + 잔여 이슈 |
| `docs/DAY6-SUMMARY.md` | **신규** — Day 6 통합 요약 + 8 시나리오 수동 E2E 결과표 + ADR-007 링크 |
| `docs/superpowers/plans/2026-04-20-day6-monitor-dashboard.md` | **신규** — 구현 플랜 (다음 skill 단계에서 작성) |

---

## 7. 실행 순서 (7 phase)

1. **문서 선행** — ADR-007 초안 + api-spec.md + backlog.md + DAY5-SUMMARY.md
2. **백엔드 (a) delta API** — Controller/Service/Repository + 커서 base64 util + RateLimiter (ConcurrentHashMap 기반)
3. **백엔드 (b) SSE 세션 경계** — `SseEmitterService.subscribe` grace 재할당 + HttpSessionListener + UNAUTHORIZED 이벤트 송출
4. **백엔드 테스트** — `ExecutionDeltaTest` 4케이스 / `SseReassignmentTest` / `SseUnauthorizedTest`
5. **프런트 (a) 공용** — vuetify 테마 + StatusChip + `useExecutionStream` + `useDashboardPolling`
6. **프런트 (b) 화면** — ExecutionHistory.vue + Dashboard.vue + Day 5 점검 5 시나리오
7. **검증** — `./gradlew build` + `npm run build` + `bootRun` + `npm run dev` + 수동 E2E 8 시나리오

**수동 E2E 8 시나리오**:
1. 로그인 → Dashboard SSE 연결 → 실행 트리거 → 카운터 +1
2. ExecutionHistory 필터 status=FAILED → FAILED 이벤트 prepend, SUCCESS 이벤트 무시
3. 재처리 버튼 → 토스트 + 새 체인 행
4. 브라우저 F5 → clientId 재사용 + grace 재할당 성공(로그 `CLIENT_ID_REASSIGNED`)
5. SSE 장시간 단절 시뮬레이션 → RESYNC_REQUIRED → delta 호출 → 병합
6. 세션 만료 → UNAUTHORIZED 이벤트 → 로그인 이동 + `SSE_DROPPED_ON_SESSION_EXPIRY` 감사
7. `GET /api/executions/delta?since=2020-01-01` → 400 DELTA_SINCE_TOO_OLD
8. delta 11회 연속 호출 → 429 DELTA_RATE_LIMITED

---

## 8. 수용한 리스크

| 리스크 | 수용 근거 |
|---|---|
| delta 마이크로초 경계 행 1건 유실 | 원본은 DB append-only 보존. RESYNC 재호출로 복구 가능. 인덱스 신설 비용(>0.5d) 회피 |
| in-memory rate limit 단일 인스턴스 전제 | 프로토타입 평가 환경과 일치. 분산 전환은 운영 이관 과제 |
| clientId grace 2s 동안 이중 emitter 존재 | 이벤트는 새 emitter에만 라우팅되도록 Registry 내부 강제 |
| SSE UNAUTHORIZED 브라우저 호환성 | Chrome/Edge/Safari 모두 EventSource 이벤트 리스너 지원 — IE11 범위 외 |
| 1000건 LRU 상한 초과 시 오래된 행 축출 | RESYNC는 1000건 이내 수렴, 초과 시 full refresh로 탈출 |

---

## 9. 완료 정의 (DoD)

- `./gradlew build` BUILD SUCCESSFUL + ArchUnit 3종 PASS
- `npm run build` 성공 (vue-tsc 타입체크 포함)
- bootRun + npm run dev 실 기동
- 수동 E2E 8 시나리오 전체 통과
- ADR-007 · api-spec.md · backlog.md · DAY5-SUMMARY.md · DAY6-SUMMARY.md 커밋 완료
- Day 5 점검 5 시나리오 무사통과 또는 발견 버그 수정 완료
