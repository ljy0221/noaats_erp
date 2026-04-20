# Day 6 Implementation Plan — ExecutionHistory · Dashboard · SSE 재동기화 · 세션 경계

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Day 5 부분 진행분(인터페이스 CRUD UI)에 ExecutionHistory·Dashboard 화면과 SSE 재동기화·세션 경계 보호를 더해, 제출 가능한 통합 모니터링 프런트엔드를 완성한다.

**Architecture:** 백엔드는 `GET /api/executions/delta` 폴백 API + SSE clientId grace 재할당 + UNAUTHORIZED 이벤트 송출을 추가하고, 프런트엔드는 `useExecutionStream`·`useDashboardPolling` composable로 스트림 구독·폴백 polling을 통합해 두 신규 페이지가 공유한다. Day 5 Vuetify 테마는 금융 톤(primary `#1E4FA8` + 상태 4색)으로 재도색.

**Tech Stack:** Spring Boot 3.3.5(Java 17), Spring Data JPA, PostgreSQL 16, Spring Security 6 세션+CSRF, SSE, ArchUnit 1.3 · Vue 3.5 Composition API, Vuetify 3.12, Pinia 2, vue-router 4, Axios 1, TypeScript 6, Vite 8

**Spec:** `docs/superpowers/specs/2026-04-20-day6-monitor-dashboard-design.md`

---

## File Structure

### 신규 백엔드 파일

- `backend/src/main/java/com/noaats/ifms/domain/execution/dto/ExecutionLogResponse.java` — 실행 로그 단건 응답 DTO (리스트·델타·상세 공용)
- `backend/src/main/java/com/noaats/ifms/domain/execution/dto/DeltaResponse.java` — delta API 응답 record `{items, truncated, nextCursor}`
- `backend/src/main/java/com/noaats/ifms/domain/execution/service/DeltaCursor.java` — base64(ISO-8601) 커서 encode/decode 유틸
- `backend/src/main/java/com/noaats/ifms/domain/execution/service/DeltaRateLimiter.java` — actor 기준 60s/10회 in-memory 슬라이딩 윈도우
- `backend/src/main/java/com/noaats/ifms/domain/execution/service/DeltaService.java` — delta 쿼리 조합(커서·limit+1·since 하한) + 감사 로그
- `backend/src/main/java/com/noaats/ifms/domain/execution/service/ExecutionQueryService.java` — 페이지네이션 리스트 조회(필터 · 페이지 · 정렬) 서비스
- `backend/src/main/java/com/noaats/ifms/domain/execution/dto/ExecutionListParams.java` — 리스트 쿼리 파라미터 record
- `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseReassignmentScheduler.java` — 이전 emitter 2초 delayed complete 스케줄러
- `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseSessionExpiryListener.java` — `HttpSessionListener` — 세션 만료 시 UNAUTHORIZED 이벤트 송출
- `backend/src/test/java/com/noaats/ifms/domain/execution/service/DeltaServiceTest.java`
- `backend/src/test/java/com/noaats/ifms/domain/execution/service/DeltaCursorTest.java`
- `backend/src/test/java/com/noaats/ifms/domain/execution/service/DeltaRateLimiterTest.java`
- `backend/src/test/java/com/noaats/ifms/domain/monitor/sse/SseReassignmentTest.java`
- `backend/src/test/java/com/noaats/ifms/domain/monitor/sse/SseUnauthorizedTest.java`

### 수정 백엔드 파일

- `backend/src/main/java/com/noaats/ifms/global/exception/ErrorCode.java` — `DELTA_SINCE_TOO_OLD(400)`·`DELTA_RATE_LIMITED(429)` 2종 추가 → 21종
- `backend/src/main/java/com/noaats/ifms/domain/execution/repository/ExecutionLogRepository.java` — `findDelta(since, limitPlusOne)` 네이티브 쿼리 + 리스트 조회 메서드
- `backend/src/main/java/com/noaats/ifms/domain/execution/controller/ExecutionController.java` — `GET /api/executions` · `GET /api/executions/{id}` · `GET /api/executions/delta` 추가
- `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterRegistry.java` — 이전 emitter 조회·반환 유틸 추가 (`findEmitterByClientId`)
- `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterService.java` — `subscribe` grace 재할당 흐름 + UNAUTHORIZED 송출 메서드 추가
- `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEventType.java` — 주석 보강 (UNAUTHORIZED 실 송출 경로 명시) — enum 값 변경 없음
- `backend/src/main/java/com/noaats/ifms/global/config/SecurityConfig.java` — `/api/monitor/stream` 401 대신 이벤트 송출 분기용 entry-point 보조

### 수정 / 신규 프런트엔드 파일

- `frontend/src/plugins/vuetify.ts` — 팔레트 재설정 (primary `#1E4FA8` 외 6색)
- `frontend/src/api/types.ts` — `ExecutionLogResponse`·`ExecutionStatus`·`DeltaResponse`·`DashboardResponse` 등 타입 추가
- `frontend/src/api/executions.ts` — **신규** 실행 API 모듈(list, getById, retry, fetchDelta)
- `frontend/src/api/dashboard.ts` — **신규** 대시보드 API 모듈
- `frontend/src/components/StatusChip.vue` — **신규** 상태 뱃지 공용 컴포넌트
- `frontend/src/composables/useExecutionStream.ts` — **신규** SSE 구독/재연결/dedup/RESYNC/UNAUTHORIZED 처리
- `frontend/src/composables/useDashboardPolling.ts` — **신규** debounce refresh + 상태 기반 폴백 polling
- `frontend/src/pages/ExecutionHistory.vue` — **신규**
- `frontend/src/pages/Dashboard.vue` — **신규**
- `frontend/src/router/index.ts` — `/history`·`/dashboard` 라우트 추가 + 로그아웃 시 SSE close guard
- `frontend/src/components/AppShell.vue` — 내비게이션에 Dashboard·ExecutionHistory 항목 추가

### 신규 문서

- `docs/adr/ADR-007-sse-resync-session-boundary.md`
- `docs/DAY5-SUMMARY.md` (역구축)
- `docs/DAY6-SUMMARY.md`

### 수정 문서

- `docs/api-spec.md` — §1.3 ErrorCode 21종, §3.3 delta 섹션, §6.1 SSE UNAUTHORIZED·재할당 공식화
- `docs/backlog.md` — Day 4 이월 회수 + Day 5/6 스트라이크

---

## Phase 0 — 문서 선행

### Task 0-1: ADR-007 작성

**Files:**
- Create: `docs/adr/ADR-007-sse-resync-session-boundary.md`

- [ ] **Step 1: ADR-007 파일 생성**

Architect 판정(R1·R2·R3·R5)을 하나의 ADR로 박제한다.

```markdown
# ADR-007: SSE 재동기화 및 세션 경계 프로토콜

**상태**: 결정됨
**날짜**: 2026-04-20
**관련**: ADR-002(SSE 선택), api-spec §3.3·§6.1, DAY6 spec

## 컨텍스트
Day 4까지 SSE 링버퍼(1000건/5분)와 clientId 메서드(`clientIdBoundToOtherSession`)는 구현됐으나, 실 재연결 경로·장시간 단절 복구·세션 만료 처리가 프런트와 묶여 미완. Day 6에 이 4개 쟁점을 통합 결정.

## 결정

### R1. delta 커서
- `GET /api/executions/delta?since=ISO8601|cursor=base64(ISO8601)&limit=500` (max 1000)
- startedAt 단독 커서, base64 캡슐화
- `limit+1` 조회 → `truncated=true` 판정, 마지막 1건 drop, `nextCursor = last.startedAt` base64

### R2. delta 보안·감사
- since 하한 "지금 - 24시간", 초과 시 400 DELTA_SINCE_TOO_OLD
- actor 60초/10회 in-memory rate limit (ConcurrentHashMap 슬라이딩 윈도우), 초과 시 429 DELTA_RATE_LIMITED
- 성공/실패 모두 감사 로그 1줄(actor·since·returned·truncated·limit)

### R3. clientId 재할당
- `subscribe` 진입 시 동일 clientId가 타 세션에 바인딩 중이면 → 이전 emitter 2초 delayed complete + 새 세션에 즉시 재할당
- grace 2초 동안 이벤트는 새 emitter에만 라우팅
- 감사 1줄: `event=CLIENT_ID_REASSIGNED`

### R5. SSE UNAUTHORIZED
- 세션 만료 감지 시(HttpSessionListener) 해당 세션의 모든 emitter에 `event: UNAUTHORIZED` 송출 후 complete
- 프런트 onmessage에서 close+logout+router push, router.beforeEach에서 보조 close
- 감사 1줄: `event=SSE_DROPPED_ON_SESSION_EXPIRY`

## 근거
- 명세 정합성: EventSource.onerror가 HTTP 상태 미노출이라 401 기반 설계는 구조적 결함
- 1주일 일정: 복합 커서·신규 인덱스를 Day 6 범위에서 제외(인덱스 신설은 운영 이관), 그 여유로 R5를 Day 7 → Day 6로 당김
- append-only 감사 무결성: 조회 감사 1줄, 재할당 감사 1줄, 만료 감사 1줄로 추적 보장

## 트레이드오프
- startedAt 마이크로초 경계 행 1건 유실 가능 — 원본 DB append-only 보존으로 수용
- in-memory rate limit은 단일 인스턴스 전제 — 분산 전환은 운영 이관
- grace 2초 동안 이중 emitter 존재 — Registry 내부에서 새 emitter로만 라우팅 강제

## 기각된 대안
- DBA 복합 커서(startedAt,id) + idx_log_started_at_id_asc 신설: Day 6 범위 초과
- 409 CLIENT_ID_BOUND_TO_OTHER_SESSION 엄격 거절: F5 UX 저하, 세션 하이재킹 탐지 불가
- 프런트 dedup Set: 상태 전이 손실로 감사 표시 일관성 훼손

## 후속
- 복합 커서·분산 rate limit은 운영 이관 backlog
- UNAUTHORIZED 송출 경로의 WAF/프록시 호환성은 Day 7 통합 테스트에서 검증
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/ADR-007-sse-resync-session-boundary.md
git commit -m "docs(adr): ADR-007 SSE 재동기화 및 세션 경계 프로토콜 결정"
```

### Task 0-2: api-spec.md 동기화

**Files:**
- Modify: `docs/api-spec.md` — §1.3 ErrorCode 표 / §3.3 delta 섹션 신설 / §6.1 SSE 재할당·UNAUTHORIZED

- [ ] **Step 1: §1.3 ErrorCode 표에 2종 추가**

`TOO_MANY_CONNECTIONS(429)` 바로 아래 행에 삽입:

```markdown
| DELTA_SINCE_TOO_OLD       | 400 | since 파라미터가 24시간 하한을 초과 |
| DELTA_RATE_LIMITED        | 429 | delta 호출 60초/10회 초과 |
```

총 19 → 21종으로 표 하단 요약 문구 업데이트.

- [ ] **Step 2: §3.3 `/api/executions/delta` 섹션 신설**

기존 §3.3 하위에 다음 추가:

