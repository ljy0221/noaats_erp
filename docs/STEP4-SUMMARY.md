# Day 4 완료 요약 — 2026-04-20

> Day 1·2·3·4 통합 산출물 대시보드. Day 5 Vue 착수 전 참조용.

---

## 1. 문서 산출물 (Day 4 추가)

| 문서 | 변경 | 비고 |
|---|---|---|
| [superpowers/plans/2026-04-20-day4-security-sse-dashboard.md](superpowers/plans/2026-04-20-day4-security-sse-dashboard.md) | **신규 작성** — Day 4 구현 플랜 18 Task | writing-plans 스킬 출력 |
| [DAY4-SUMMARY.md](DAY4-SUMMARY.md) | **본 문서 신규** | 누적 대시보드 (DAY3-SUMMARY 대체) |
| [backlog.md](backlog.md) | Day 4 완료 항목 스트라이크, Day 5~6 섹션 유지 | 이월 항목 추적 |

본 Day는 코드 중심. ADR 신규 확정 없음(사양이 api-spec v0.7에 이미 수렴됨).

---

## 2. 코드 산출물 (Day 4)

### 2-A 인프라·의존성

| 파일 | 역할 |
|---|---|
| [backend/build.gradle](../backend/build.gradle) | `archunit-junit5:1.3.0` 추가 |
| [backend/src/main/resources/application.yml](../backend/src/main/resources/application.yml) | `ifms.sse.*` 6개 프로퍼티 (ring-buffer-size, ring-buffer-ttl, heartbeat-interval, emitter-timeout, session-limit, account-limit) |
| [backend/src/main/resources/application-local.yml](../backend/src/main/resources/application-local.yml) | `ifms.actor.anon-salt` local 기본값(`REQUIRED_SET_ME_LOCAL`) — gradle bootRun 지원 |
| [backend/src/main/resources/logback-spring.xml](../backend/src/main/resources/logback-spring.xml) | **신규** — 콘솔 패턴에 `[%X{traceId:-no-trace}]` 삽입 |

### 2-B SSE 본 구현 (monitor/sse 패키지 6파일 신규)

| 파일 | 책임 |
|---|---|
| `SseProperties` (record, `@ConfigurationProperties(prefix = "ifms.sse")`) | 기본값 fallback(`ringBufferSize=1000`, `ringBufferTtl=5M`, `heartbeatInterval=30S`, `emitterTimeout=3M`, `sessionLimit=3`, `accountLimit=10`) |
| `SseEventType` (enum) | 8종 (CONNECTED, EXECUTION_STARTED/SUCCESS/FAILED/RECOVERED, HEARTBEAT, UNAUTHORIZED, RESYNC_REQUIRED) |
| `SseEvent` (record) | `id(long 단조)`, `type`, `payload(Map)`, `timestamp(OffsetDateTime KST)` |
| `SseRingBuffer` | synchronized ArrayDeque + AtomicLong 시퀀스, 1,000건/5분 축출, `since`·`isKnown` API |
| `SseEmitterRegistry` | `(sessionId, clientId) → SseEmitter` 2단 ConcurrentHashMap, 세션·계정 카운트, 스냅샷 |
| `SseEmitterService` | `subscribe`(CONNECTED + Last-Event-ID 재전송 + RESYNC_REQUIRED), `broadcast`, `@Scheduled("${ifms.sse.heartbeat-interval}")` HEARTBEAT |

### 2-C 스텁 교체

| 파일 | Day 3 상태 → Day 4 |
|---|---|
| [ExecutionEventPublisher](../backend/src/main/java/com/noaats/ifms/domain/monitor/sse/ExecutionEventPublisher.java) | `log.info(...)` stub → `SseEmitterService.broadcast(...)` 위임 (호출자 무변경) |
| [ActorContext](../backend/src/main/java/com/noaats/ifms/global/security/ActorContext.java) | `ANONYMOUS_LOCAL` 상수 반환 → 세션 기반 분기 (`EMAIL:` SHA-256 / `ANONYMOUS_` ip해시 prefix16) |

### 2-D Security 완전판

