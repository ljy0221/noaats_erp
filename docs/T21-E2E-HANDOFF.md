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

---

## 8. 후속 세션 — §4 부채 3건 처리 결과 (2026-04-21, T21 인계 직후)

§6 권장 순서 ③ → ② → ① 역순으로 처리(영향이 작은 순). 결과 요약:

### 8.1 ✅ §4.3 — Vite proxy `/login`·`/logout` 경로 분리

**채택**: 옵션 A (`bypass`로 POST만 백엔드 프록시, GET/기타는 SPA 폴백). 옵션 B(엔드포인트 rename)는 Spring formLogin 계약(`loginProcessingUrl`, CSRF prime, 라우터 가드 401 핸들링) 변경 범위가 너무 커서 거절.

| 파일 | 변경 |
|---|---|
| [frontend/vite.config.ts:24-35](../frontend/vite.config.ts#L24-L35) | `/login`·`/logout`에 `bypass: (req) => req.method === 'POST' ? undefined : req.url` 추가. POST만 8080으로 프록시, GET 등은 Vite가 SPA index.html로 서빙 |

**검증** (Vite dev 5175 + 백엔드 8080):

```text
GET  /login  → HTTP 200, text/html (SPA index.html)        # 이전: 500 INTERNAL_ERROR JSON
POST /login  → HTTP 401 (백엔드 formLogin 도달, 자격증명 거부)  # 정상 프록시
```

### 8.2 ✅ §4.1 — OrphanRunningWatchdog SQL 타입 오류

**원인 확정**: 핸드오프 §4.1 추정대로 `:now - INTERVAL - INTERVAL` 형태가 Hibernate 6 prepared-statement 변환 단계에서 `timestamp < interval` 비교로 해석되는 회귀. 직전 인스턴스 로그(ifms-backend3.log:105-109)에서 정확히 재현 확인:

```text
2026-04-21 12:34:21.682 ERROR ... SqlExceptionHelper - ERROR: operator does not exist: timestamp without time zone < interval
2026-04-21 12:34:21.689 ERROR ... TaskUtils$LoggingErrorHandler - Unexpected error occurred in scheduled task
```

PostgreSQL 직접 prepared 실행은 정상이라 SQL 자체는 무결, Hibernate 변환 단계의 모호성이 주범.

**픽스**: 좌변 양수 합 형태로 변경 (의미 동치, 우변에서 interval 산술 제거).

| 파일 | 변경 |
|---|---|
| [backend/.../repository/ExecutionLogRepository.java:73-86](../backend/src/main/java/com/noaats/ifms/domain/execution/repository/ExecutionLogRepository.java#L73-L86) | `started_at < :now - INTERVAL '60s' - (timeout_seconds * INTERVAL '1s')` → `started_at + INTERVAL '60s' + (timeout_seconds * INTERVAL '1s') < :now`. Javadoc에 회귀 사유 명시 |

**검증**:

- PostgreSQL 직접 `PREPARE`/`EXECUTE` 정상 (timestamp ↔ timestamp 비교)
- timeout 30s 인터페이스에 1시간 전 RUNNING 1건(ID=26) 인위 삽입 → 수정된 SQL이 prepared로 정확히 잡음 확인
- 백엔드 재기동(13:04:10) 후 Hibernate 통합 watchdog sweep 검증 — 13:10:10에 첫 fixedDelay 회차가 정상 발화: `OrphanRunningWatchdog: 고아 RUNNING 1건 발견 → 회수 시작` 로그 + ID=26이 `FAILED/STARTUP_RECOVERY/duration_ms=3868883`(약 64분)으로 마감. **SQL 에러 0건** — Hibernate prepared 변환에서도 수정된 형태 정상 동작.

### 8.3 ✅ §4.2 — operator 401 (재현 불가, 본 세션 한정 현상으로 결론)

**현 백엔드 인스턴스(13:04:10 기동) 검증**:

```text
POST /login (admin@ifms.local / admin1234)        → HTTP 200 OK
POST /login (operator@ifms.local / operator1234)  → HTTP 200 OK
```

둘 다 정상. `SecurityUserDetailsService` 코드 자체는 무결(in-memory `User.withUsername(...).password(encoder.encode(...))`).

**직전 인스턴스 로그 분석으로 추정한 진짜 원인**:

- 12:35:07까지는 정상 인증 (Hibernate trace에 정상 INSERT/SELECT가 trace ID와 함께 기록됨)
- 12:35:25부터 모든 로그인이 `BCryptPasswordEncoder - Empty encoded password` 경고로 실패
- 실패 trace ID에 해당하는 username binding 로그가 **전혀 없음** → `loadUserByUsername` 도달 전 / 직후에 username이 빈 문자열로 들어왔거나, Spring Security `usernamePasswordFilter`가 form 파싱에 실패해 빈 username으로 `UsernameNotFoundException` 분기
- T21 시점 "operator만 401, admin은 200" 분리는 우연(시도 시점 차이)일 가능성, 본 세션 분석 시점엔 admin도 401이었음

**조치 없음 결론**:

- 코드 변경으로 재현 불가 → 픽스할 대상 없음
- 다음 발생 시 추적: (1) Spring Security TRACE 로그 활성 (`logging.level.org.springframework.security=TRACE`), (2) 실패 trace ID로 모든 필터 진입 로그 추적, (3) 요청 raw body 기록. 운영 전환 시 in-memory → DB 사용자 테이블로 교체되면 상황 자체 소멸

### 8.4 변경 커밋 (예정)

`fix(infra): T21 §4 잔여 부채 3건 — Vite proxy 분리 + Watchdog SQL + operator 401 분석`

- `frontend/vite.config.ts` — `/login`·`/logout` POST 한정 프록시 (`bypass`)
- `backend/.../ExecutionLogRepository.java` — `findOrphanRunning` SQL 좌변 양수 합 형태로 재작성
- `docs/T21-E2E-HANDOFF.md` — 본 §8 추가

---

## 8.5 ✅ §4.2 재점검 — 401 근인 확정 + 픽스 (2026-04-21 14:00 후속)

§8.3의 "재현 불가, 조치 없음 결론"을 **뒤집는** 새 증거를 확보했다. 제출 직전 마무리 세션에서 브라우저 E2E 중 operator 로그인이 401로 실패 → curl로도 재현 → 재기동 후 **첫 요청만 200, 이후 모든 요청 401**의 결정적 타이밍 패턴 포착.

### 8.5.1 재현 조건

1. 백엔드를 재기동한다.
2. 첫 번째 `POST /login`(username/password 무관) → **HTTP 200**
3. 두 번째 이후 `POST /login`(같은 사용자든 다른 사용자든) → **HTTP 401** + `BCryptPasswordEncoder - Empty encoded password` WARN

§8.3이 관찰한 "1~2분 후 401 전이"는 **이 패턴이 1분 단위로 사람 손 요청 간격과 겹쳐 느리게 보였을 뿐**. 실제로는 **첫 인증 직후 전이**한다.

### 8.5.2 Spring Security TRACE 로그로 본 차이

성공(trace `d3ecc3963c584a03`):

```text
UsernamePasswordAuthenticationFilter (8/15)
DaoAuthenticationProvider → Authenticated user
HttpSessionSecurityContextRepository → Stored SecurityContextImpl [...Password=[PROTECTED]...]
```

실패(trace `b3aed7e6e54a4d87`, 20초 후):

```text
UsernamePasswordAuthenticationFilter (8/15)
ProviderManager → Authenticating request with DaoAuthenticationProvider (1/1)
BCryptPasswordEncoder - Empty encoded password     ← ★
DaoAuthenticationProvider - Failed to authenticate since password does not match stored value
```

실패 경로에도 `UserDetailsService.loadUserByUsername`이 호출되고 있으나(필수 단계), 반환된 `UserDetails`의 **`getPassword()`가 null**이다.

### 8.5.3 근인

[`SecurityUserDetailsService`](../backend/src/main/java/com/noaats/ifms/global/security/SecurityUserDetailsService.java)가 두 개의 `UserDetails` 인스턴스를 **`private final` 필드에 싱글턴으로 보관**하고 `loadUserByUsername`에서 **같은 인스턴스를 그대로 반환**하고 있었다.

Spring Security는 인증 성공 후 기본적으로 `ProviderManager.eraseCredentialsAfterAuthentication=true`에 따라 `AbstractAuthenticationToken.eraseCredentials()`를 호출한다. 이 호출은 principal(=`UserDetails`)의 `eraseCredentials()`에 위임되어 `User.password` 필드를 `null`로 지운다.

싱글턴 인스턴스가 반환되므로 첫 인증 성공의 부수효과(password 지움)가 **필드에 누적**되고, 두 번째 요청은 password가 비어있는 UserDetails를 받아 `BCryptPasswordEncoder.matches(raw, null)` → "Empty encoded password" WARN + 401.

### 8.5.4 픽스

```diff
-private final UserDetails operator;  // 싱글턴 보관 → eraseCredentials 누적
-private final UserDetails admin;
+private final String operatorEncodedPassword;  // 불변 원자료만 보관
+private final String adminEncodedPassword;
 ...
 @Override
 public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
-    if (operator.getUsername().equalsIgnoreCase(username)) return operator;
+    if ("operator@ifms.local".equalsIgnoreCase(username)) {
+        return User.withUsername("operator@ifms.local")
+                .password(operatorEncodedPassword)
+                .roles("OPERATOR")
+                .build();   // 매 호출 새 인스턴스
+    }
     ...
 }
```

`PasswordEncoder.encode(...)` 결과 문자열만 필드로 보관하고, `UserDetails` 인스턴스는 매 호출마다 `User.withUsername(...).build()`로 **새로 생성**한다. BCrypt 계산은 기동 시 1회(생성자)만 수행되므로 성능 영향 없음.

### 8.5.5 검증

- 8회 연속 `POST /login` (operator, 10초 간격) → **전부 200**
- admin 1회 → 200
- 잘못된 패스워드 → 401 (BadCredentials, 정상)
- 존재하지 않는 사용자 → 401 (UsernameNotFound → BadCredentials로 승격, 정상)
- `/tmp/ifms-backend2.log` 전체에서 `Empty encoded password` WARN **0건**
- 회귀 방지 테스트 추가: [`SecurityUserDetailsServiceTest`](../backend/src/test/java/com/noaats/ifms/global/security/SecurityUserDetailsServiceTest.java) — 6 케이스 (복사본 반환 불변식, eraseCredentials 격리, Role, 대소문자, 존재하지 않는 사용자)

### 8.5.6 §8.3이 놓쳤던 이유

§8.3이 "재현 불가"로 결론 낸 시점에는 Spring Security TRACE가 꺼져 있었다. 로그에서 보이는 건 `BCryptPasswordEncoder - Empty encoded password` WARN 1줄뿐이었고, 이것만으로는 "password가 왜 비어있는지"를 UserDetailsService 싱글턴 문제로 연결하기 어려웠다. **TRACE를 켜자 실패 경로에서도 `DaoAuthenticationProvider`가 UserDetails를 받은 뒤 비교에서 실패한다는 것이 명확해져** 근인이 UserDetailsService 쪽이라는 결론에 도달.

교훈: `UserDetails` 구현체는 **mutable**이다. `UserDetailsService.loadUserByUsername`은 매번 새 인스턴스를 반환해야 하며, 공식 `InMemoryUserDetailsManager`도 매 호출마다 복사본을 만든다. 싱글턴 보관은 Spring Security 문서에도 명시적 경고는 없으나 `CredentialsContainer` 의미론을 위반한다.