```markdown
### GET /api/executions/delta

링버퍼(5분/1000건) 초과 단절 시 프런트가 `RESYNC_REQUIRED` 수신 후 호출하는 공백 메꾸기 API.

**쿼리 파라미터**
- `since` (ISO-8601 OffsetDateTime) — 최초 호출. 하한 `now - 24h`, 초과 시 `400 DELTA_SINCE_TOO_OLD`
- `cursor` (base64(ISO-8601)) — 2페이지 이후. `since`와 동시 지정 시 `cursor` 우선
- `limit` (기본 500, 최대 1000)

**응답** `ApiResponse<DeltaResponse>`
```json
{
  "success": true,
  "data": {
    "items": [{ "id": 123, "interfaceConfigId": 1, "status": "SUCCESS", "startedAt": "2026-04-20T10:00:00+09:00", "finishedAt": "...", "durationMs": 512, "triggeredBy": "MANUAL", "retryCount": 0, "errorMessage": null }],
    "truncated": false,
    "nextCursor": null
  }
}
```

**보안·감사**
- 세션 인증 필수. actor 필터 없음(운영자 전체 관측 허용).
- actor 기준 60초/10회 rate limit 초과 시 `429 DELTA_RATE_LIMITED`.
- 성공/실패 모두 감사 로그 1줄: `actor={hash} since={iso} returned_count={n} truncated={bool} limit={n}`.

**페이지네이션**
- `limit+1` 서버 조회 후 `truncated = size > limit`. `truncated=true`면 마지막 1건 drop하고 `nextCursor = base64(last.startedAt)`.
- `truncated=false`면 `nextCursor=null`.

**마이크로초 경계**
동일 μs에 다수 행 존재 시 경계 1건 유실 가능 — 원본 DB append-only 보존. RESYNC 재호출로 복구.
```

- [ ] **Step 3: §6.1 SSE 섹션 보강**

기존 §6.1에 다음 항목 추가:

```markdown
### clientId 재할당 (grace 2초)

동일 `clientId`가 다른 세션에 바인딩 중일 때 `subscribe` 진입 시:
1. 이전 세션의 emitter를 2초 delayed complete 스케줄
2. 새 세션에 즉시 재할당, 이후 이벤트는 새 emitter에만 라우팅
3. 감사 로그: `event=CLIENT_ID_REASSIGNED clientId=<uuid> old_session=<hash> new_session=<hash> actor=<hash>`

브라우저 F5·탭 이동 시 이전 emitter complete 지연과의 경쟁 상태를 흡수한다.

### UNAUTHORIZED 이벤트

세션 만료를 `HttpSessionListener`로 감지하면 해당 세션의 모든 emitter에 `event: UNAUTHORIZED` 1회 송출 후 complete. 프런트는 이 이벤트를 수신해 `close()` + logout + `/login` 이동해야 한다. 감사 로그: `event=SSE_DROPPED_ON_SESSION_EXPIRY sessionId=<hash> clientId=<uuid> actor=<hash>`.

EventSource는 HTTP 401을 onerror 이벤트로만 노출하며 status 필드가 없으므로(WHATWG 스펙), 401 응답은 3초 간격 자동 재연결을 유발한다. 이 이벤트 기반 종료 프로토콜은 자동 재연결 루프를 방지한다.
```

- [ ] **Step 4: Commit**

```bash
git add docs/api-spec.md
git commit -m "docs(api-spec): delta API(21종 ErrorCode) + SSE 재할당·UNAUTHORIZED 프로토콜 명세"
```

### Task 0-3: backlog.md 동기화

**Files:**
- Modify: `docs/backlog.md`

- [ ] **Step 1: Day 4 이월 회수 표기**

기존 `SSE UNAUTHORIZED 이벤트 + SSE_DROPPED_ON_SESSION_EXPIRY 감사 로그` 행을 스트라이크 처리(`~~...~~`) + `비고` 칸에 `✅ Day 6 회수 (ADR-007)`.

`SSE clientIdBoundToOtherSession 스푸핑 차단 Controller 호출` 행 스트라이크 + `✅ Day 6 재할당 방식 채택 (ADR-007)`.

`?since= 폴백 쿼리` 행 스트라이크 + `✅ Day 6 /api/executions/delta 신설 (ADR-007)`.

- [ ] **Step 2: Day 5/6 섹션 완료분 스트라이크**

```markdown
## Day 5 (완료)
| ~~`Vuetify 3` 스파이크 2시간 선행~~ | ✅ Day 5 완료 |
| ~~`InterfaceList.vue`, `InterfaceFormDialog.vue`~~ | ✅ Day 5 완료 |
| ~~`OPTIMISTIC_LOCK_CONFLICT` diff 다이얼로그~~ | ✅ Day 5 완료 |

## Day 6 (완료)
| ~~`ExecutionHistory.vue`, `Dashboard.vue`~~ | ✅ Day 6 완료 |
| ~~SSE 클라이언트 재연결 + `Last-Event-ID` 자동 처리~~ | ✅ Day 6 완료 |
```

- [ ] **Step 3: Commit**

```bash
git add docs/backlog.md
git commit -m "docs(backlog): Day 4 이월 SSE 3종 회수 + Day 5/6 완료분 스트라이크"
```

### Task 0-4: DAY5-SUMMARY.md 역구축

**Files:**
- Create: `docs/DAY5-SUMMARY.md`

- [ ] **Step 1: 현재 frontend/ 스냅샷 기반 역구축**

```markdown
# Day 5 완료 요약 — 2026-04-20 (역구축)

> 누락됐던 Day 5 요약을 Day 6 착수 시점에 역구축. 원본 커밋 타임스탬프는 git log 참조.

## 1. 산출물
- `frontend/` Vue 3.5 + Vuetify 3.12 + Vite 8 + TS 6 스캐폴딩
- 화면: `Login.vue`, `InterfaceList.vue`
- 컴포넌트: `AppShell.vue`, `InterfaceFormDialog.vue`, `OptimisticLockDialog.vue`
- API: `client.ts`(Axios + CSRF 쿠키), `auth.ts`, `interfaces.ts`, `types.ts`
- 상태: `stores/auth.ts`, `stores/toast.ts`
- 라우팅: `router/index.ts` (로그인 가드 + redirect)
- 테마: 기본 라이트, primary `#1E3A8A` (Day 6에 `#1E4FA8`로 재도색 예정)

## 2. 검증된 기능
- 로그인/로그아웃 (CSRF 쿠키+헤더 동봉)
- 인터페이스 목록 페이지네이션·필터(status·protocol·name)
- 인터페이스 등록·수정 다이얼로그 + ConfigJsonValidator 연동
- 낙관적 락 충돌 diff 다이얼로그 표출

## 3. Day 6 이월 항목
- ExecutionHistory·Dashboard 화면
- SSE 클라이언트 composable 및 재연결
- 테마 팔레트 갱신(`#1E4FA8`)
- Day 5 화면 5 시나리오 점검