| 파일 | 책임 |
|---|---|
| [SecurityUserDetailsService](../backend/src/main/java/com/noaats/ifms/global/security/SecurityUserDetailsService.java) | **신규** in-memory 2명 (`operator@ifms.local/operator1234` OPERATOR, `admin@ifms.local/admin1234` ADMIN+OPERATOR) |
| [SecurityConfig](../backend/src/main/java/com/noaats/ifms/global/config/SecurityConfig.java) | permitAll 전면 → 세션 기반 인증 + CSRF(CookieCsrfTokenRepository) + formLogin(200/401) + logout + 401/403 authenticationEntryPoint + permitAll 범위(/login·/logout·/swagger-ui/**·/v3/api-docs/**·/actuator/health) + `/actuator/**` denyAll + 필터 순서 `TraceId(10) → UsernamePassword → ConnectionLimit(20)` |

### 2-E 대시보드 집계 (monitor 패키지 7파일 신규)

| 파일 | 책임 |
|---|---|
| `ExecutionLogRepository`에 native 쿼리 3개 + projection 3종 추가 | `aggregateTotals`·`aggregateByProtocol`·`findRecentFailures` + `DashboardTotalsProjection`·`DashboardProtocolProjection`·`DashboardRecentFailureProjection` (PostgreSQL `FILTER (WHERE ...)` 절 활용) |
| `TotalStats` · `ProtocolStats` · `RecentFailure` · `DashboardResponse` (record 4종) | api-spec §6.2 응답 구조 |
| `DashboardService` | since 생략 시 Asia/Seoul 당일 00:00, OffsetDateTime ↔ LocalDateTime 변환, 최근 실패 10건 제한 |
| `MonitorController` | `GET /api/monitor/stream` (SSE, UUID v4 검증) + `GET /api/monitor/dashboard` |

### 2-F Traces / 필터

| 파일 | 책임 |
|---|---|
| [TraceIdFilter](../backend/src/main/java/com/noaats/ifms/global/web/TraceIdFilter.java) | **신규** `OncePerRequestFilter @Order(10)` — `X-Trace-Id` 헤더 재사용 또는 UUID 16-hex 생성, MDC `traceId` 주입, 응답 헤더 동일값 |
| [ConnectionLimitFilter](../backend/src/main/java/com/noaats/ifms/domain/monitor/filter/ConnectionLimitFilter.java) | **신규** `OncePerRequestFilter @Order(20)` — `/api/monitor/stream` 전용, 세션 3/계정 10 초과 시 429 JSON 응답 |

### 2-G Controller 리팩토링 (ADR-006 Repository 주입 범위 준수)

| 파일 | 변경 |
|---|---|
| [AsyncExecutionRunner.runAsync](../backend/src/main/java/com/noaats/ifms/domain/execution/service/AsyncExecutionRunner.java) | 시그니처 `(Long logId, InterfaceConfig config)` → `(Long logId, Long configId)` + 내부에서 `InterfaceConfigRepository.findById` 수행 |
| [InterfaceController](../backend/src/main/java/com/noaats/ifms/domain/interface_/controller/InterfaceController.java) | `InterfaceConfigRepository` 의존 제거, `asyncRunner.runAsync(logId, id)` 호출 |
| [ExecutionController](../backend/src/main/java/com/noaats/ifms/domain/execution/controller/ExecutionController.java) | `ExecutionLogRepository` 의존 제거, `asyncRunner.runAsync(logId, response.interfaceId())` 호출 |

### 2-H ArchUnit 테스트

| 파일 | 규칙 |
|---|---|
| [ArchitectureTest](../backend/src/test/java/com/noaats/ifms/archunit/ArchitectureTest.java) | **신규** 3종: (1) Repository는 `..service..`·`..repository..`·`..config..` 패키지에서만 주입 허용, (2) `EntityManager.merge` 금지, (3) `@Modifying`은 Repository 인터페이스에서만 선언 |

---

## 3. 빌드 + 실 기동 검증

### 빌드
- `compileJava` BUILD SUCCESSFUL
- `compileTestJava` BUILD SUCCESSFUL
- `build -x test` BUILD SUCCESSFUL
- ArchUnit 3종 모두 PASS (`./gradlew test --tests ArchitectureTest`)

### 실 기동 (PostgreSQL 16-alpine + bootRun)
- `Started IfmsApplication in 8.231 seconds`
- `AsyncConfig EXECUTION_POOL initialized: core=4 max=8 queue=50 policy=CallerRuns`
- `Tomcat started on port 8080`
- `OrphanRunningWatchdog: 잔재 RUNNING 없음`
- 로그 패턴에 `[traceId]` 컬럼 자동 주입 확인

### 엔드포인트 검증 결과

| 시나리오 | 결과 | 비고 |
|---|---|---|
| `GET /api/monitor/dashboard` (미인증) | **401** | SecurityConfig 정상 |
| `POST /login` (operator@ifms.local) | **200** | JSESSIONID + XSRF-TOKEN 쿠키 발급 |
| `GET /api/monitor/dashboard` (인증) | **200** | totals=3건, byProtocol=REST 1건, recentFailures=2건, sseConnections=0 |
| `X-Trace-Id` 응답 헤더 | `82241899b6ef4cf0` (16-hex) | TraceIdFilter 동작 |
| `GET /api/monitor/stream?clientId=<uuid>` | SSE 수신 | `id:1 CONNECTED` → `id:2 HEARTBEAT`(30s 후) → 실행 트리거 후 `id:3 EXECUTION_STARTED` → `id:4 EXECUTION_SUCCESS(529ms)` |
| `GET /api/monitor/stream?clientId=not-a-uuid` | **400 VALIDATION_FAILED** | extra.clientId 에코 |
| `POST /api/interfaces/1/execute` 동시 2건 | **201 + 409 DUPLICATE_RUNNING** | advisory lock 정상 (Day 3 회귀 확인) |
| SSE 연결 4개 시도 (세션당) | **200/200/200/429 TOO_MANY_CONNECTIONS** | ConnectionLimitFilter 정확히 sessionLimit=3 경계 |
| 실행 후 대시보드 | totals.success 3 / sseConnections 1 | 집계 실시간 반영 |

---

## 4. Day 4 디버깅 기록 (실 기동 발견 버그 2건)

### Bug A — `@Scheduled(fixedRateString = "#{@sseProperties.heartbeatInterval.toMillis()}")` SpEL 실패

- **증상**: `APPLICATION FAILED TO START` → `required a bean named 'sseProperties' that could not be found`
- **원인**: `@ConfigurationProperties` record는 Spring이 자동으로 `<prefix>-<fqcn>` 복합 이름으로 등록 — `sseProperties` 단순 이름의 빈 존재 안 함. SpEL이 못 찾음.
- **수정**: `@Scheduled(fixedRateString = "${ifms.sse.heartbeat-interval}")` 프로퍼티 플레이스홀더 직접 참조로 변경. Spring 6 `fixedRateString`은 ISO-8601 `Duration` 포맷(`PT30S`) 자동 해석.

### Bug B — `IFMS_ACTOR_ANON_SALT` 환경변수 미주입 → bootRun 기동 실패

- **증상**: `Could not resolve placeholder 'IFMS_ACTOR_ANON_SALT' in value "${IFMS_ACTOR_ANON_SALT}"` → ActorContext 생성 실패
- **원인**: `.env` 파일은 docker-compose 전용. `./gradlew bootRun` 단독 실행 시 env 주입 경로 없음. Day 3에는 `ActorContext`가 `@Value` 주입을 쓰지 않던 상태라 문제 노출 안 됨.
- **수정**: [application-local.yml](../backend/src/main/resources/application-local.yml)에 `ifms.actor.anon-salt: ${IFMS_ACTOR_ANON_SALT:REQUIRED_SET_ME_LOCAL}` 기본값 추가. prod 프로파일에선 `SaltValidator`가 여전히 fail-fast 강제하므로 운영 안전성 유지.

### Bug C — ArchUnit 1번 규칙 위반 6건 (Day 3 Controller가 Repository 직접 주입)

- **증상**: `repositoriesOnlyInjectedInServiceOrRepository` 6 위반 — `ExecutionController`·`InterfaceController`가 `ExecutionLogRepository`·`InterfaceConfigRepository`를 필드로 주입하고 `findById` 호출
- **원인**: Day 3까지 `AsyncExecutionRunner.runAsync`가 `InterfaceConfig`를 파라미터로 받았기 때문에 Controller가 Entity를 조회해 넘기던 관성
- **수정**: `AsyncExecutionRunner.runAsync(Long logId, Long configId)`로 시그니처 단순화 + 내부에서 `InterfaceConfigRepository.findById` 수행. 두 Controller 모두 Repository 의존 제거. ADR-006 취지("Repository 주입은 Service 레이어로 국한")를 정적 테스트로 강제.

---

## 5. 주요 설계 결정 요약 (Day 4 추가)

| 영역 | 결정 | 근거 |
|---|---|---|
| 인증 모델 | 세션(JSESSIONID) + CSRF 쿠키-헤더 이중화 | api-spec §2.1, SPA 관례 |
| CSRF 핸들러 | `CookieCsrfTokenRepository.withHttpOnlyFalse()` + `CsrfTokenRequestAttributeHandler(setCsrfRequestAttributeName(null))` | Spring Security 6 SPA 패턴 — `_csrf` 요청 속성 자동 deferred 해제 |
| 인증 실패 응답 | 401만 반환 (HTML 리다이렉트 금지) | SPA 전용, 기본 FormLoginConfigurer 폐기 |
| SSE 링버퍼 | synchronized ArrayDeque + AtomicLong | 초당 수천 이벤트 미만 규모 — 단일 lock 충분, ConcurrentLinkedDeque는 size() 정확성 비용으로 제외 |
| HEARTBEAT 발송 조건 | 활성 구독자 0이면 skip | 불필요한 시퀀스 발급 방지 |
| Emitter 타임아웃 | 3분 | 브라우저 EventSource 기본 재연결 주기보다 짧게 설정해 서버 유휴 감지 유도 |
| actor_id 계산 | EMAIL: prefix + SHA-256(lower(username)) / ANONYMOUS_ prefix + SHA-256(salt+ip)[:16] | api-spec §2.3 — 평문 이메일 저장 금지 |
| anon-salt local 기본값 | `REQUIRED_SET_ME_LOCAL` (prod SaltValidator가 거부하는 prefix) | 운영에 우연히 반영되어도 fail-fast 유지 |
| 필터 순서 | TraceId(10) → UsernamePasswordAuthenticationFilter → ConnectionLimit(20) | 인증 실패 로그에도 traceId 기록 / ConnectionLimit은 세션 확립 이후 동작 |
| ArchUnit 3번 규칙 DSL | `methods().that().areAnnotatedWith(Modifying.class)` | 1.3 API 호환성 확인 |
| Controller → Service → AsyncRunner | AsyncRunner가 configId만 받고 내부 조회 | ADR-006 Repository 주입 범위 정적 강제 |

---

## 6. Day 4 끝나도 남은 검증

| 항목 | 상태 | 책임 |
|---|---|---|
| SSE `UNAUTHORIZED` 이벤트 (세션 만료 직전 emit + 감사 로그) | ⏳ Day 7 이월 | 세션 만료 감지 HttpSessionListener 필요 |
| SSE clientId 세션 스푸핑 차단 (`clientIdBoundToOtherSession`) 실 활용 | ⏳ Day 5 이월 | 현재 Registry 메서드만 존재, Controller에서 호출 안 함 |
| `?since=` 폴백 쿼리 (`GET /api/executions?since=`) | ⏳ Day 6 (프런트 재연결 시 구현) | api-spec §3.3 |
| `DefensiveMaskingFilter` SSE 경로 미적용 확인 | ✅ Day 4 SecurityConfig의 `text/event-stream` 제외 로직 | MaskingRule 1차만 작동 |
| ArchUnit 3종 CI 고정(test 의존성) | ⏳ Day 7 CI 구성 시 | `check.dependsOn(test)` |
| Testcontainers 통합 테스트 (JSONB GIN, advisory lock, 재처리 체인) | ⏳ Day 7 유지 | |
| `@PreAuthorize("hasRole('OPERATOR')")` 엔드포인트별 선언 | ⏳ Day 7 유지 (단일 Role 고정) | |
| Role=ADMIN 분기 (재처리 actor 우회, 대시보드 전체 조회) | ⏳ 운영 전환 | backlog |
| Rate limit (actor·IP 기준) | ⏳ Day 7 유지 | |

---

## 7. 누적 통계 (Day 1~4)

- **Java 파일** 총 60+ (Day 4 신규 15)
- **ADR** 6종 (001·004·005·006 확정, 002·003 본문 편입)
- **문서** planning·erd·api-spec v0.7 + DAY 요약 2회 + plan 1건
- **엔드포인트** 8개 (`/login`, `/logout`, `/api/interfaces/*` 5개, `/api/executions/{id}/retry`, `/api/monitor/stream`, `/api/monitor/dashboard`)
- **에러 코드** 18종 (NOT_IMPLEMENTED 포함), 실제 사용 중 17종

---

## 8. Day 5 착수 준비

Day 5 우선순위 (planning §11):
1. **Vuetify 3 스파이크** 2시간 선행 — 컴포넌트 셋업·Axios 인스턴스·Vite proxy(/api → localhost:8080)·CSRF 쿠키 자동 동봉 확인
2. **`InterfaceList.vue`** — 테이블 + 필터(status·protocol·name) + 페이지네이션
3. **`InterfaceFormDialog.vue`** — 등록·수정 모달 (낙관적 락 충돌 시 diff 다이얼로그)
4. **Day 6와의 경계** — Day 5는 인터페이스 CRUD UI, Day 6는 실행 이력 + Dashboard + SSE 재연결

백로그 이월 항목: [backlog.md](backlog.md) 참조.

---

## 9. 통합 빌드 상태

**Day 1+2+3+4 통합 BUILD SUCCESSFUL** + 실 기동 검증 통과:
- Security 401/200 인증 전환
- Dashboard 집계 200 (+ sseConnections 실시간 반영)
- SSE 4 이벤트 수신 (CONNECTED·HEARTBEAT·EXECUTION_STARTED·EXECUTION_SUCCESS)
- 연결 상한 429 (sessionLimit=3 경계 정확)
- UUID v4 검증 400
- 동시 실행 차단 409 (Day 3 회귀)
- traceId 응답 헤더 정상

다음 세션은 Day 5 — Vue 프런트엔드 착수.
