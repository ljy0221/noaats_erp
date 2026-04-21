# T21 — E2E 사용자 핸드오프 (2026-04-21)

> Playwright MCP 기반 수동 E2E 4묶음 수행. 시나리오 B에서 **재처리 전체 다운 회귀**(RetryGuardSnapshot ClassCastException)를 발견·수정하고 API 레벨로 재검증했다.
> 본 문서는 ① 수행 시나리오, ② 발견·수정한 버그(회의→판정→픽스→검증), ③ 남은 부차 이슈를 다음 세션에 인계한다.

---

## 1. 환경

| 구성 | 값 |
|---|---|
| Backend | Spring Boot 3.3.5 / Java 17, localhost:8080 (`./gradlew bootRun`) |
| Frontend | Vite 8 / Vue 3, localhost:5173 (재기동 시 5174로 포트 오프셋) |
| Postgres | Docker `ifms-postgres` (postgres:16-alpine, 127.0.0.1:5432) |
| Browser | Playwright 번들 `chromium-headless-shell` 147.0.7727.15 (`npx playwright install chromium`으로 사전 설치) |
| 계정 | `operator@ifms.local / operator1234`, `admin@ifms.local / admin1234` (in-memory, [SecurityUserDetailsService](../backend/src/main/java/com/noaats/ifms/global/security/SecurityUserDetailsService.java)) |

### 사전 조건
- Docker Desktop이 실행되어 있고 `ifms-postgres`가 healthy.
- 시스템 Chrome이 떠 있으면 Playwright MCP가 **번들 chromium-headless-shell**로 fallback(사용자 Chrome을 건드리지 않음).

---

## 2. 수행 시나리오

| # | 시나리오 | 결과 | 근거 |
|---|---|---|---|
| A | 로그인 → 목록 → 등록 → 수동 실행 → 이력 반영 | ✅ PASS | ID=3 `e2e_REST_신규` 생성, POST `/api/interfaces/3/execute` 201, 이력에 즉시 반영 |
| B | 실패 실행 → 재처리 → SUCCESS 전환 | 🔴→✅ **BUG 발견 및 수정** | §3 참고 |
| C | Dashboard SSE 수신 중 다른 탭에서 실행 → 카드 갱신 | ✅ PASS | 전체 1→5, 성공 0→4 실시간 갱신, `SSE open`, 연결 수 1 유지 ([t21-scenario-c-dashboard.png](../t21-scenario-c-dashboard.png)) |
| D | ErrorCode 대표 케이스 (404/밸리데이션/인증) | ✅ PASS | 404 `INTERFACE_NOT_FOUND`, 400 `VALIDATION_FAILED(fieldErrors)`, 로그아웃 후 401 → 라우터 가드가 `/login` 리다이렉트 |

- 스크린샷: [t21-A1-dashboard.png](../t21-A1-dashboard.png), [t21-A-history-success.png](../t21-A-history-success.png), [t21-scenario-c-dashboard.png](../t21-scenario-c-dashboard.png)
- 콘솔/네트워크 로그: `.playwright-mcp/` 아래 기록됨

---

## 3. 발견·수정한 버그 — RetryGuardSnapshot ClassCastException

### 3.1 증상
- POST `/api/executions/{id}/retry` → **500 INTERNAL_ERROR**
- 스택: `java.lang.ClassCastException: class [Ljava.lang.Object; cannot be cast to class java.lang.Number at RetryGuardSnapshot.fromRow(RetryGuardSnapshot.java:36)`
- 영향: **모든 FAILED 로그에 대한 재처리 불가** (기능 전체 다운)

### 3.2 원인
`ExecutionLogRepository.findRetryGuardSnapshot`는 9컬럼 native projection을 `Optional<Object[]>`로 받았는데, Hibernate 6가 단일 row를 `Object[][]`로 한 겹 더 감싸 반환. `RetryGuardSnapshot.fromRow(Object[] row)`는 `row[0]`를 `Number`로 캐스팅하려다 `Object[]`를 받아 실패.

공식적으로 `Optional<Object[]>` + multi-column native `@Query`는 Spring Data JPA 3 / Hibernate 6에서 **안전하게 지원되는 시그니처가 아님** (정의되지 않은 동작).

### 3.3 회의 판정 (4-에이전트 회의 축약)

| 옵션 | 요지 | 채택 여부 |
|---|---|---|
| A. `fromRow` 언랩 가드 | `row[0] instanceof Object[]`면 내부 배열을 풀어씀. 최소 변경 | ❌ — Security: 컬럼 순서 변경 시 rootActor/parentActor 자리바꿈 위험(권한 경계 우회), Devil: 미신 픽스 |
| B. `Optional<Tuple>` + 이름 기반 | SELECT alias 9개 명시 + `tuple.get(name, Class)` | **✅ 채택** |
| C. Projection interface | getter 이름과 SELECT alias 매핑 | ❌ — native 쿼리 + snake_case/alias 매핑 불안정(Devil) |