## 4. 누적 통계
- Java 파일 60+ (Day 4 기준 유지)
- Vue 파일 ~10개
- ADR 6종 유지 (Day 6에 ADR-007 추가 예정)
```

- [ ] **Step 2: Commit**

```bash
git add docs/DAY5-SUMMARY.md
git commit -m "docs: Day 5 완료 요약 역구축 (Day 6 착수 시점)"
```

---

## Phase 1 — 백엔드 delta API (TDD)

### Task 1-1: ErrorCode 2종 추가

**Files:**
- Modify: `backend/src/main/java/com/noaats/ifms/global/exception/ErrorCode.java`

- [ ] **Step 1: 기존 테스트 파일이 있으면 추가 테스트 작성, 없으면 Step 2로**

- [ ] **Step 2: ErrorCode enum에 2종 추가**

`TOO_MANY_CONNECTIONS(429, ...)` 라인 직후에 삽입:

```java
    TOO_MANY_CONNECTIONS(429, "연결 상한을 초과했습니다"),
    DELTA_SINCE_TOO_OLD(400, "delta since 파라미터가 24시간 하한을 초과했습니다"),
    DELTA_RATE_LIMITED(429, "delta 호출 빈도 상한(60초/10회)을 초과했습니다"),

    INTERNAL_ERROR(500, ...
```

Javadoc 상단 "17종" → "21종"으로 갱신.

- [ ] **Step 3: 컴파일 확인**

```bash
cd backend && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/global/exception/ErrorCode.java
git commit -m "feat(error-code): DELTA_SINCE_TOO_OLD · DELTA_RATE_LIMITED 2종 추가 (21종)"
```

### Task 1-2: DeltaCursor 유틸 (TDD)

**Files:**
- Create: `backend/src/main/java/com/noaats/ifms/domain/execution/service/DeltaCursor.java`
- Test: `backend/src/test/java/com/noaats/ifms/domain/execution/service/DeltaCursorTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noaats.ifms.global.exception.BusinessException;
import com.noaats.ifms.global.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DeltaCursorTest {

    @Test
    void roundtrip_preserves_timestamp() {
        OffsetDateTime now = OffsetDateTime.of(2026, 4, 20, 10, 30, 45, 123_000_000, ZoneOffset.ofHours(9));
        String encoded = DeltaCursor.encode(now);
        OffsetDateTime decoded = DeltaCursor.decode(encoded);
        assertThat(decoded).isEqualTo(now);
    }

    @Test
    void encoded_is_base64_url_safe() {
        OffsetDateTime t = OffsetDateTime.of(2026, 4, 20, 10, 0, 0, 0, ZoneOffset.ofHours(9));
        String encoded = DeltaCursor.encode(t);
        assertThat(encoded).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    void decode_invalid_base64_throws_validation_failed() {
        assertThatThrownBy(() -> DeltaCursor.decode("!!!not-base64!!!"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void decode_invalid_iso_throws_validation_failed() {
        String bad = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-a-date".getBytes());
        assertThatThrownBy(() -> DeltaCursor.decode(bad))
                .isInstanceOf(BusinessException.class);
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd backend && ./gradlew test --tests DeltaCursorTest
```
Expected: FAIL — `DeltaCursor` 클래스 없음

- [ ] **Step 3: 구현**

```java
package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.global.exception.BusinessException;
import com.noaats.ifms.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;

/** delta API 커서 인코딩 유틸. base64url(ISO-8601 OffsetDateTime) 단일 필드. */
public final class DeltaCursor {
    private DeltaCursor() {}

    public static String encode(OffsetDateTime at) {
        String iso = at.toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iso.getBytes(StandardCharsets.UTF_8));
    }

    public static OffsetDateTime decode(String cursor) {
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "cursor base64 디코딩 실패",
                    Map.of("cursor", cursor));
        }
        String iso = new String(raw, StandardCharsets.UTF_8);
        try {
            return OffsetDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "cursor ISO-8601 파싱 실패",
                    Map.of("cursor", cursor));
        }
    }
}
```

- [ ] **Step 4: 테스트 재실행 — PASS**

```bash
cd backend && ./gradlew test --tests DeltaCursorTest
```
Expected: 4 tests passed

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/execution/service/DeltaCursor.java backend/src/test/java/com/noaats/ifms/domain/execution/service/DeltaCursorTest.java
git commit -m "feat(delta): base64 단일 커서 유틸 DeltaCursor + 4 테스트"
```

### Task 1-3: DeltaRateLimiter (TDD)

**Files:**
- Create: `backend/src/main/java/com/noaats/ifms/domain/execution/service/DeltaRateLimiter.java`
- Test: `backend/src/test/java/com/noaats/ifms/domain/execution/service/DeltaRateLimiterTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DeltaRateLimiterTest {

    @Test
    void allows_up_to_10_in_window_then_rejects_11th() {
        TestClock clock = new TestClock(Instant.parse("2026-04-20T10:00:00Z"));
        DeltaRateLimiter limiter = new DeltaRateLimiter(clock, Duration.ofSeconds(60), 10);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("actor-A")).isTrue();
        }
        assertThat(limiter.tryAcquire("actor-A")).isFalse();
    }

    @Test
    void different_actors_have_independent_buckets() {
        TestClock clock = new TestClock(Instant.parse("2026-04-20T10:00:00Z"));
        DeltaRateLimiter limiter = new DeltaRateLimiter(clock, Duration.ofSeconds(60), 10);
        for (int i = 0; i < 10; i++) limiter.tryAcquire("A");
        assertThat(limiter.tryAcquire("B")).isTrue();
    }

    @Test
    void window_sliding_frees_oldest_after_60s() {
        TestClock clock = new TestClock(Instant.parse("2026-04-20T10:00:00Z"));
        DeltaRateLimiter limiter = new DeltaRateLimiter(clock, Duration.ofSeconds(60), 10);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("A");
        }
        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire("A")).isTrue();
    }

    static class TestClock extends Clock {
        private Instant now;
        TestClock(Instant start) { this.now = start; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd backend && ./gradlew test --tests DeltaRateLimiterTest
```
Expected: FAIL — `DeltaRateLimiter` 없음

- [ ] **Step 3: 구현**

```java
package com.noaats.ifms.domain.execution.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * actor 기준 슬라이딩 윈도우 rate limiter (in-memory).
 * <p>ADR-007 R2 — 분산 rate limit은 운영 이관 대상, 프로토타입은 단일 인스턴스 전제.</p>
 */
@Component
public class DeltaRateLimiter {

    private final Clock clock;
    private final Duration window;
    private final int capacity;
    private final Map<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    public DeltaRateLimiter(Clock clock,
                            @Value("${ifms.delta.rate-window:PT60S}") Duration window,
                            @Value("${ifms.delta.rate-capacity:10}") int capacity) {
        this.clock = clock;
        this.window = window;
        this.capacity = capacity;
    }

    public boolean tryAcquire(String actorKey) {
        Instant now = clock.instant();
        Deque<Instant> dq = buckets.computeIfAbsent(actorKey, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && dq.peekFirst().isBefore(now.minus(window))) {
                dq.pollFirst();
            }
            if (dq.size() >= capacity) return false;
            dq.addLast(now);
            return true;
        }
    }
}
```

- [ ] **Step 4: Clock 빈 확인/등록**

`global/config/` 패키지에 Clock 빈이 없으면 다음을 `AsyncConfig.java` 또는 신규 `ClockConfig.java`에 추가:

```java
@Bean
public Clock systemClock() {
    return Clock.systemDefaultZone();
}
```

기존 빈 존재 여부는 다음으로 확인:

```bash
cd backend && grep -r "Clock systemClock\|@Bean.*Clock" src/main/java/
```

존재하면 스킵, 없으면 `global/config/ClockConfig.java` 신규 작성:

```java
package com.noaats.ifms.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
```

- [ ] **Step 5: application.yml에 기본값 추가**

`backend/src/main/resources/application.yml`의 `ifms:` 하위에 추가:

```yaml
ifms:
  delta:
    rate-window: PT60S
    rate-capacity: 10
    since-lower-bound: PT24H
    default-limit: 500
    max-limit: 1000
```

- [ ] **Step 6: 테스트 재실행 — PASS**

```bash
cd backend && ./gradlew test --tests DeltaRateLimiterTest
```
Expected: 3 tests passed

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/execution/service/DeltaRateLimiter.java backend/src/test/java/com/noaats/ifms/domain/execution/service/DeltaRateLimiterTest.java backend/src/main/resources/application.yml backend/src/main/java/com/noaats/ifms/global/config/ClockConfig.java
git commit -m "feat(delta): in-memory 슬라이딩 윈도우 DeltaRateLimiter + 3 테스트"
```

### Task 1-4: ExecutionLogResponse + DeltaResponse DTO

**Files:**
- Create: `backend/src/main/java/com/noaats/ifms/domain/execution/dto/ExecutionLogResponse.java`
- Create: `backend/src/main/java/com/noaats/ifms/domain/execution/dto/DeltaResponse.java`

- [ ] **Step 1: ExecutionLogResponse record 작성**

기존 엔티티 필드 확인:

```bash
cd backend && cat src/main/java/com/noaats/ifms/domain/execution/domain/ExecutionLog.java | head -80
```

확인 후 record 작성:

```java
package com.noaats.ifms.domain.execution.dto;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.domain.TriggeredBy;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 실행 로그 응답 DTO — 리스트/델타/상세 공통 사용.
 * <p>엔티티의 {@link java.time.LocalDateTime}은 Asia/Seoul 기준 {@link OffsetDateTime}으로 변환.</p>
 */
public record ExecutionLogResponse(
        Long id,
        Long interfaceConfigId,
        String interfaceName,
        ExecutionStatus status,
        TriggeredBy triggeredBy,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Long durationMs,
        int retryCount,
        Long parentLogId,
        String errorMessage
) {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static ExecutionLogResponse of(ExecutionLog e, String interfaceName) {
        return new ExecutionLogResponse(
                e.getId(),
                e.getInterfaceConfigId(),
                interfaceName,
                e.getStatus(),
                e.getTriggeredBy(),
                e.getStartedAt() == null ? null : e.getStartedAt().atZone(KST).toOffsetDateTime(),
                e.getFinishedAt() == null ? null : e.getFinishedAt().atZone(KST).toOffsetDateTime(),
                e.getDurationMs(),
                e.getRetryCount(),
                e.getParentLogId(),
                e.getErrorMessage()
        );
    }
}
```

> 실제 엔티티 필드명이 다르면 `ExecutionLog` 게터에 맞춰 수정. 필드 누락 시 Day 5까지의 상세 응답 DTO가 이미 있는지 `ExecutionLogDetailResponse` 같은 이름으로 검색:
>
> ```bash
> grep -r "record.*Execution.*Response" backend/src/main/java/com/noaats/ifms/domain/execution/dto/
> ```
>
> 이미 유사 record가 있으면 Day 6 변경은 필드 추가에 그치고, 이 record는 스킵한다.

- [ ] **Step 2: DeltaResponse record 작성**

```java
package com.noaats.ifms.domain.execution.dto;

import java.util.List;

/** /api/executions/delta 응답. */
public record DeltaResponse(
        List<ExecutionLogResponse> items,
        boolean truncated,
        String nextCursor
) {}
```

- [ ] **Step 3: 컴파일 확인**

```bash
cd backend && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/execution/dto/
git commit -m "feat(execution): ExecutionLogResponse + DeltaResponse record"
```

### Task 1-5: Repository delta 쿼리

**Files:**
- Modify: `backend/src/main/java/com/noaats/ifms/domain/execution/repository/ExecutionLogRepository.java`

- [ ] **Step 1: 기존 Repository 확인**

```bash
cd backend && cat src/main/java/com/noaats/ifms/domain/execution/repository/ExecutionLogRepository.java
```

- [ ] **Step 2: delta 쿼리 메서드 추가**

Repository 인터페이스 끝에 삽입:

```java
    /**
     * delta 조회 — started_at >= since, ASC 정렬, limit+1 건 반환(truncated 판정용).
     * 기존 idx_log_started_at_desc는 양방향 스캔 가능하므로 ASC에도 효과적.
     */
    @Query(value = """
            SELECT el FROM ExecutionLog el
            WHERE el.startedAt >= :since
            ORDER BY el.startedAt ASC
            """)
    List<ExecutionLog> findDeltaSince(
            @org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since,
            org.springframework.data.domain.Pageable pageable);

    /**
     * 리스트 페이지네이션 조회 — ExecutionHistory.vue에서 사용.
     * status·interfaceConfigId 필터 지원. name은 상위 서비스에서 interfaceConfigId로 전환.
     */
    @Query("""
            SELECT el FROM ExecutionLog el
            WHERE (:status IS NULL OR el.status = :status)
              AND (:configId IS NULL OR el.interfaceConfigId = :configId)
            """)
    org.springframework.data.domain.Page<ExecutionLog> findList(
            @org.springframework.data.repository.query.Param("status") com.noaats.ifms.domain.execution.domain.ExecutionStatus status,
            @org.springframework.data.repository.query.Param("configId") Long configId,
            org.springframework.data.domain.Pageable pageable);
```

필요한 import 추가:
```java
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
```

- [ ] **Step 3: 컴파일**

```bash
cd backend && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/execution/repository/ExecutionLogRepository.java
git commit -m "feat(execution): Repository findDeltaSince + findList 쿼리 추가"
```

### Task 1-6: DeltaService (TDD)

**Files:**
- Create: `backend/src/main/java/com/noaats/ifms/domain/execution/service/DeltaService.java`
- Test: `backend/src/test/java/com/noaats/ifms/domain/execution/service/DeltaServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성 (JPA + @DataJpaTest or 수동 mock)**

프로젝트에 Testcontainers가 아직 없으므로 간단한 수동 mock 방식 선택. 단위 테스트:

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.domain.TriggeredBy;
import com.noaats.ifms.domain.execution.dto.DeltaResponse;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import com.noaats.ifms.global.exception.BusinessException;
import com.noaats.ifms.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class DeltaServiceTest {

    @Mock ExecutionLogRepository logRepo;
    @Mock InterfaceConfigRepository configRepo;
    @Mock DeltaRateLimiter limiter;

    Clock clock;
    DeltaService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-20T10:00:00Z"), ZoneId.of("UTC"));
        service = new DeltaService(logRepo, configRepo, limiter, clock, Duration.ofHours(24), 500, 1000);
    }

    @Test
    void rejects_since_older_than_24h() {
        when(limiter.tryAcquire(any())).thenReturn(true);
        OffsetDateTime since = OffsetDateTime.parse("2026-04-18T00:00:00+09:00"); // >24h
        assertThatThrownBy(() -> service.query("actor-A", since, null, 500))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DELTA_SINCE_TOO_OLD);
    }

    @Test
    void rejects_when_rate_limited() {
        when(limiter.tryAcquire("actor-A")).thenReturn(false);
        OffsetDateTime since = OffsetDateTime.parse("2026-04-20T09:00:00+00:00");
        assertThatThrownBy(() -> service.query("actor-A", since, null, 500))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DELTA_RATE_LIMITED);
    }

    @Test
    void returns_truncated_when_size_exceeds_limit() {
        when(limiter.tryAcquire(any())).thenReturn(true);
        List<ExecutionLog> fixture = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fixture.add(fakeLog((long) i, LocalDateTime.of(2026, 4, 20, 9, 0, i)));
        }
        when(logRepo.findDeltaSince(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(fixture);
        when(configRepo.findById(any())).thenReturn(java.util.Optional.empty());

        OffsetDateTime since = OffsetDateTime.parse("2026-04-20T09:00:00+00:00");
        DeltaResponse res = service.query("actor-A", since, null, 5);

        assertThat(res.items()).hasSize(5);
        assertThat(res.truncated()).isTrue();
        assertThat(res.nextCursor()).isNotNull();
    }

    @Test
    void truncated_false_when_size_equals_limit_no_extra() {
        when(limiter.tryAcquire(any())).thenReturn(true);
        List<ExecutionLog> fixture = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            fixture.add(fakeLog((long) i, LocalDateTime.of(2026, 4, 20, 9, 0, i)));
        }
        when(logRepo.findDeltaSince(any(), any())).thenReturn(fixture);

        OffsetDateTime since = OffsetDateTime.parse("2026-04-20T09:00:00+00:00");
        DeltaResponse res = service.query("actor-A", since, null, 5);

        assertThat(res.items()).hasSize(3);
        assertThat(res.truncated()).isFalse();
        assertThat(res.nextCursor()).isNull();
    }

    private ExecutionLog fakeLog(Long id, LocalDateTime started) {
        ExecutionLog e = new ExecutionLog();
        setField(e, "id", id);
        setField(e, "interfaceConfigId", 1L);
        setField(e, "status", ExecutionStatus.SUCCESS);
        setField(e, "triggeredBy", TriggeredBy.MANUAL);
        setField(e, "startedAt", started);
        setField(e, "retryCount", 0);
        return e;
    }

    private static void setField(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = findField(target.getClass(), field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    private static java.lang.reflect.Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}
```

> `ExecutionLog`에 기본 생성자가 없거나 필드명이 다르면, 실제 엔티티 구조에 맞춰 조정. 필요시 builder 사용.

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd backend && ./gradlew test --tests DeltaServiceTest
```
Expected: FAIL — DeltaService 없음

- [ ] **Step 3: 구현**

```java
package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.dto.DeltaResponse;
import com.noaats.ifms.domain.execution.dto.ExecutionLogResponse;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import com.noaats.ifms.global.exception.BusinessException;
import com.noaats.ifms.global.exception.ConflictException;
import com.noaats.ifms.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * delta 조회 서비스 — ADR-007 R1·R2.
 */
@Slf4j
@Service
public class DeltaService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ExecutionLogRepository logRepo;
    private final InterfaceConfigRepository configRepo;
    private final DeltaRateLimiter limiter;
    private final Clock clock;
    private final Duration sinceLowerBound;
    private final int defaultLimit;
    private final int maxLimit;

    public DeltaService(ExecutionLogRepository logRepo,
                        InterfaceConfigRepository configRepo,
                        DeltaRateLimiter limiter,
                        Clock clock,
                        @Value("${ifms.delta.since-lower-bound:PT24H}") Duration sinceLowerBound,
                        @Value("${ifms.delta.default-limit:500}") int defaultLimit,
                        @Value("${ifms.delta.max-limit:1000}") int maxLimit) {
        this.logRepo = logRepo;
        this.configRepo = configRepo;
        this.limiter = limiter;
        this.clock = clock;
        this.sinceLowerBound = sinceLowerBound;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
    }

    public DeltaResponse query(String actor, OffsetDateTime sinceParam, String cursor, Integer limit) {
        if (!limiter.tryAcquire(actor)) {
            throw new ConflictException(ErrorCode.DELTA_RATE_LIMITED);
        }

        OffsetDateTime effectiveSince = (cursor != null && !cursor.isBlank())
                ? DeltaCursor.decode(cursor)
                : sinceParam;
        if (effectiveSince == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "since 또는 cursor 필수", Map.of());
        }

        OffsetDateTime lower = OffsetDateTime.now(clock).minus(sinceLowerBound);
        if (effectiveSince.isBefore(lower)) {
            throw new BusinessException(
                    ErrorCode.DELTA_SINCE_TOO_OLD,
                    "since는 %s 이후여야 합니다".formatted(lower),
                    Map.of("since", effectiveSince.toString(), "lowerBound", lower.toString()));
        }

        int effective = (limit == null) ? defaultLimit : Math.min(limit, maxLimit);
        LocalDateTime sinceLocal = effectiveSince.atZoneSameInstant(KST).toLocalDateTime();

        List<ExecutionLog> rows = logRepo.findDeltaSince(sinceLocal, PageRequest.of(0, effective + 1));
        boolean truncated = rows.size() > effective;
        List<ExecutionLog> kept = truncated ? rows.subList(0, effective) : rows;

        // 인터페이스 이름 일괄 조회
        Map<Long, String> nameMap = configRepo.findAllById(
                kept.stream().map(ExecutionLog::getInterfaceConfigId).distinct().toList()
        ).stream().collect(Collectors.toMap(InterfaceConfig::getId, InterfaceConfig::getName));

        List<ExecutionLogResponse> items = kept.stream()
                .map(e -> ExecutionLogResponse.of(e, nameMap.getOrDefault(e.getInterfaceConfigId(), "(삭제됨)")))
                .toList();

        String nextCursor = truncated
                ? DeltaCursor.encode(kept.get(kept.size() - 1).getStartedAt().atZone(KST).toOffsetDateTime())
                : null;

        log.info("delta actor={} since={} returned_count={} truncated={} limit={}",
                actor, effectiveSince, items.size(), truncated, effective);

        return new DeltaResponse(items, truncated, nextCursor);
    }
}
```

> `ConflictException(ErrorCode)` 생성자 시그니처 확인 필요. 없으면 기존 `new ConflictException(ErrorCode.X, "...", Map.of())` 형태 사용.

- [ ] **Step 4: 테스트 재실행 — PASS**

```bash
cd backend && ./gradlew test --tests DeltaServiceTest
```
Expected: 4 tests passed

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/execution/service/DeltaService.java backend/src/test/java/com/noaats/ifms/domain/execution/service/DeltaServiceTest.java
git commit -m "feat(delta): DeltaService 쿼리+감사+rate limit+since 하한 (4 테스트)"
```

### Task 1-7: ExecutionQueryService (리스트 조회)

**Files:**
- Create: `backend/src/main/java/com/noaats/ifms/domain/execution/service/ExecutionQueryService.java`
- Create: `backend/src/main/java/com/noaats/ifms/domain/execution/dto/ExecutionListParams.java`

- [ ] **Step 1: ExecutionListParams 작성**

```java
package com.noaats.ifms.domain.execution.dto;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import org.springframework.data.domain.Pageable;

public record ExecutionListParams(
        ExecutionStatus status,
        Long interfaceConfigId,
        Pageable pageable
) {}
```

- [ ] **Step 2: ExecutionQueryService 작성**

```java
package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.dto.ExecutionListParams;
import com.noaats.ifms.domain.execution.dto.ExecutionLogResponse;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.global.exception.NotFoundException;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExecutionQueryService {

    private final ExecutionLogRepository logRepo;
    private final InterfaceConfigRepository configRepo;

    public Page<ExecutionLogResponse> list(ExecutionListParams params) {
        Page<ExecutionLog> page = logRepo.findList(params.status(), params.interfaceConfigId(), params.pageable());
        Map<Long, String> names = configRepo.findAllById(
                page.getContent().stream().map(ExecutionLog::getInterfaceConfigId).distinct().toList()
        ).stream().collect(Collectors.toMap(c -> c.getId(), c -> c.getName()));
        return page.map(e -> ExecutionLogResponse.of(e, names.getOrDefault(e.getInterfaceConfigId(), "(삭제됨)")));
    }

    public ExecutionLogResponse detail(Long id) {
        ExecutionLog e = logRepo.findById(id)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EXECUTION_NOT_FOUND));
        String name = configRepo.findById(e.getInterfaceConfigId())
                .map(c -> c.getName()).orElse("(삭제됨)");
        return ExecutionLogResponse.of(e, name);
    }
}
```

> `NotFoundException(ErrorCode)` 시그니처는 기존 사용 패턴 따름.

- [ ] **Step 3: 컴파일**

```bash
cd backend && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/execution/service/ExecutionQueryService.java backend/src/main/java/com/noaats/ifms/domain/execution/dto/ExecutionListParams.java
git commit -m "feat(execution): ExecutionQueryService (리스트·상세 조회)"
```

### Task 1-8: ExecutionController 엔드포인트 3종 추가

**Files:**
- Modify: `backend/src/main/java/com/noaats/ifms/domain/execution/controller/ExecutionController.java`

- [ ] **Step 1: Controller 확장**

기존 `retry` 메서드 아래에 추가:

```java
    private final ExecutionQueryService queryService;
    private final DeltaService deltaService;

    // 생성자 주입은 @RequiredArgsConstructor가 처리.

    @GetMapping
    @Operation(summary = "실행 이력 리스트 조회 (페이지네이션+필터)")
    public ApiResponse<org.springframework.data.domain.Page<ExecutionLogResponse>> list(
            @RequestParam(required = false) ExecutionStatus status,
            @RequestParam(required = false) Long interfaceConfigId,
            @PageableDefault(size = 20, sort = "startedAt",
                    direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(queryService.list(new ExecutionListParams(status, interfaceConfigId, pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "실행 로그 단건 조회")
    public ApiResponse<ExecutionLogResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(queryService.detail(id));
    }

    @GetMapping("/delta")
    @Operation(summary = "실행 로그 델타 조회 (RESYNC 폴백, ADR-007)")
    public ApiResponse<DeltaResponse> delta(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        String actor = actorContext.resolveActor(request);
        return ApiResponse.success(deltaService.query(actor, since, cursor, limit));
    }
```

필요한 import:

```java
import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.dto.DeltaResponse;
import com.noaats.ifms.domain.execution.dto.ExecutionListParams;
import com.noaats.ifms.domain.execution.dto.ExecutionLogResponse;
import com.noaats.ifms.domain.execution.service.DeltaService;
import com.noaats.ifms.domain.execution.service.ExecutionQueryService;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
```

- [ ] **Step 2: 컴파일 + 전체 테스트**

```bash
cd backend && ./gradlew build -x test && ./gradlew test --tests "com.noaats.ifms.domain.execution.*"
```
Expected: BUILD SUCCESSFUL, 기존 테스트 PASS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/execution/controller/ExecutionController.java
git commit -m "feat(api): GET /api/executions{,/{id},/delta} 3종 엔드포인트 (ADR-007)"
```

---

## Phase 2 — SSE 세션 경계 (TDD)

### Task 2-1: SseEmitterRegistry 재할당 보조 메서드

**Files:**
- Modify: `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterRegistry.java`

- [ ] **Step 1: findEmitter + findSession 추가**

기존 `clientIdBoundToOtherSession` 아래에:

```java
    /** clientId를 보유한 다른 세션(이 세션 제외)의 (sessionId, emitter)를 반환. 없으면 null. */
    public SessionEmitter findOtherSessionByClientId(String excludeSessionId, String clientId) {
        for (Map.Entry<String, ConcurrentMap<String, SseEmitter>> e : bySession.entrySet()) {
            if (!e.getKey().equals(excludeSessionId) && e.getValue().containsKey(clientId)) {
                return new SessionEmitter(e.getKey(), e.getValue().get(clientId));
            }
        }
        return null;
    }

    /** 세션의 모든 emitter 스냅샷 — UNAUTHORIZED 송출용. */
    public List<SseEmitter> snapshotBySession(String sessionId) {
        ConcurrentMap<String, SseEmitter> m = bySession.get(sessionId);
        return m == null ? List.of() : new ArrayList<>(m.values());
    }

    public record SessionEmitter(String sessionId, SseEmitter emitter) {}
```

- [ ] **Step 2: 컴파일**

```bash
cd backend && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterRegistry.java
git commit -m "feat(sse): Registry findOtherSessionByClientId + snapshotBySession"
```

### Task 2-2: SseReassignmentScheduler (TDD)

**Files:**
- Create: `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseReassignmentScheduler.java`
- Test: `backend/src/test/java/com/noaats/ifms/domain/monitor/sse/SseReassignmentTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.noaats.ifms.domain.monitor.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseReassignmentSchedulerTest {

    @Test
    void schedules_complete_after_grace() throws Exception {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        SseReassignmentScheduler s = new SseReassignmentScheduler(sched, java.time.Duration.ofMillis(200));
        SseEmitter em = new SseEmitter(5000L);
        boolean[] completed = { false };
        em.onCompletion(() -> completed[0] = true);

        s.scheduleComplete(em);
        assertThat(completed[0]).isFalse();
        Thread.sleep(400);
        assertThat(completed[0]).isTrue();

        sched.shutdown();
    }

    @Test
    void double_complete_is_safe() throws Exception {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        SseReassignmentScheduler s = new SseReassignmentScheduler(sched, java.time.Duration.ofMillis(100));
        SseEmitter em = new SseEmitter(5000L);
        em.complete();
        s.scheduleComplete(em);
        Thread.sleep(250);
        // no exception thrown
        sched.shutdown();
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd backend && ./gradlew test --tests SseReassignmentSchedulerTest
```
Expected: FAIL — class 없음

- [ ] **Step 3: 구현**

```java
package com.noaats.ifms.domain.monitor.sse;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** clientId 재할당 시 이전 emitter를 grace 시간 후 complete 하는 단일 스케줄러. */
@Slf4j
@Component
public class SseReassignmentScheduler {

    private final ScheduledExecutorService scheduler;
    private final Duration grace;

    public SseReassignmentScheduler(@Value("${ifms.sse.reassign-grace:PT2S}") Duration grace) {
        this(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-reassign-scheduler");
            t.setDaemon(true);
            return t;
        }), grace);
    }

    // 테스트용 생성자
    SseReassignmentScheduler(ScheduledExecutorService scheduler, Duration grace) {
        this.scheduler = scheduler;
        this.grace = grace;
    }

    public void scheduleComplete(SseEmitter emitter) {
        scheduler.schedule(() -> {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("grace complete 실패(이미 완료일 수 있음): {}", e.getMessage());
            }
        }, grace.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
```

- [ ] **Step 4: application.yml에 grace 기본값 추가**

기존 `ifms.sse:` 아래에:

```yaml
ifms:
  sse:
    # ... 기존 ...
    reassign-grace: PT2S
```

- [ ] **Step 5: 테스트 재실행 — PASS**

```bash
cd backend && ./gradlew test --tests SseReassignmentSchedulerTest
```
Expected: 2 tests passed

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseReassignmentScheduler.java backend/src/test/java/com/noaats/ifms/domain/monitor/sse/SseReassignmentSchedulerTest.java backend/src/main/resources/application.yml
git commit -m "feat(sse): SseReassignmentScheduler 단일 스레드 grace complete + 2 테스트"
```

### Task 2-3: SseEmitterService subscribe 재할당 로직

**Files:**
- Modify: `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterService.java`

- [ ] **Step 1: subscribe 메서드 재할당 분기 추가**

생성자에 `SseReassignmentScheduler` 주입:

```java
    private final SseReassignmentScheduler reassignScheduler;

    public SseEmitterService(SseEmitterRegistry registry,
                             SseRingBuffer ringBuffer,
                             SseProperties props,
                             SseReassignmentScheduler reassignScheduler) {
        this.registry = registry;
        this.ringBuffer = ringBuffer;
        this.props = props;
        this.reassignScheduler = reassignScheduler;
    }
```

`subscribe` 메서드 맨 위(emitter 생성 전)에 재할당 분기:

```java
    public SseEmitter subscribe(String sessionId, String actorId, String clientId, Long lastEventId) {
        // ADR-007 R3: 다른 세션이 이 clientId 보유 시 grace complete + 재할당
        var prev = registry.findOtherSessionByClientId(sessionId, clientId);
        if (prev != null) {
            log.info("event=CLIENT_ID_REASSIGNED clientId={} old_session={} new_session={} actor={}",
                    clientId, hash(prev.sessionId()), hash(sessionId), hash(actorId));
            registry.unregister(prev.sessionId(), clientId);
            reassignScheduler.scheduleComplete(prev.emitter());
        }

        SseEmitter emitter = new SseEmitter(props.emitterTimeout().toMillis());
        // ... 기존 onCompletion·onTimeout·onError·register ...
        // 기존 코드 유지
    }

    private static String hash(String s) {
        if (s == null) return "null";
        return Integer.toHexString(s.hashCode()); // 감사 로그용 간이 해시, 실 해시 필요 시 SHA-256
    }
```

- [ ] **Step 2: UNAUTHORIZED 송출 메서드 추가**

Service 끝에 추가:

```java
    /** 세션 단위 UNAUTHORIZED 이벤트 송출 후 모든 emitter complete. ADR-007 R5. */
    public void publishUnauthorizedAndClose(String sessionId) {
        var emitters = registry.snapshotBySession(sessionId);
        if (emitters.isEmpty()) return;
        SseEvent event = ringBuffer.append(SseEventType.UNAUTHORIZED,
                Map.of("reason", "SESSION_EXPIRED"));
        for (SseEmitter em : emitters) {
            sendTo(em, event);
            try { em.complete(); } catch (Exception ignore) {}
        }
        log.info("event=SSE_DROPPED_ON_SESSION_EXPIRY sessionId={} count={}", hash(sessionId), emitters.size());
    }
```

- [ ] **Step 3: 컴파일**

```bash
cd backend && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterService.java
git commit -m "feat(sse): subscribe clientId 재할당 흐름 + publishUnauthorizedAndClose (ADR-007)"
```

### Task 2-4: HttpSessionListener 통합 (TDD)

**Files:**
- Create: `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseSessionExpiryListener.java`
- Test: `backend/src/test/java/com/noaats/ifms/domain/monitor/sse/SseUnauthorizedTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.noaats.ifms.domain.monitor.sse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.Test;

class SseSessionExpiryListenerTest {

    @Test
    void on_session_destroyed_calls_publishUnauthorizedAndClose() {
        SseEmitterService svc = mock(SseEmitterService.class);
        SseSessionExpiryListener listener = new SseSessionExpiryListener(svc);

        HttpSession session = mock(HttpSession.class);
        when(session.getId()).thenReturn("sess-123");
        HttpSessionEvent event = new HttpSessionEvent(session);

        listener.sessionDestroyed(event);

        verify(svc).publishUnauthorizedAndClose("sess-123");
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd backend && ./gradlew test --tests SseSessionExpiryListenerTest
```
Expected: FAIL — class 없음

- [ ] **Step 3: 구현**

```java
package com.noaats.ifms.domain.monitor.sse;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.stereotype.Component;

/** 세션 만료 시 해당 세션의 모든 SSE emitter에 UNAUTHORIZED 이벤트 송출. ADR-007 R5. */
@Component
public class SseSessionExpiryListener implements HttpSessionListener {

    private final SseEmitterService service;

    public SseSessionExpiryListener(SseEmitterService service) {
        this.service = service;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        service.publishUnauthorizedAndClose(se.getSession().getId());
    }
}
```

- [ ] **Step 4: 테스트 재실행 — PASS**

```bash
cd backend && ./gradlew test --tests SseSessionExpiryListenerTest
```
Expected: 1 test passed

- [ ] **Step 5: 전체 테스트 확인**

```bash
cd backend && ./gradlew test
```
Expected: 기존 + 신규 모두 PASS. ArchUnit 3종 PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseSessionExpiryListener.java backend/src/test/java/com/noaats/ifms/domain/monitor/sse/SseUnauthorizedTest.java
git commit -m "feat(sse): HttpSessionListener로 세션 만료 시 UNAUTHORIZED 이벤트 송출"
```

---

## Phase 3 — 프런트엔드 공용 (TDD 없음, 컴파일·타입체크 기준)

### Task 3-1: Vuetify 테마 재도색

**Files:**
- Modify: `frontend/src/plugins/vuetify.ts`

- [ ] **Step 1: 팔레트 교체**

```ts
light: {
  colors: {
    primary:    '#1E4FA8',
    secondary:  '#3A6FCE',
    success:    '#1E8A4C',
    error:      '#C62828',
    warning:    '#ED8936',
    info:       '#1E88E5',
    surface:    '#FFFFFF',
    background: '#F4F6FA',
  },
},
```

- [ ] **Step 2: 빌드 확인**

```bash
cd frontend && npm run build
```
Expected: 성공

- [ ] **Step 3: Commit**

```bash
git add frontend/src/plugins/vuetify.ts
git commit -m "feat(ui): Vuetify 테마 금융 톤 재도색 (primary #1E4FA8 + 상태 4색)"
```

### Task 3-2: API types 확장 + API 모듈 신설

**Files:**
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/api/executions.ts`
- Create: `frontend/src/api/dashboard.ts`

- [ ] **Step 1: types.ts에 타입 추가**

파일 끝에 추가:

```ts
export type ExecutionStatus = 'RUNNING' | 'SUCCESS' | 'FAILED' | 'RECOVERED'
export type TriggeredBy = 'MANUAL' | 'SCHEDULER' | 'RETRY'

export interface ExecutionLogResponse {
  id: number
  interfaceConfigId: number
  interfaceName: string
  status: ExecutionStatus
  triggeredBy: TriggeredBy
  startedAt: string
  finishedAt: string | null
  durationMs: number | null
  retryCount: number
  parentLogId: number | null
  errorMessage: string | null
}

export interface ExecutionListParams {
  page?: number
  size?: number
  sort?: string
  status?: ExecutionStatus
  interfaceConfigId?: number
}

export interface DeltaResponse {
  items: ExecutionLogResponse[]
  truncated: boolean
  nextCursor: string | null
}

export interface DashboardTotals {
  total: number
  running: number
  success: number
  failed: number
}
export interface DashboardProtocolStat {
  protocol: string
  running: number
  success: number
  failed: number
}
export interface DashboardRecentFailure {
  id: number
  interfaceName: string
  errorMessage: string | null
  finishedAt: string | null
}
export interface DashboardResponse {
  totals: DashboardTotals
  byProtocol: DashboardProtocolStat[]
  recentFailures: DashboardRecentFailure[]
  sseConnections: number
  since: string
}
```

> 실제 백엔드 `DashboardResponse` 필드 이름이 다르면 해당 파일(`backend/.../dto/DashboardResponse.java`) 기준으로 동기화.

- [ ] **Step 2: executions.ts 작성**

```ts
import { api } from './client'
import type {
  DeltaResponse,
  ExecuteResponse,
  ExecutionListParams,
  ExecutionLogResponse,
  Page,
} from './types'

export async function listExecutions(
  params: ExecutionListParams = {},
): Promise<Page<ExecutionLogResponse>> {
  const res = await api.get<Page<ExecutionLogResponse>>('/api/executions', {
    params: {
      page: params.page ?? 0,
      size: params.size ?? 20,
      sort: params.sort ?? 'startedAt,desc',
      status: params.status,
      interfaceConfigId: params.interfaceConfigId,
    },
  })
  return res.data
}

export async function getExecution(id: number): Promise<ExecutionLogResponse> {
  const res = await api.get<ExecutionLogResponse>(`/api/executions/${id}`)
  return res.data
}

export async function retryExecution(id: number): Promise<ExecuteResponse> {
  const res = await api.post<ExecuteResponse>(`/api/executions/${id}/retry`)
  return res.data
}

export async function fetchDelta(
  since?: string,
  cursor?: string,
  limit = 500,
): Promise<DeltaResponse> {
  const res = await api.get<DeltaResponse>('/api/executions/delta', {
    params: { since, cursor, limit },
  })
  return res.data
}
```

- [ ] **Step 3: dashboard.ts 작성**

```ts
import { api } from './client'
import type { DashboardResponse } from './types'

export async function getDashboard(since?: string): Promise<DashboardResponse> {
  const res = await api.get<DashboardResponse>('/api/monitor/dashboard', {
    params: { since },
  })
  return res.data
}
```

- [ ] **Step 4: 빌드 확인**

```bash
cd frontend && npm run build
```
Expected: 성공

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/
git commit -m "feat(frontend): executions·dashboard API 모듈 + types 확장"
```

### Task 3-3: StatusChip 컴포넌트

**Files:**
- Create: `frontend/src/components/StatusChip.vue`

- [ ] **Step 1: 컴포넌트 작성**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import type { ExecutionStatus } from '@/api/types'

const props = defineProps<{ status: ExecutionStatus }>()

const meta = computed(() => {
  switch (props.status) {
    case 'RUNNING':   return { color: 'info',    icon: 'mdi-play-circle', label: 'RUNNING' }
    case 'SUCCESS':   return { color: 'success', icon: 'mdi-check-circle', label: 'SUCCESS' }
    case 'FAILED':    return { color: 'error',   icon: 'mdi-alert-circle', label: 'FAILED' }
    case 'RECOVERED': return { color: 'warning', icon: 'mdi-restore',      label: 'RECOVERED' }
  }
})
</script>

<template>
  <v-chip :color="meta.color" size="small" variant="flat" :prepend-icon="meta.icon">
    {{ meta.label }}
  </v-chip>
</template>
```

- [ ] **Step 2: 빌드**

```bash
cd frontend && npm run build
```
Expected: 성공

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/StatusChip.vue
git commit -m "feat(ui): StatusChip 공용 상태 뱃지 컴포넌트"
```

### Task 3-4: useExecutionStream composable

**Files:**
- Create: `frontend/src/composables/useExecutionStream.ts`

- [ ] **Step 1: composable 작성**

```ts
import { onBeforeUnmount, ref, shallowRef } from 'vue'
import type { ExecutionLogResponse, ExecutionStatus } from '@/api/types'
import { fetchDelta } from '@/api/executions'

const CLIENT_ID_KEY = 'sse.clientId'

type ConnState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface StreamEvent {
  type: 'EXECUTION_STARTED' | 'EXECUTION_SUCCESS' | 'EXECUTION_FAILED' | 'EXECUTION_RECOVERED'
        | 'HEARTBEAT' | 'CONNECTED' | 'RESYNC_REQUIRED' | 'UNAUTHORIZED'
  payload: Record<string, unknown>
  timestamp: string
}

export interface UseExecutionStreamHandlers {
  onStarted?: (e: ExecutionLogResponse | Partial<ExecutionLogResponse>) => void
  onSuccess?: (e: ExecutionLogResponse | Partial<ExecutionLogResponse>) => void
  onFailed?: (e: ExecutionLogResponse | Partial<ExecutionLogResponse>) => void
  onRecovered?: (e: ExecutionLogResponse | Partial<ExecutionLogResponse>) => void
  onHeartbeat?: () => void
  onResync?: () => void
  onFullRefresh?: () => void
  onUnauthorized?: () => void
  onOpen?: () => void
  onError?: () => void
}

export function useExecutionStream(handlers: UseExecutionStreamHandlers = {}) {
  const state = ref<ConnState>('idle')
  const lastSeenAt = ref<string | null>(null)
  const dedup = new Map<number, { status: ExecutionStatus, createdAt: string }>()
  const DEDUP_MAX = 1000
  const source = shallowRef<EventSource | null>(null)

  function clientId(): string {
    let id = sessionStorage.getItem(CLIENT_ID_KEY)
    if (!id) {
      id = crypto.randomUUID()
      sessionStorage.setItem(CLIENT_ID_KEY, id)
    }
    return id
  }

  function touchLastSeen(ts: string | undefined) {
    if (!ts) return
    if (!lastSeenAt.value || ts > lastSeenAt.value) {
      lastSeenAt.value = ts
    }
  }

  function trimDedup() {
    if (dedup.size <= DEDUP_MAX) return
    // 단순 FIFO drop: Map은 삽입순 유지
    const toDrop = dedup.size - DEDUP_MAX
    let i = 0
    for (const key of dedup.keys()) {
      if (i++ >= toDrop) break
      dedup.delete(key)
    }
  }

  function applyEvent(kind: StreamEvent['type'], raw: any) {
    const log = raw?.log ?? raw  // 서버가 payload.log로 보낼 수도, payload 자체가 log일 수도
    if (log?.id != null) {
      const prev = dedup.get(log.id)
      if (prev && prev.createdAt >= (log.startedAt ?? '')) return
      dedup.set(log.id, { status: log.status, createdAt: log.startedAt ?? new Date().toISOString() })
      trimDedup()
      touchLastSeen(log.startedAt)
    }
    switch (kind) {
      case 'EXECUTION_STARTED':   handlers.onStarted?.(log); break
      case 'EXECUTION_SUCCESS':   handlers.onSuccess?.(log); break
      case 'EXECUTION_FAILED':    handlers.onFailed?.(log); break
      case 'EXECUTION_RECOVERED': handlers.onRecovered?.(log); break
    }
  }

  async function handleResync() {
    handlers.onResync?.()
    try {
      const since = lastSeenAt.value ?? new Date(Date.now() - 5 * 60_000).toISOString()
      const res = await fetchDelta(since)
      if (res.truncated) {
        handlers.onFullRefresh?.()
        return
      }
      for (const log of res.items) {
        const kind: StreamEvent['type'] =
          log.status === 'RUNNING'   ? 'EXECUTION_STARTED'   :
          log.status === 'SUCCESS'   ? 'EXECUTION_SUCCESS'   :
          log.status === 'FAILED'    ? 'EXECUTION_FAILED'    :
                                       'EXECUTION_RECOVERED'
        applyEvent(kind, log)
      }
    } catch {
      handlers.onFullRefresh?.()
    }
  }

  function connect() {
    if (source.value) return
    const es = new EventSource(`/api/monitor/stream?clientId=${clientId()}`, { withCredentials: true })
    state.value = 'connecting'
    es.onopen = () => {
      state.value = 'open'
      handlers.onOpen?.()
    }
    es.onerror = () => {
      state.value = 'reconnecting'
      handlers.onError?.()
    }
    const eventTypes: StreamEvent['type'][] = [
      'CONNECTED','HEARTBEAT','EXECUTION_STARTED','EXECUTION_SUCCESS',
      'EXECUTION_FAILED','EXECUTION_RECOVERED','RESYNC_REQUIRED','UNAUTHORIZED',
    ]
    for (const t of eventTypes) {
      es.addEventListener(t, (ev: MessageEvent) => {
        try {
          const data = JSON.parse(ev.data)
          if (t === 'HEARTBEAT') { handlers.onHeartbeat?.(); return }
          if (t === 'CONNECTED') { return }
          if (t === 'RESYNC_REQUIRED') { void handleResync(); return }
          if (t === 'UNAUTHORIZED') {
            close()
            handlers.onUnauthorized?.()
            return
          }
          applyEvent(t, data.payload)
        } catch {
          /* ignore parse errors */
        }
      })
    }
    source.value = es
  }

  function close() {
    source.value?.close()
    source.value = null
    state.value = 'closed'
  }

  onBeforeUnmount(close)

  return { state, connect, close, lastSeenAt }
}
```

> 서버 `SseEmitterService.sendTo`가 `data = { type, payload, timestamp }` 형태로 내려보내므로, 이벤트의 `event.data`에는 최소한 `payload`가 있다. 서버의 `payload` 내부에 `log` 필드를 담는지는 `ExecutionEventPublisher` 구현을 확인해서 조정.

- [ ] **Step 2: 빌드**

```bash
cd frontend && npm run build
```
Expected: 성공

- [ ] **Step 3: Commit**

```bash
git add frontend/src/composables/useExecutionStream.ts
git commit -m "feat(frontend): useExecutionStream — SSE 구독·RESYNC·dedup·UNAUTHORIZED"
```

### Task 3-5: useDashboardPolling composable

**Files:**
- Create: `frontend/src/composables/useDashboardPolling.ts`

- [ ] **Step 1: composable 작성**

```ts
import { onBeforeUnmount, ref, shallowRef } from 'vue'
import type { DashboardResponse } from '@/api/types'
import { getDashboard } from '@/api/dashboard'

export function useDashboardPolling(options?: { debounceMs?: number, fallbackMs?: number }) {
  const data = ref<DashboardResponse | null>(null)
  const loading = ref(false)
  const debounceMs = options?.debounceMs ?? 1000
  const fallbackMs = options?.fallbackMs ?? 60_000

  let debounceTimer: number | null = null
  let fallbackTimer: number | null = null
  const source = shallowRef<'sse' | 'polling' | 'manual'>('manual')

  async function refresh() {
    loading.value = true
    try {
      data.value = await getDashboard()
    } finally {
      loading.value = false
    }
  }

  function requestDebouncedRefresh() {
    if (debounceTimer != null) window.clearTimeout(debounceTimer)
    debounceTimer = window.setTimeout(() => { void refresh() }, debounceMs)
  }

  function startPolling() {
    if (fallbackTimer != null) return
    fallbackTimer = window.setInterval(() => { void refresh() }, fallbackMs)
    source.value = 'polling'
  }

  function stopPolling() {
    if (fallbackTimer != null) {
      window.clearInterval(fallbackTimer)
      fallbackTimer = null
    }
    source.value = 'sse'
  }

  onBeforeUnmount(() => {
    if (debounceTimer != null) window.clearTimeout(debounceTimer)
    if (fallbackTimer != null) window.clearInterval(fallbackTimer)
  })

  return { data, loading, refresh, requestDebouncedRefresh, startPolling, stopPolling, source }
}
```

- [ ] **Step 2: 빌드**

```bash
cd frontend && npm run build
```
Expected: 성공

- [ ] **Step 3: Commit**

```bash
git add frontend/src/composables/useDashboardPolling.ts
git commit -m "feat(frontend): useDashboardPolling — debounce refresh + 상태 기반 폴백"
```

---

## Phase 4 — 프런트엔드 화면

### Task 4-1: Dashboard.vue

**Files:**
- Create: `frontend/src/pages/Dashboard.vue`

- [ ] **Step 1: 페이지 작성**

```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useExecutionStream } from '@/composables/useExecutionStream'
import { useDashboardPolling } from '@/composables/useDashboardPolling'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import StatusChip from '@/components/StatusChip.vue'

const auth = useAuthStore()
const router = useRouter()
const poll = useDashboardPolling()

const stream = useExecutionStream({
  onStarted:   () => poll.requestDebouncedRefresh(),
  onSuccess:   () => poll.requestDebouncedRefresh(),
  onFailed:    () => poll.requestDebouncedRefresh(),
  onRecovered: () => poll.requestDebouncedRefresh(),
  onFullRefresh: () => poll.refresh(),
  onOpen:  () => poll.stopPolling(),
  onError: () => poll.startPolling(),
  onUnauthorized: async () => {
    await auth.logout()
    router.push('/login')
  },
})

onMounted(async () => {
  await poll.refresh()
  stream.connect()
})
</script>

<template>
  <v-container fluid>
    <h1 class="text-h5 mb-4">대시보드</h1>

    <v-row v-if="poll.data.value">
      <v-col cols="12" md="3" v-for="card in [
        { label: '전체', value: poll.data.value.totals.total, color: 'primary' },
        { label: '실행 중', value: poll.data.value.totals.running, color: 'info' },
        { label: '성공', value: poll.data.value.totals.success, color: 'success' },
        { label: '실패', value: poll.data.value.totals.failed, color: 'error' },
      ]" :key="card.label">
        <v-card :color="card.color" variant="tonal">
          <v-card-text>
            <div class="text-overline">{{ card.label }}</div>
            <div class="text-h3 font-weight-bold">{{ card.value }}</div>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-row class="mt-4" v-if="poll.data.value">
      <v-col cols="12" md="7">
        <v-card>
          <v-card-title>프로토콜별 현황</v-card-title>
          <v-table>
            <thead>
              <tr>
                <th>프로토콜</th>
                <th>실행 중</th>
                <th>성공</th>
                <th>실패</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in poll.data.value.byProtocol" :key="p.protocol">
                <td>{{ p.protocol }}</td>
                <td>{{ p.running }}</td>
                <td>{{ p.success }}</td>
                <td>{{ p.failed }}</td>
              </tr>
            </tbody>
          </v-table>
        </v-card>
      </v-col>
      <v-col cols="12" md="5">
        <v-card>
          <v-card-title class="d-flex align-center">
            <span>최근 실패</span>
            <v-spacer />
            <v-chip size="x-small" :color="stream.state.value === 'open' ? 'success' : 'warning'">
              SSE {{ stream.state.value }}
            </v-chip>
          </v-card-title>
          <v-list>
            <v-list-item v-for="f in poll.data.value.recentFailures" :key="f.id">
              <template #prepend>
                <StatusChip status="FAILED" />
              </template>
              <v-list-item-title>{{ f.interfaceName }}</v-list-item-title>
              <v-list-item-subtitle class="text-truncate">{{ f.errorMessage }}</v-list-item-subtitle>
            </v-list-item>
            <v-list-item v-if="poll.data.value.recentFailures.length === 0">
              <v-list-item-title class="text-disabled">최근 실패 없음</v-list-item-title>
            </v-list-item>
          </v-list>
        </v-card>
      </v-col>
    </v-row>

    <div class="mt-4 text-caption text-disabled">
      SSE 연결 수: {{ poll.data.value?.sseConnections ?? 0 }}
    </div>
  </v-container>
</template>
```

- [ ] **Step 2: 빌드**

```bash
cd frontend && npm run build
```
Expected: 성공

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/Dashboard.vue
git commit -m "feat(frontend): Dashboard.vue — 카드 + 프로토콜·실패 리스트 + SSE 실시간"
```

### Task 4-2: ExecutionHistory.vue

**Files:**
- Create: `frontend/src/pages/ExecutionHistory.vue`

- [ ] **Step 1: 페이지 작성**

```vue
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { ExecutionLogResponse, ExecutionStatus } from '@/api/types'
import { listExecutions, retryExecution, getExecution } from '@/api/executions'
import { useExecutionStream } from '@/composables/useExecutionStream'
import { useToastStore } from '@/stores/toast'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import StatusChip from '@/components/StatusChip.vue'
import { ApiError } from '@/api/types'

const toast = useToastStore()
const auth = useAuthStore()
const router = useRouter()

const page = ref(0)
const size = ref(20)
const sort = ref<'startedAt,desc' | 'startedAt,asc'>('startedAt,desc')
const statusFilter = ref<ExecutionStatus | null>(null)

const items = ref<ExecutionLogResponse[]>([])
const total = ref(0)
const loading = ref(false)
const pendingNewCount = ref(0)

const retryDialog = ref<{ open: boolean, row: ExecutionLogResponse | null }>({ open: false, row: null })
const detailDialog = ref<{ open: boolean, row: ExecutionLogResponse | null }>({ open: false, row: null })

const statusOptions: Array<{ title: string, value: ExecutionStatus | null }> = [
  { title: '전체', value: null },
  { title: 'RUNNING', value: 'RUNNING' },
  { title: 'SUCCESS', value: 'SUCCESS' },
  { title: 'FAILED', value: 'FAILED' },
  { title: 'RECOVERED', value: 'RECOVERED' },
]

const isDefaultView = computed(() =>
  page.value === 0 && sort.value === 'startedAt,desc'
)

async function load() {
  loading.value = true
  pendingNewCount.value = 0
  try {
    const res = await listExecutions({ page: page.value, size: size.value, sort: sort.value, status: statusFilter.value ?? undefined })
    items.value = res.content
    total.value = res.totalElements
  } finally {
    loading.value = false
  }
}

function matchesFilter(e: Partial<ExecutionLogResponse>): boolean {
  if (statusFilter.value == null) return true
  return e.status === statusFilter.value
}

function upsertInPlace(e: Partial<ExecutionLogResponse>) {
  if (e.id == null) return
  const idx = items.value.findIndex(x => x.id === e.id)
  if (idx >= 0) {
    items.value[idx] = { ...items.value[idx], ...e } as ExecutionLogResponse
  }
}

function handleIncoming(e: Partial<ExecutionLogResponse>) {
  upsertInPlace(e)

  if (!matchesFilter(e)) return

  if (isDefaultView.value && e.id != null && !items.value.some(x => x.id === e.id)) {
    items.value = [e as ExecutionLogResponse, ...items.value].slice(0, size.value)
    return
  }
  if (e.id != null && !items.value.some(x => x.id === e.id)) {
    pendingNewCount.value++
  }
}

const stream = useExecutionStream({
  onStarted:   handleIncoming,
  onSuccess:   handleIncoming,
  onFailed:    handleIncoming,
  onRecovered: handleIncoming,
  onFullRefresh: load,
  onUnauthorized: async () => {
    await auth.logout()
    router.push('/login')
  },
})

onMounted(async () => {
  await load()
  stream.connect()
})

watch([page, size, sort, statusFilter], () => { void load() })

function openRetry(row: ExecutionLogResponse) {
  retryDialog.value = { open: true, row }
}

async function confirmRetry() {
  const row = retryDialog.value.row
  retryDialog.value.open = false
  if (!row) return
  try {
    await retryExecution(row.id)
    toast.push('재처리를 시작했습니다', 'success')
  } catch (e) {
    if (e instanceof ApiError) {
      const map: Record<string, string> = {
        RETRY_FORBIDDEN_ACTOR: '타 사용자의 실행 로그는 재처리할 수 없습니다',
        RETRY_LIMIT_EXCEEDED: '재처리 최대 횟수를 초과했습니다',
        RETRY_CHAIN_CONFLICT: '재처리 체인 분기는 허용되지 않습니다',
        RETRY_NOT_LEAF: '체인 최신 리프 로그만 재처리할 수 있습니다',
        RETRY_TRUNCATED_BLOCKED: 'payload가 잘린 로그는 재처리할 수 없습니다',
      }
      toast.push(map[e.errorCode ?? ''] ?? e.message, 'error')
    }
  }
}

async function openDetail(row: ExecutionLogResponse) {
  detailDialog.value = { open: true, row }
  try {
    const detail = await getExecution(row.id)
    detailDialog.value.row = detail
  } catch { /* noop */ }
}

function applyBanner() {
  pendingNewCount.value = 0
  void load()
}
</script>

<template>
  <v-container fluid>
    <h1 class="text-h5 mb-4">실행 이력</h1>

    <v-row class="mb-2" dense>
      <v-col cols="12" md="3">
        <v-select v-model="statusFilter" :items="statusOptions" item-title="title" item-value="value" label="상태" />
      </v-col>
      <v-col cols="12" md="3">
        <v-select v-model="sort"
                  :items="[{ title: '시작 시각 내림차순', value: 'startedAt,desc' }, { title: '시작 시각 오름차순', value: 'startedAt,asc' }]"
                  item-title="title" item-value="value" label="정렬" />
      </v-col>
    </v-row>

    <v-alert v-if="pendingNewCount > 0" type="info" closable class="mb-2"
             @click="applyBanner">
      새 실행 {{ pendingNewCount }}건 — 클릭하여 새로고침
    </v-alert>

    <v-card>
      <v-data-table
        :headers="[
          { title: 'ID', key: 'id', width: 80 },
          { title: '인터페이스', key: 'interfaceName' },
          { title: '상태', key: 'status' },
          { title: '트리거', key: 'triggeredBy' },
          { title: '시작', key: 'startedAt' },
          { title: '소요(ms)', key: 'durationMs' },
          { title: '재시도', key: 'retryCount', width: 80 },
          { title: '', key: 'actions', sortable: false, width: 100 },
        ]"
        :items="items"
        :loading="loading"
        hide-default-footer
        @click:row="(_: unknown, { item }: { item: ExecutionLogResponse }) => openDetail(item)">
        <template #[`item.status`]="{ item }">
          <StatusChip :status="item.status" />
        </template>
        <template #[`item.actions`]="{ item }">
          <v-btn v-if="item.status === 'FAILED'" size="x-small" color="warning" @click.stop="openRetry(item)">재처리</v-btn>
        </template>
      </v-data-table>
      <div class="pa-2 d-flex align-center">
        <v-btn :disabled="page === 0" size="small" @click="page--">이전</v-btn>
        <span class="mx-2">{{ page + 1 }} / {{ Math.max(1, Math.ceil(total / size)) }}</span>
        <v-btn :disabled="(page + 1) * size >= total" size="small" @click="page++">다음</v-btn>
        <v-spacer />
        <v-chip size="x-small" :color="stream.state.value === 'open' ? 'success' : 'warning'">
          SSE {{ stream.state.value }}
        </v-chip>
      </div>
    </v-card>

    <v-dialog v-model="retryDialog.open" max-width="480">
      <v-card>
        <v-card-title>재처리 확인</v-card-title>
        <v-card-text>
          실행 #{{ retryDialog.row?.id }} ({{ retryDialog.row?.interfaceName }})을 재처리하시겠습니까?
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="retryDialog.open = false">취소</v-btn>
          <v-btn color="primary" @click="confirmRetry">재처리</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="detailDialog.open" max-width="720">
      <v-card v-if="detailDialog.row">
        <v-card-title>실행 상세 #{{ detailDialog.row.id }}</v-card-title>
        <v-card-text>
          <div><strong>인터페이스:</strong> {{ detailDialog.row.interfaceName }}</div>
          <div><strong>상태:</strong> <StatusChip :status="detailDialog.row.status" /></div>
          <div><strong>시작:</strong> {{ detailDialog.row.startedAt }}</div>
          <div><strong>종료:</strong> {{ detailDialog.row.finishedAt ?? '-' }}</div>
          <div><strong>소요:</strong> {{ detailDialog.row.durationMs ?? '-' }} ms</div>
          <div><strong>재시도 횟수:</strong> {{ detailDialog.row.retryCount }}</div>
          <div v-if="detailDialog.row.parentLogId"><strong>부모 로그:</strong> #{{ detailDialog.row.parentLogId }}</div>
          <v-divider class="my-2" />
          <div><strong>에러:</strong></div>
          <pre class="text-body-2">{{ detailDialog.row.errorMessage ?? '-' }}</pre>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="detailDialog.open = false">닫기</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>
```

> `Page<T>` 타입에 `content`·`totalElements` 필드가 있어야 한다(`types.ts` 기존 타입). 없으면 추가.

- [ ] **Step 2: 빌드**

```bash
cd frontend && npm run build
```
Expected: 성공

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/ExecutionHistory.vue
git commit -m "feat(frontend): ExecutionHistory.vue — 필터·페이지·재처리·SSE in-place·배너"
```

### Task 4-3: 라우터·내비 갱신

**Files:**
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/components/AppShell.vue`

- [ ] **Step 1: 라우트 2종 추가**

`routes[].children` 안의 `interfaces` 위 또는 아래:

```ts
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/pages/Dashboard.vue'),
        },
        {
          path: 'history',
          name: 'history',
          component: () => import('@/pages/ExecutionHistory.vue'),
        },
```

그리고 `children[0].redirect`를 `/dashboard`로 변경:

```ts
        {
          path: '',
          redirect: '/dashboard',
        },
```

- [ ] **Step 2: AppShell 내비 추가**

현재 네비게이션 블록(`v-navigation-drawer` 또는 `v-list`)에 Dashboard·ExecutionHistory 링크 추가:

```vue
<v-list-item to="/dashboard" prepend-icon="mdi-view-dashboard" title="대시보드" />
<v-list-item to="/interfaces" prepend-icon="mdi-format-list-bulleted" title="인터페이스" />
<v-list-item to="/history" prepend-icon="mdi-history" title="실행 이력" />
```

- [ ] **Step 3: 라우터 beforeEach에 SSE close 보조**

```ts
router.beforeEach(async (to, from) => {
  const auth = useAuthStore()
  if (!auth.ready) await auth.bootstrap()
  if (to.name === 'login' && from.name && from.name !== 'login') {
    // 명시적 로그아웃 이동 시 sessionStorage clientId 정리
    sessionStorage.removeItem('sse.clientId')
  }
  // 기존 로직 유지
  const isPublic = to.meta.public === true
  if (!isPublic && !auth.authenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.authenticated) {
    return { name: 'dashboard' }
  }
  return true
})
```

- [ ] **Step 4: 빌드**

```bash
cd frontend && npm run build
```
Expected: 성공

- [ ] **Step 5: Commit**

```bash
git add frontend/src/router/index.ts frontend/src/components/AppShell.vue
git commit -m "feat(frontend): /dashboard·/history 라우트 + 네비 + 로그아웃 시 clientId 정리"
```

---

## Phase 5 — 통합 검증

### Task 5-1: 전체 빌드·테스트

- [ ] **Step 1: 백엔드 전체 빌드+테스트**

```bash
cd backend && ./gradlew clean build
```
Expected: BUILD SUCCESSFUL. ArchUnit 3종 포함 전 테스트 PASS.

- [ ] **Step 2: 프런트 타입체크+빌드**

```bash
cd frontend && npm run build
```
Expected: 성공

- [ ] **Step 3: Commit (빌드 결과물은 커밋하지 않음)**

빌드 산출물만 확인. 작업 커밋 없음.

### Task 5-2: 실 기동 + 수동 E2E 8 시나리오

- [ ] **Step 1: PostgreSQL + bootRun**

```bash
# 터미널 1
docker-compose up -d
# 터미널 2
cd backend && ./gradlew bootRun
```
Expected: `Started IfmsApplication` 로그, 에러 없음.

- [ ] **Step 2: npm run dev**

```bash
# 터미널 3
cd frontend && npm run dev
```
Expected: Vite dev server on http://localhost:5173.

- [ ] **Step 3: 8 시나리오 수동 실행 및 결과 기록**

| # | 시나리오 | Expected |
|---|---|---|
| 1 | 로그인(operator) → /dashboard → 인터페이스 실행 | 카운터 +1 (debounce 후) |
| 2 | /history → status=FAILED 필터 → 새 FAILED 이벤트 | prepend 배너 아닌 1p prepend (FAILED는 필터 일치) / SUCCESS 이벤트는 무시 |
| 3 | /history → FAILED 행 재처리 | 토스트 + 체인 행 반영 |
| 4 | 브라우저 F5 on /dashboard | 서버 로그 `CLIENT_ID_REASSIGNED`, SSE 재연결 |
| 5 | `curl '/api/monitor/stream?clientId=<same-uuid>'` 외부에서 시도(수동 시뮬레이션) | 이전 세션 2초 후 complete |
| 6 | 로그아웃 → Dashboard 탭에서 SSE 수신 | UNAUTHORIZED 이벤트 → /login 이동 + 서버 로그 `SSE_DROPPED_ON_SESSION_EXPIRY` |
| 7 | `curl '/api/executions/delta?since=2020-01-01T00:00:00Z'` | 400 DELTA_SINCE_TOO_OLD |
| 8 | `for i in $(seq 1 11); do curl '/api/executions/delta?since=...'; done` | 11번째 429 DELTA_RATE_LIMITED |

- [ ] **Step 4: Day 5 점검 5 시나리오**

| # | 시나리오 | 이상 여부 |
|---|---|---|
| a | 로그인/로그아웃 (CSRF 동작) | OK/수정필요 |
| b | 인터페이스 목록·필터·페이지 | OK/수정필요 |
| c | 인터페이스 등록 | OK/수정필요 |
| d | 수정 + 낙관적 락 다이얼로그 | OK/수정필요 |
| e | 수동 실행 트리거 201 | OK/수정필요 |

- [ ] **Step 5: 문제 발견 시 즉시 수정 → 재빌드 → 재검증**

수정 후:
```bash
cd backend && ./gradlew build && cd ../frontend && npm run build
```

- [ ] **Step 6: 커밋 (수정 있는 경우에만)**

```bash
git add <수정된 파일>
git commit -m "fix(day5-touchup): <구체적 문제> 수정"
```

### Task 5-3: DAY6-SUMMARY.md 작성

**Files:**
- Create: `docs/DAY6-SUMMARY.md`

- [ ] **Step 1: 요약 문서 작성**

8 시나리오 결과표·빌드 상태·발견 버그·주요 설계 결정을 채운다.

```markdown
# Day 6 완료 요약 — 2026-04-20

> Day 5 후속. ExecutionHistory·Dashboard·SSE 재동기화·세션 경계 완결.

## 1. 문서 산출물
- [ADR-007](adr/ADR-007-sse-resync-session-boundary.md) — R1·R2·R3·R5 묶음
- [api-spec.md](api-spec.md) — ErrorCode 21종, §3.3 delta, §6.1 SSE 재할당·UNAUTHORIZED
- [backlog.md](backlog.md) — Day 4 이월 SSE 3종 회수
- [DAY5-SUMMARY.md](DAY5-SUMMARY.md) — 역구축
- [spec](superpowers/specs/2026-04-20-day6-monitor-dashboard-design.md), [plan](superpowers/plans/2026-04-20-day6-monitor-dashboard.md)

## 2. 백엔드 신규/변경
- (신규) `DeltaCursor`, `DeltaRateLimiter`, `DeltaService`, `ExecutionQueryService`, `ExecutionListParams`, `DeltaResponse`, `ExecutionLogResponse`
- (신규) `SseReassignmentScheduler`, `SseSessionExpiryListener`
- (수정) `ErrorCode`(21종), `ExecutionLogRepository`(delta+list 쿼리), `ExecutionController`(3 엔드포인트), `SseEmitterRegistry`(findOther/snapshotBySession), `SseEmitterService`(재할당+UNAUTHORIZED)
- 테스트 신규: `DeltaCursorTest`(4), `DeltaRateLimiterTest`(3), `DeltaServiceTest`(4), `SseReassignmentSchedulerTest`(2), `SseSessionExpiryListenerTest`(1) — 총 14 케이스

## 3. 프런트 신규/변경
- (신규) `pages/Dashboard.vue`, `pages/ExecutionHistory.vue`, `components/StatusChip.vue`, `composables/useExecutionStream.ts`, `composables/useDashboardPolling.ts`, `api/executions.ts`, `api/dashboard.ts`
- (수정) `plugins/vuetify.ts` 팔레트 재도색, `router/index.ts` 라우트 2종+가드, `AppShell.vue` 내비 갱신, `api/types.ts` 타입 확장

## 4. 빌드 + 실 기동
- `./gradlew clean build` — BUILD SUCCESSFUL
- `npm run build` — 성공
- 실 기동: IfmsApplication Started

## 5. 8 E2E 시나리오 결과
(표 — 각 시나리오 결과 채우기)

## 6. 발견 버그 및 수정
(있으면 기록, 없으면 "없음")

## 7. 누적 통계
- Java 파일 75+
- Vue 파일 15+
- ADR 7종 (ADR-007 확정)
- 엔드포인트 11개 (실행 이력 list/detail/delta 추가)
- ErrorCode 21종

## 8. Day 7 이월
- Testcontainers 통합 테스트
- Rate limit 분산 전환(운영 이관)
- 복합 커서(startedAt,id) 인덱스(운영 이관)
- Swagger try-it-out 회귀, DefensiveMaskingFilter p95 벤치
```

- [ ] **Step 2: Commit**

```bash
git add docs/DAY6-SUMMARY.md
git commit -m "docs: Day 6 완료 요약 (8 E2E + ADR-007 링크)"
```

---

## Self-Review 체크리스트 (플랜 작성자 기록)

- [x] Spec §4.1 `/api/executions/delta` → Task 1-4/1-5/1-6/1-8
- [x] Spec §4.2 clientId 재할당 → Task 2-1/2-2/2-3
- [x] Spec §4.3 SSE UNAUTHORIZED → Task 2-3/2-4 + 프런트 3-4
- [x] Spec §5.1 테마 → Task 3-1
- [x] Spec §5.2 StatusChip → Task 3-3
- [x] Spec §5.3 useExecutionStream → Task 3-4
- [x] Spec §5.4 useDashboardPolling → Task 3-5
- [x] Spec §5.5 ExecutionHistory → Task 4-2
- [x] Spec §5.6 Dashboard → Task 4-1
- [x] Spec §5.7 API 확장 → Task 3-2
- [x] Spec §5.8 Day 5 점검 → Task 5-2 Step 4
- [x] Spec §6 문서 동기화 → Phase 0 전체 + 5-3
- [x] Spec §7 8 시나리오 → Task 5-2 Step 3
- [x] ErrorCode 2종 추가 → Task 1-1
- [x] 타입 일관성: `ExecutionLogResponse`·`DeltaResponse`·`DashboardResponse` 백엔드/프런트 이름 동일
- [x] 재처리 에러 5종 맞춤 토스트 → Task 4-2의 `confirmRetry`