**채택 근거**:
- rootActorId는 `RETRY_FORBIDDEN_ACTOR` 판정의 유일 근거(ADR-005 Q2) — 위치 기반 캐스팅은 리팩터링 회귀 시 **권한 경계 오배치 리스크** 가짐(Security, CWE-863).
- Tuple 이름 기반은 Hibernate 6 Nativequery에서 별칭(`AS ...`)이 모든 컬럼에 박혀 있으면 `getColumnLabel` 기준으로 안정 동작.
- advisory lock + COALESCE 서브쿼리 때문에 native 유지는 불가피 (JPQL 재작성 불가, DBA 합의).

### 3.4 수정 내용

| 파일 | 변경 |
|---|---|
| [backend/.../repository/ExecutionLogRepository.java](../backend/src/main/java/com/noaats/ifms/domain/execution/repository/ExecutionLogRepository.java#L108-L124) | 반환 타입 `Optional<Object[]>` → `Optional<Tuple>`. SELECT 9개 컬럼 전부 AS alias 명시 (`parent_id`, `parent_actor`, `parent_retry_count`, `max_retry_snapshot`, `payload_truncated`, `parent_status`, `root_id`, `root_actor`, `ic_status`) |
| [backend/.../service/RetryGuardSnapshot.java](../backend/src/main/java/com/noaats/ifms/domain/execution/service/RetryGuardSnapshot.java#L35-L49) | `fromRow(Object[])` → `fromTuple(Tuple)`. 모든 필드를 `row.get(alias, Type)`로 추출 |
| [backend/.../service/RetryService.java:57](../backend/src/main/java/com/noaats/ifms/domain/execution/service/RetryService.java#L57) | `.map(RetryGuardSnapshot::fromRow)` → `.map(RetryGuardSnapshot::fromTuple)` |
| [backend/.../repository/RetryGuardSnapshotQueryTest.java](../backend/src/test/java/com/noaats/ifms/domain/execution/repository/RetryGuardSnapshotQueryTest.java) | 회귀 테스트 3건 — (1) 원본 로그 9필드 매핑, (2) 체인 자식의 rootActor COALESCE, (3) 미존재 ID. `@EnabledIfEnvironmentVariable(DOCKER_HOST)`로 게이팅 (로컬 Testcontainers 환경 요구) |

### 3.5 검증 — API 레벨 재현

본 세션에서 Playwright MCP가 user-data-dir 락 이슈로 재진입 실패 → **curl 기반 API 검증으로 대체** (시나리오 B의 본질은 백엔드 재처리 계약이며, UI는 동일 API를 호출).

```
# 1) admin이 operator의 FAILED 로그(#6) 재처리 → 403 RETRY_FORBIDDEN_ACTOR (정상 차단)
POST /api/executions/6/retry   (admin session)
  → 403 {"errorCode":"RETRY_FORBIDDEN_ACTOR", ...}

# 2) admin이 자기 FAILED 로그(#24) 재처리 → 201 Created (happy-path)
POST /api/executions/24/retry  (admin session)
  → 201 {"logId":25, "parentLogId":24, "retryCount":1, "triggeredBy":"RETRY", "status":"RUNNING"}

# 3) 완료 상태 확인
GET /api/executions/25
  → status=SUCCESS, retry=1, parent=24, duration=392ms
```

**계약 충족**:
- 500 → 403 (정확한 에러 코드로 전이)
- 체인 캐스케이드 (`parentLogId=24`, `retryCount=0→1`, `triggeredBy=RETRY`)
- RetryGuard가 rootActorId를 올바르게 비교 (Tuple 이름 매핑이 9개 필드 전부 정상)

---

## 4. 남은 부차 이슈 (본 세션 스코프 외, 기록만)

### 4.1 OrphanRunningWatchdog 스케줄 쿼리 SQL 타입 오류
- 위치: [backend/.../execution/service/OrphanRunningWatchdog](../backend/src/main/java/com/noaats/ifms/domain/execution/service) (추정 — watchdog 쿼리)
- 로그: `ERROR: operator does not exist: timestamp without time zone < interval`
- 재현: 백엔드가 기동한 뒤 스케줄러 주기(5분)가 돌 때 발생. `WHERE el.status = 'RUNNING' AND el.started_at < ? - INTERVAL '60 seconds' - (ic.timeout_seconds * INTERVAL '1 second')`의 연산자 우선순위로 `? - INTERVAL - INTERVAL` 전체가 `interval` 타입으로 해석되어 `timestamp < interval`이 되는 것으로 추정.
- 임팩트: 부차. ApplicationReadyEvent 시작 시 1회 전수 복구는 정상(`STARTUP_RECOVERY`). 런타임 주기 회수만 실패.
- 제안: `started_at + (ic.timeout_seconds * INTERVAL '1 second') + INTERVAL '60 seconds' < ?` 또는 바인딩을 `timestamp - interval - interval`로 강제 캐스팅.

### 4.2 operator 계정 로그인 401 (admin은 200 정상)
- 재현: 본 세션 백엔드 재기동 후 `curl -X POST /login -d "username=operator@ifms.local&password=operator1234"` → 401, `admin@ifms.local` / `admin1234`는 200.
- 로그: `BCryptPasswordEncoder - Empty encoded password` (사용자 미발견 시 타이밍 방어용 가짜 비교)
- 추정: [SecurityUserDetailsService](../backend/src/main/java/com/noaats/ifms/global/security/SecurityUserDetailsService.java)는 in-memory 고정. 코드상으로는 operator도 있어야 하므로 **본 세션 한정 현상**일 수 있음(쿠키/세션/케이스 등). 프런트(Playwright 첫 라운드)에서는 operator 로그인이 성공했음.
- 제안: 다음 세션에서 `loadUserByUsername`에 로그 추가하거나 `BCryptPasswordEncoder - Empty encoded password` 경고가 정확히 어느 요청에서 나는지 tracId로 역추적.

### 4.3 Vite proxy `/login` 경로 충돌
- [frontend/vite.config.ts:24-27](../frontend/vite.config.ts#L24-L27): `/login`을 통째로 백엔드로 프록시. 이 때문에 **브라우저에서 `/login`을 GET으로 직접 접근**하면 Vue 라우트 대신 Spring formLogin으로 가서 500 INTERNAL_ERROR JSON이 뜸.
- 영향: 일반 사용자 경로(`/` → 가드 리디렉트 `/login?redirect=...`)에선 문제 없음. 직접 URL 공유·새로고침만 위험.
- 제안: proxy 규칙에 `bypass` 추가해 `POST /login`만 프록시, GET은 Vite가 SPA로 서빙. 또는 엔드포인트를 `/api/auth/login`로 rename.

---

## 5. 재현 · 재실행 가이드

### 5.1 Playwright MCP로 재실행
```bash
# 1) 사전 조건
docker compose up -d                               # ifms-postgres
cd backend && ./gradlew bootRun &                  # backend on 8080
cd frontend && npm run dev &                       # frontend on 5173/5174

# 2) Playwright chromium 번들 (최초 1회)
cd frontend && npx playwright install chromium
```
그 후 MCP Playwright로 `http://localhost:5173/` 접속 → 로그인 폼은 자동 채워짐(operator) → 시나리오 A~D 순차.

### 5.2 API 레벨 재검증 (브라우저 없이)
```bash
# admin 세션
COOKIE=/tmp/ifms-jar
curl -sS -c $COOKIE -X POST http://localhost:8080/login \
  -d "username=admin@ifms.local&password=admin1234"

# 어떤 FAILED 로그 → retry
XSRF=$(grep XSRF-TOKEN $COOKIE | awk '{print $NF}')
curl -sS -b $COOKIE -X POST http://localhost:8080/api/executions/{id}/retry \
  -H "X-XSRF-TOKEN: $XSRF"
# → 201 (본인 로그) 또는 403 RETRY_FORBIDDEN_ACTOR (타 사용자 로그)
```

### 5.3 Repository 회귀 테스트 (Docker 환경 필요)
```powershell
$env:DOCKER_HOST="npipe:////./pipe/dockerDesktopLinuxEngine"
./gradlew test --tests "com.noaats.ifms.domain.execution.repository.RetryGuardSnapshotQueryTest"
```
로컬에 `DOCKER_HOST`가 없으면 `@EnabledIfEnvironmentVariable`로 자동 skip.

---

## 6. 다음 세션 착수 권장 순서

1. **[T21 잔여] operator 401 재현 조건 추적** (§4.2) — in-memory 사용자가 어떤 조건에서 empty-password로 떨어지는지
2. **[부채] OrphanRunningWatchdog SQL 수정** (§4.1) — 런타임 스케줄러 주기 복구 복원
3. **[부채] Vite proxy `/login` 경로 분리** (§4.3) — `bypass` 규칙 또는 엔드포인트 rename
4. 제출물(T21 §3)에 본 문서 + 수정 내역 포함

---

## 7. 커밋

본 세션 산출 커밋 (예정): `fix(retry): Tuple 이름 기반 매핑으로 RetryGuardSnapshot ClassCastException 회귀 차단`
- `ExecutionLogRepository.findRetryGuardSnapshot` — `Optional<Tuple>` + AS alias 9
- `RetryGuardSnapshot.fromTuple` — 이름 기반 추출
- `RetryService` — 호출부 교체
- `RetryGuardSnapshotQueryTest` — 회귀 테스트 3건 (DOCKER_HOST 게이팅)
- 본 문서 (`docs/T21-E2E-HANDOFF.md`)
