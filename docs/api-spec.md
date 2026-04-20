# API 명세

> **IFMS (보험사 금융 IT 인터페이스 통합관리시스템)**
> 백엔드·프런트엔드 구현자 간 계약 문서
> 기준: [planning.md](planning.md) v0.3 / [erd.md](erd.md) v0.4

---

## 1. 개요

### 1.1 Base URL · 버전 · Content-Type

| 항목 | 값 |
|---|---|
| Base URL (개발) | `http://localhost:8080/api` |
| Base URL (운영 가정) | `https://ifms.example.com/api` |
| API 버전 | v1 (경로에 포함하지 않음. 변경 시 `/api/v2/…`) |
| 요청 Content-Type | `application/json; charset=UTF-8` |
| 응답 Content-Type | `application/json; charset=UTF-8` / SSE는 `text/event-stream` |
| 문자 인코딩 | UTF-8 고정 |
| 타임존 | `Asia/Seoul` (서버 기본) |

### 1.2 공통 응답 래핑 `ApiResponse<T>`

모든 JSON 응답은 아래 구조로 래핑한다.

```json
{
  "success": true,
  "data": { ... },
  "message": null,
  "timestamp": "2026-04-20T10:00:00.000+09:00"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `success` | boolean | 성공 여부 (2xx = true, 4xx/5xx = false) |
| `data` | T \| null | 성공 시 실제 응답 본문 |
| `message` | string \| null | 실패 시 사유 메시지 |
| `timestamp` | ISO-8601 문자열 | 응답 생성 시각 (offset 포함) |

### 1.3 공통 에러 코드 표

| HTTP | `error_code` | 용도 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 요청 본문·파라미터 검증 실패 |
| 400 | `CONFIG_JSON_INVALID` | `config_json` 금지 키/시크릿 패턴 포함 |
| 400 | `INTERFACE_INACTIVE` | 비활성 인터페이스 실행 시도 |
| 400 | `QUERY_PARAM_CONFLICT` | 상호 배제 파라미터 동시 지정 (§5.1 `from`·`to`·`since`) |
| 401 | `UNAUTHENTICATED` | 세션 없음·만료 |
| 403 | `FORBIDDEN` | 권한 부족 (Role 기반, 프로토타입에서는 미사용) |
| 403 | `RETRY_FORBIDDEN_ACTOR` | 타 사용자 로그 재처리 시도 (`actor_id` 불일치) |
| 404 | `INTERFACE_NOT_FOUND` / `EXECUTION_NOT_FOUND` | 리소스 부재 |
| 409 | `DUPLICATE_NAME` | 인터페이스 `name` UNIQUE 위반 |
| 409 | `DUPLICATE_RUNNING` | 동일 인터페이스 실행 중복 |
| 409 | `OPTIMISTIC_LOCK_CONFLICT` | 낙관적 락 충돌 |
| 409 | `RETRY_CHAIN_CONFLICT` | 재처리 체인 분기 시도 (advisory lock(`parent_log_id`) 실패 또는 `uk_log_parent` 위반, ADR-005 Q4) |
| 409 | `RETRY_LIMIT_EXCEEDED` | `retry_count >= max_retry_snapshot` (체인 루트 등록 시점 스냅샷, ADR-005 Q1) |
| 409 | `RETRY_NOT_LEAF` | 체인 중간 노드 재처리 시도 |
| 409 | `RETRY_TRUNCATED_BLOCKED` | `payload_truncated=true` 로그 재처리 시도 |
| 413 | `PAYLOAD_TOO_LARGE` | 요청 본문 크기 초과 |
| 429 | `TOO_MANY_CONNECTIONS` | SSE 연결 상한 초과 |
| 400 | `DELTA_SINCE_TOO_OLD` | `since` 파라미터가 24시간 하한을 초과 |
| 429 | `DELTA_RATE_LIMITED` | delta 호출 60초/10회 초과 |
| 500 | `INTERNAL_ERROR` | 서버 내부 오류 (스택트레이스 응답 금지) |
| 501 | `NOT_IMPLEMENTED` | 아직 구현되지 않은 엔드포인트 (Day 3 이전 `execute` 같은 stub) |

**에러 코드 우선순위** (복수 조건 동시 위배 시 상위부터 반환)

재처리 엔드포인트 `POST /api/executions/{id}/retry` 기준 평가 순서:

1. `UNAUTHENTICATED` (401)
2. `EXECUTION_NOT_FOUND` (404)
3. `INTERFACE_NOT_FOUND` (404) — 원본 로그의 연결 인터페이스가 하드 삭제된 극단 케이스 (실제로는 `ON DELETE RESTRICT`로 차단)
4. `RETRY_FORBIDDEN_ACTOR` (403) — **체인 루트의 `actor_id`** 기준 (`COALESCE(self.root_log_id, self.id)`, ADR-005 Q2)
5. `INTERFACE_INACTIVE` (400) — 신규 실행과 동일 정책, 체인 재처리도 차단 (ADR-005 Q3)
6. `RETRY_NOT_LEAF` (409)
7. `RETRY_TRUNCATED_BLOCKED` (409)
8. `RETRY_LIMIT_EXCEEDED` (409) — `max_retry_snapshot` 기준 (체인 루트 등록 시점, ADR-005 Q1)
9. `DUPLICATE_RUNNING` (409) — advisory lock(`interface_config_id`) 획득 실패 시 우선 발화 (ADR-005 Q4)
10. `RETRY_CHAIN_CONFLICT` (409) — advisory lock(`parent_log_id`) 획득 실패 시 발화. INSERT 시점 `uk_log_parent` 23505는 사후 safety net (ADR-005 Q4)

프런트는 이 순서대로 분기하며, 서버 구현은 동일 순서를 Service 레이어에서 강제한다. **advisory lock 획득 순서는 `interface_config_id` → `parent_log_id`** (deadlock 방지, §9.1 참조).

> **단일 출처**: 아래 §4.5·§5.3의 "에러 케이스" 표는 **케이스별 메시지·추가 필드 설명**만 제공하며, 평가 순서의 정본은 본 표다. 순서 변경 시 본 표만 수정.

### 1.4 타임스탬프 포맷

모든 요청·응답 타임스탬프는 **ISO-8601 확장형**을 사용한다.

```
yyyy-MM-dd'T'HH:mm:ss.SSSXXX
예시: 2026-04-20T10:00:00.000+09:00
```

- 파싱 불가 시 400 `VALIDATION_FAILED`
- epoch millis 같은 대체 포맷은 **허용하지 않음**
- 서버 내부 저장은 `LocalDateTime`이지만 응답은 offset 포함 ISO-8601로 직렬화

---

## 2. 인증·권한

### 2.1 세션 기반 최소 인증

프로토타입 범위에서는 **Spring Security 세션 인증**을 채택한다. JWT·OAuth2는 운영 전환 시([planning.md §12.2](planning.md#122-운영-고도화)).

- 로그인: `POST /login` (Spring Security 기본 폼)
- 로그아웃: `POST /logout`
- 세션 쿠키: `JSESSIONID` (HttpOnly, **SameSite=Lax**, 운영 시 Secure)
- CSRF 보호: 세션 쿠키 기반이므로 **CSRF 토큰 필수**. Spring Security의 `CookieCsrfTokenRepository.withHttpOnlyFalse()` 사용 → 브라우저가 `XSRF-TOKEN` 쿠키를 읽어 `X-XSRF-TOKEN` 헤더로 동봉 (SPA 관례)
- CSRF 예외 엔드포인트: 없음. 모든 상태 변경(POST/PATCH/DELETE)은 CSRF 헤더 필수
- 미인증 접근 → 401 `UNAUTHENTICATED`, CSRF 실패 → 403 `FORBIDDEN`

### 2.2 SSE 엔드포인트 인증 필터

`GET /api/monitor/stream`은 `EventSource`가 커스텀 헤더를 보낼 수 없으므로 **세션 쿠키 기반 인증**에 의존한다.

```
SecurityFilterChain 순서
  1. CorsFilter
  2. SessionAuthFilter         ← /api/monitor/stream 포함
  3. ConnectionLimitFilter     ← 계정당·세션당 상한
  4. SseEmitterController
```

- 미인증 요청: 401 응답 + SSE 연결 거부
- 세션 만료 중 이벤트: 서버가 `event: UNAUTHORIZED` emit 후 연결 종료

### 2.3 `actor_id` 추출 규칙

`ExecutionLog.actor_id`는 모든 실행 기록의 필수 필드([erd.md §3.2](erd.md)). 인증 주체별 생성 규칙:

| 인증 상태 | 저장 값 예시 | 규칙 |
|---|---|---|
| 이메일 로그인 | `EMAIL:3a7bd3e2360a…` (64 hex) | `"EMAIL:" + SHA-256(lowercase(email))` |
| SSO OIDC `sub` | `SSO:9f86d081884c…` | `"SSO:" + sub` (UUID·opaque 그대로 허용, 255자 내) |
| 내부 스케줄러 | `SYSTEM` | 고정 상수 |
| 미인증 Mock 테스트 | `ANONYMOUS_a9b2…` | `"ANONYMOUS_" + SHA-256(salt + client_ip)[:16]`, salt는 `ifms.actor.anon-salt` 주입 (평문 IP 레인보우 테이블 역산 방어) |

**`SaltValidator` 기동 거부 규약** — 운영 환경(`prod` 프로파일)에서만 활성화되는 `@Profile("prod & !dev")` SpEL 빈이 `@PostConstruct` 시점에 `ifms.actor.anon-salt` 값을 검증한다. 값이 (a) 비어 있거나 (b) `REQUIRED_SET_ME_` 접두사로 시작하면 `IllegalStateException`으로 **ApplicationContext 초기화 실패** — 기본값 salt가 운영에 배포되는 사고를 차단한다. `dev`·`local`·`test` 프로파일에서는 빈 생성 자체가 스킵된다.

- 이메일 평문 저장 금지 (개인정보보호법 제3조 최소수집)
- 검색 시 재해시하여 비교
- `AuditContext.currentActor()` 유틸에서 위 규칙 일관 적용

### 2.4 Role (설계만)

**프로토타입 구현 범위**: 단일 Role(`OPERATOR`)만 실장. 본 명세의 각 엔드포인트 "권한:" 표기는 **설계상 의도된 권한**이며, v0.1 구현에서는 모든 인증 사용자가 `OPERATOR`로 동작한다. 운영 전환 시 아래 분리:

| Role | 권한 |
|---|---|
| OPERATOR | 인터페이스 CRUD·실행·재처리·모니터링 (현 범위) |
| ADMIN | OPERATOR + 시스템 설정·사용자 관리 |
| AUDITOR | 이력·대시보드 **조회 전용** (실행 금지) |

Role 기반 접근 제어는 `@PreAuthorize("hasRole('OPERATOR')")`로 엔드포인트별 적용 (운영 전환 시 실장). 감사 검토 시 "권한: OPERATOR" 표기를 구현된 강제 제약으로 오인하지 않도록 주의.

---

## 3. 공통 규약

### 3.1 페이지네이션

모든 목록 엔드포인트는 Spring `Pageable`을 사용한다.

| 파라미터 | 기본값 | 최대 | 설명 |
|---|---|---|---|
| `page` | 0 | - | 0-indexed. 음수 → 400 `VALIDATION_FAILED` |
| `size` | 20 | **100** | 초과 시 **100으로 클램프** + `X-Size-Clamped: true` 응답 헤더. `size<1` 또는 비숫자 → 400 |
| `sort` | 엔드포인트별 기본 | - | `field,asc` 또는 `field,desc` |

- `@PageableDefault(size = 20)` + `PageableHandlerMethodArgumentResolverCustomizer.setMaxPageSize(100)` 전역 적용
- 클램프 선택 이유: 실수로 큰 값을 보낸 클라이언트가 **기능 장애가 아닌 성능 상한으로 귀결**되도록 함. 악성 입력(`size=-1`)만 400으로 차단
- 응답은 Spring `Page<T>` 직렬화 형태 그대로. **페이지 본문은 항상 `ApiResponse.data.content`** 경로로 일관 (이중 래핑 고정 규약)

```json
{
  "content": [ ... ],
  "pageable": { "pageNumber": 0, "pageSize": 20, "sort": { ... } },
  "totalElements": 127,
  "totalPages": 7,
  "number": 0,
  "size": 20,
  "numberOfElements": 20,
  "first": true,
  "last": false
}
```

### 3.2 정렬·필터

- 필터 파라미터는 null 허용(생략 = 전체)
- 복수 값 필터는 `status=SUCCESS&status=FAILED` 반복형
- 날짜 범위: `from` / `to` 모두 ISO-8601, 양끝 포함(inclusive)

### 3.3 `Last-Event-ID` / `since` 규약

SSE 재연결 및 폴백 조회를 위한 2단계 안전망:

1. **1차 방어** — SSE 재연결 시 브라우저 자동 전송 `Last-Event-ID` 헤더 → 서버 링버퍼에서 미전송 이벤트 재전송 ([planning.md §5.4](planning.md#54-실시간-모니터링-sse-채택-근거))
2. **2차 방어** — 링버퍼 밀림 시 프런트가 `GET /api/executions?since={timestamp}` 호출해 누락분 회수

`since` 파라미터 규약:

- 포맷: ISO-8601 (§1.4)
- 시맨틱: `started_at > since` (strict greater, 중복 수신 방지)
- 생략 시: 전체 조회 (기존 동작)
- 최대 허용 범위 — **일반 호출**: `since < now() - 24h`면 400 `VALIDATION_FAILED` (무차별 덤프 방어). 경계는 strict less-than — 정확히 24시간 전은 허용
- 최대 허용 범위 — **복구 호출**: `?mode=RECOVERY` 쿼리 추가 시 최대 7일까지 허용. 단 아래 조건 충족 필수
  - `Role=ADMIN` (프로토타입 범위에선 인증된 `OPERATOR`로 완화 + **자신의 `actor_id`가 생성한 로그로 쿼리 자동 제약**. 타인 이력 조회는 `Role=ADMIN` 실장 이후)
  - 호출 즉시 `audit_log`에 `RECOVERY_FETCH` 엔트리 기록 (`actor_id`·시각·범위)
  - **`actor_id` 기준 5회/시간** rate limit (IP 기준 아님 — NAT 뒤 공유 환경에서 선의 사용자 차단 방지)
- 24시간 초과면서 `mode=RECOVERY`가 없으면 400 + 응답 메시지에 `"복구 모드는 ?mode=RECOVERY를 사용하세요"` 힌트 제공

### GET /api/executions/delta

링버퍼(5분/1000건) 초과 단절 시 프런트가 `RESYNC_REQUIRED` 수신 후 호출하는 공백 메꾸기 API.

**쿼리 파라미터**
- `since` (ISO-8601 OffsetDateTime) — 최초 호출. 하한 `now - 24h`, 초과 시 `400 DELTA_SINCE_TOO_OLD`
- `cursor` (base64(ISO-8601)) — 2페이지 이후. `since`와 동시 지정 시 `cursor` 우선
- `limit` (기본 500, 최대 1000)

**응답** `ApiResponse<DeltaResponse>`

    {
      "success": true,
      "data": {
        "items": [{
          "id": 123,
          "interfaceConfigId": 1,
          "interfaceName": "example",
          "status": "SUCCESS",
          "triggeredBy": "MANUAL",
          "startedAt": "2026-04-20T10:00:00+09:00",
          "finishedAt": "2026-04-20T10:00:01+09:00",
          "durationMs": 512,
          "retryCount": 0,
          "parentLogId": null,
          "errorMessage": null
        }],
        "truncated": false,
        "nextCursor": null
      }
    }

**보안·감사**
- 세션 인증 필수. actor 필터 없음(운영자 전체 관측 허용).
- actor 기준 60초/10회 rate limit 초과 시 `429 DELTA_RATE_LIMITED`.
- 성공/실패 모두 감사 로그 1줄: `actor={hash} since={iso} returned_count={n} truncated={bool} limit={n}`.

**페이지네이션**
- `limit+1` 서버 조회 후 `truncated = size > limit`. `truncated=true`면 마지막 1건 drop하고 `nextCursor = base64(last.startedAt)`.
- `truncated=false`면 `nextCursor=null`.

**마이크로초 경계**
동일 μs에 다수 행 존재 시 경계 1건 유실 가능 — 원본 DB append-only 보존. RESYNC 재호출로 복구.

### 3.4 민감정보 마스킹 적용 지점 (Defense in Depth)

**1차 방어 (정상 경로)**: `MaskingRule`은 `MockExecutor` 반환 직후 `ExecutionResult` 생성 지점에서 **저장 경로와 SSE emit 경로 양쪽에 동일하게 적용**된다 ([erd.md §3.2](erd.md)). 정상 경로에서는 DB에 이미 마스킹본만 존재.

**2차 방어 (Controller 응답 직전)**: Controller는 응답 DTO 직렬화 직전에 **가벼운 패턴 스캐너** `DefensiveMaskingFilter`를 한 번 더 통과시킨다. 정상 데이터에는 no-op(패턴 미매치)이므로 성능 영향 미미(~1ms/응답).

- 대상: JWT 패턴, 신용카드(Luhn), 주민번호, 이메일
- 우회 경로 방어:
  - 운영자가 `psql`로 직접 INSERT한 레코드
  - Flyway 마이그레이션이 평문을 주입한 경우
  - `MaskingRule` 누락 배포(회귀)
- 탐지 시 동작: 해당 필드 `"***MASKED***"` 치환 + `audit_log`에 `POST_HOC_MASK_APPLIED` 경고 기록 (마스킹 회귀 조기 감지)

**DefensiveMaskingFilter 구현 규약** (`ResponseBodyAdvice<ApiResponse<?>>`)

- **적용 대상**: `ApiResponse<?>` 래핑 응답만. SSE(`text/event-stream`)·`/actuator/**`·Spring 기본 `/error`·정적 리소스는 미적용 — Day 4 `SecurityConfig` 완전판에서 `/actuator` 접근 차단 + `server.error.whitelabel.enabled=false`로 보완
- **ErrorDetail skip**: `body.data instanceof ErrorDetail`이면 마스킹 건너뛴다 — 에러 메시지 왜곡(예: "API 키가 만료됨" → "API ***MASKED***가 만료됨") 방지
- **Page.content[] 재귀**: `body.data`가 `Page<T>`(또는 Spring `Page` 직렬화 결과)면 `content[]` 내부까지 재귀 순회하여 목록 응답에서도 우회 데이터 방어
- **재귀 가드**: `MaskingRule.maxDepth = 10`, 총 노드 순회 상한 10,000 (악성 중첩 JSON으로 스택 오버플로·CPU DoS 차단)
- **X-Size-Clamped 헤더 주입**: 동일 `DefensiveMaskingFilter` 내에서 raw query param `size`를 읽어 `PaginationConstants.MAX_PAGE_SIZE` 초과 시 `ServerHttpResponse.getHeaders().add("X-Size-Clamped","true")`. Interceptor 경로는 응답 커밋 타이밍 이슈로 폐기
- **성능 목표**: p95 < 50ms (size=100 × 64KB payload 응답 기준, `Pattern` 인스턴스는 `static final` 고정)

**§3.5 낙관적 락 `serverSnapshot`**: DB 마스킹을 거치지 않은 최신 스냅샷이므로 `MaskingRule`(1차)을 응답 직전 명시적으로 수행 + `DefensiveMaskingFilter`(2차)까지 통과.

### 3.5 낙관적 락 충돌 409 표준

`PATCH /api/interfaces/{id}` 실패 시 표준 응답:

```json
{
  "success": false,
  "message": "다른 사용자가 수정했습니다. 최신 버전을 확인하세요.",
  "data": {
    "errorCode": "OPTIMISTIC_LOCK_CONFLICT",
    "submittedVersion": 3,
    "currentVersion": 4,
    "serverSnapshot": {
      "name": "금감원_일일보고_REST",
      "description": "…",
      "endpoint": "https://fss.or.kr/api/daily",
      "configJson": {
        "headers": { "Authorization": "***MASKED***" },
        "secretRef": "vault://ifms/rest/fss-token"
      },
      "status": "ACTIVE",
      "updatedAt": "2026-04-20T10:05:00.000+09:00"
    }
  },
  "timestamp": "2026-04-20T10:05:01.123+09:00"
}
```

- `serverSnapshot`에는 **`MaskingRule` 적용본**만 내려보냄 (`secretRef` 프리픽스는 노출 허용, 값 평문 금지)
- 프런트는 diff 다이얼로그로 "내 변경 vs 서버 최신"을 대조하고 사용자 선택 후 재제출
- **재충돌 처리**: 재제출 중 제3자 수정으로 또 충돌 시에도 동일 포맷 409 응답. 프런트는 같은 다이얼로그를 다시 열어 최신 `serverSnapshot`으로 대조
- **연속 충돌 경보**: 동일 `interfaceConfigId`에 대해 5회 이상 연속 `OPTIMISTIC_LOCK_CONFLICT`가 발생하면 응답 `data.warnings`에 `CONCURRENT_EDIT_STORM` 플래그 추가. 프런트는 사용자에게 "다른 사용자와 편집 경합 중" 안내
- **카운터 저장소**: 프로토타입 범위는 **in-memory** `ConcurrentHashMap` (단일 인스턴스 가정). 멀티 인스턴스 전환 시 Redis/Hazelcast 공유 카운터로 승격 — ADR 후보 `"다중 인스턴스 상태 공유 전략"`

---

## 4. Interface API

### 4.1 `GET /api/interfaces` — 목록 조회

| 항목 | 내용 |
|---|---|
| 권한 | `OPERATOR` |
| 기본 정렬 | `createdAt,desc` |
| 필터 | `status`, `protocol`, `name` (부분 일치, LIKE `%name%`) |

> **성능 주의**: `name` 부분 일치(`%name%`)는 B-tree 인덱스 미활용 → Full Table Scan. `interface_config`는 마스터 테이블(수백 건 수준)이라 현 프로토타입 범위에서 허용. **운영 전환 시** `pg_trgm` 확장 + GIN 인덱스(`CREATE INDEX idx_ifc_name_trgm ON interface_config USING GIN (name gin_trgm_ops)`)로 전환 필수.

**요청**

```
GET /api/interfaces?page=0&size=20&status=ACTIVE&protocol=REST
```

**응답 200 OK**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "name": "금감원_일일보고_REST",
        "description": "금감원 일일 실적 보고",
        "protocol": "REST",
        "endpoint": "https://fss.or.kr/api/daily",
        "httpMethod": "POST",
        "scheduleType": "CRON",
        "cronExpression": "0 0 6 * * *",
        "timeoutSeconds": 30,
        "maxRetryCount": 3,
        "status": "ACTIVE",
        "version": 0,
        "createdAt": "2026-04-20T09:00:00.000+09:00",
        "updatedAt": "2026-04-20T09:00:00.000+09:00"
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "number": 0,
    "size": 20
  },
  "message": null,
  "timestamp": "2026-04-20T10:00:00.000+09:00"
}
```

### 4.2 `GET /api/interfaces/{id}` — 단건 조회

**응답 200 OK**: `InterfaceConfigResponse` 단일 객체
**에러**: 404 `INTERFACE_NOT_FOUND`

### 4.3 `POST /api/interfaces` — 등록

| 항목 | 내용 |
|---|---|
| 권한 | `OPERATOR` |
| 응답 상태 | 201 Created |
| 검증 | Bean Validation + `ConfigJsonValidator` (`@PreUpdate`/`@PrePersist` 경로) |

**요청**

```json
{
  "name": "금감원_일일보고_REST",
  "description": "금감원 일일 실적 보고",
  "protocol": "REST",
  "endpoint": "https://fss.or.kr/api/daily",
  "httpMethod": "POST",
  "configJson": {
    "headers": { "Content-Type": "application/json" },
    "secretRef": "vault://ifms/rest/fss-token"
  },
  "scheduleType": "CRON",
  "cronExpression": "0 0 6 * * *",
  "timeoutSeconds": 30,
  "maxRetryCount": 3
}
```

**응답 201 Created**: 생성된 `InterfaceConfigResponse`

**에러 케이스**

| 상태 | `errorCode` | 상황 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `name` 공백, `timeoutSeconds` 범위 밖, `cronExpression` 누락 등 |
| 400 | `CONFIG_JSON_INVALID` | `configJson`에 `password` 평문·JWT 패턴 포함 |
| 409 | `DUPLICATE_NAME` | `name` UNIQUE 제약 위반 |

### 4.4 `PATCH /api/interfaces/{id}` — 수정

| 항목 | 내용 |
|---|---|
| 권한 | `OPERATOR` |
| 동시성 | 낙관적 락 (`version` 필수) |

**요청**

```json
{
  "version": 3,
  "description": "금감원 일일 실적 보고 (2026-04 개정)",
  "timeoutSeconds": 45
}
```

- `version`은 **필수**. 누락 시 400
- 부분 필드만 전달 허용 (null 필드는 미변경)

**응답 200 OK**: 갱신된 `InterfaceConfigResponse`

**에러**

- 404 `INTERFACE_NOT_FOUND`
- 409 `OPTIMISTIC_LOCK_CONFLICT` (§3.5 표준 응답)
- 400 `CONFIG_JSON_INVALID`

### 4.5 `POST /api/interfaces/{id}/execute` — 수동 실행

> **Day 2 현 상태 (stub)**: 501 `NOT_IMPLEMENTED` 반환.
> body: `{"success":false,"data":{"errorCode":"NOT_IMPLEMENTED","extra":{"note":"..."}},"message":"..."}`.
> 존재/비존재 oracle 차단을 위해 `interfaceId`를 응답에 에코하지 않는다. **Day 3 `ExecutionService` 구현 시 아래 201 스펙으로 교체**.

| 항목 | 내용 |
|---|---|
| 권한 | `OPERATOR` |
| 응답 상태 | 201 Created |
| 실행 방식 | 비동기 (`@Async`), 즉시 `logId` 반환 |
| 중복 방지 | advisory lock + `uk_log_running` 부분 UNIQUE 2+1중 방어 (ADR-004) |

**요청**: 본문 없음

**응답 201 Created**

```json
{
  "success": true,
  "data": {
    "logId": 12345,
    "status": "RUNNING",
    "interfaceConfigId": 1,
    "triggeredBy": "MANUAL",
    "startedAt": "2026-04-20T10:00:00.000+09:00"
  },
  "timestamp": "2026-04-20T10:00:00.050+09:00"
}
```

**에러**

- 404 `INTERFACE_NOT_FOUND`
- 409 `DUPLICATE_RUNNING` — 이미 해당 인터페이스의 `RUNNING` 로그 존재
- 400 — 인터페이스 `status=INACTIVE`일 때

```json
{
  "success": false,
  "message": "이미 실행 중인 동일 인터페이스가 있습니다.",
  "data": {
    "errorCode": "DUPLICATE_RUNNING",
    "runningLogId": 12340,
    "runningStartedAt": "2026-04-20T09:59:30.000+09:00"
  },
  "timestamp": "2026-04-20T10:00:00.100+09:00"
}
```

---

## 5. Execution API

### 5.1 `GET /api/executions` — 이력 조회 / SSE 폴백

| 항목 | 내용 |
|---|---|
| 권한 | `OPERATOR`, `AUDITOR` (조회 전용) |
| 기본 정렬 | `startedAt,desc` |

**필터 파라미터**

| 이름 | 타입 | 설명 |
|---|---|---|
| `status` | enum (복수) | `RUNNING` / `SUCCESS` / `FAILED` |
| `interfaceConfigId` | long | 인터페이스별 이력 |
| `triggeredBy` | enum | `MANUAL` / `SCHEDULER` / `RETRY` |
| `from` / `to` | ISO-8601 | `started_at` 범위 (inclusive). 이력 화면 UI용 |
| `since` | ISO-8601 | §3.3 SSE 폴백용. `started_at > since` strict |

**`from`·`to`·`since` 상호 배제 규칙**

- `since`와 `from`/`to`는 의도가 다름 (폴백 vs UI 조회). **동시 지정 시 400 `QUERY_PARAM_CONFLICT`**
- `from > to` 역전 → 400 `VALIDATION_FAILED`
- `since` 24시간 초과 규칙은 §3.3 참조

**N+1 방지**

`ListView`는 `interfaceName` / `protocol`을 포함하므로 Repository는 `@EntityGraph(attributePaths = {"interfaceConfig"})`로 fetch join 강제. 구현 예시:

```java
@EntityGraph(attributePaths = {"interfaceConfig"})
Page<ExecutionLog> findAll(Specification<ExecutionLog> spec, Pageable pageable);
```

**요청 예시**

```
GET /api/executions?status=FAILED&since=2026-04-20T09:55:00.000+09:00&page=0&size=50
```

**응답 200 OK**: `Page<ExecutionLogListView>`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 12345,
        "interfaceConfigId": 1,
        "interfaceName": "금감원_일일보고_REST",
        "protocol": "REST",
        "status": "FAILED",
        "triggeredBy": "MANUAL",
        "retryCount": 0,
        "payloadTruncated": false,
        "errorCode": "TIMEOUT_EXCEEDED",
        "startedAt": "2026-04-20T09:58:12.000+09:00",
        "finishedAt": "2026-04-20T09:58:42.123+09:00",
        "durationMs": 30123
      }
    ],
    "totalElements": 1
  }
}
```

> **주의**: `ListView`에는 `request_payload` / `response_payload` / `error_message` **원문이 포함되지 않는다**. 상세는 §5.2.

### 5.2 `GET /api/executions/{id}` — 실행 상세

**응답 200 OK**

```json
{
  "success": true,
  "data": {
    "id": 12345,
    "interfaceConfig": {
      "id": 1,
      "name": "금감원_일일보고_REST",
      "protocol": "REST"
    },
    "parentLogId": null,
    "triggeredBy": "MANUAL",
    "actorId": "EMAIL:3a7bd3e2360a…",
    "clientIp": "10.0.1.23",
    "status": "FAILED",
    "startedAt": "2026-04-20T09:58:12.000+09:00",
    "finishedAt": "2026-04-20T09:58:42.123+09:00",
    "durationMs": 30123,
    "payloadFormat": "JSON",
    "payloadTruncated": false,
    "requestPayload": { "reportDate": "2026-04-20" },
    "responsePayload": null,
    "errorCode": "TIMEOUT_EXCEEDED",
    "errorMessage": "응답 대기 30초 초과",
    "retryCount": 0
  }
}
```

**`PayloadHolder` 직렬화 규약** ([erd.md §10.2](erd.md) JPA 엔티티)

엔티티는 `request_payload` (JSONB) / `request_payload_xml` (TEXT) 4개 컬럼을 가지지만 DTO는 **단일 `requestPayload` / `responsePayload` 필드**로 평탄화한다. XML인 경우 문자열 그대로, JSON인 경우 객체로 직렬화.

- `payload_format=JSON`: `requestPayload`는 JSON 객체
- `payload_format=XML`: `requestPayload`는 문자열 (`<soap:Envelope>…</soap:Envelope>`)
- 엔티티 XML 컬럼(`request_payload_xml` 등)은 `@JsonIgnore`로 직렬화 제외
- DTO 통합 게터 `getRequestPayload()`가 `payload_format`에 따라 분기

**에러**

- 404 `EXECUTION_NOT_FOUND`

### 5.3 `POST /api/executions/{id}/retry` — 재처리

| 항목 | 내용 |
|---|---|
| 권한 | `OPERATOR` |
| 응답 상태 | 201 Created |
| 제약 | 체인 최신 리프, `payload_truncated=false`, `retry_count < max_retry_snapshot` (체인 루트 등록 시점 스냅샷, ADR-005 Q1) |

**요청**: 본문 없음

**응답 201 Created**

```json
{
  "success": true,
  "data": {
    "logId": 12346,
    "parentLogId": 12345,
    "status": "RUNNING",
    "retryCount": 1,
    "startedAt": "2026-04-20T10:01:00.000+09:00"
  }
}
```

**에러 케이스** (평가 순서는 §1.3 우선순위 표 참조)

| 상태 | `errorCode` | 상황 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 원본이 `SUCCESS` 상태 |
| 400 | `INTERFACE_INACTIVE` | 연결된 인터페이스 `status=INACTIVE` |
| 403 | `RETRY_FORBIDDEN_ACTOR` | **체인 루트** 로그의 `actor_id`와 현재 세션 `actor_id` 불일치 (타인 로그 재처리 차단). 루트 = `COALESCE(self.root_log_id, self.id)` (ADR-005 Q2) |
| 404 | `EXECUTION_NOT_FOUND` | logId 부재 |
| 409 | `DUPLICATE_RUNNING` | advisory lock(`interface_config_id`) 획득 실패 또는 해당 인터페이스가 이미 `RUNNING` (ADR-005 Q4) |
| 409 | `RETRY_CHAIN_CONFLICT` | advisory lock(`parent_log_id`) 획득 실패 또는 `uk_log_parent` 위반 (동시 재처리, ADR-005 Q4) |
| 409 | `RETRY_NOT_LEAF` | 체인 최신 리프가 아닌 중간 노드 |
| 409 | `RETRY_LIMIT_EXCEEDED` | `retry_count >= max_retry_snapshot` (체인 루트 등록 시점 스냅샷) |
| 409 | `RETRY_TRUNCATED_BLOCKED` | `payload_truncated=true` |

**`RETRY_FORBIDDEN_ACTOR` 예외 규칙**

- `actor_id = SYSTEM` 원본(스케줄러 실행 실패)은 모든 `OPERATOR`가 재처리 가능 (운영상 필요)
- 원본 `actor_id`가 `ANONYMOUS_*`인 경우 재처리 금지 (원 소유자 확인 불가)
- 운영 전환 시 `Role=ADMIN`은 `actor_id` 무관 재처리 허용

```json
{
  "success": false,
  "message": "재처리 최대 횟수를 초과했습니다.",
  "data": {
    "errorCode": "RETRY_LIMIT_EXCEEDED",
    "currentRetryCount": 3,
    "maxRetrySnapshot": 3,
    "rootLogId": 12340
  }
}
```

> `maxRetrySnapshot`은 체인 루트 INSERT 시점의 인터페이스 정책값 스냅샷이다 (ADR-005 Q1). 운영자가 PATCH로 `interface_config.max_retry_count`를 변경해도 진행 중 체인은 영향받지 않으므로, 클라이언트는 이 값을 기준으로 "더 이상 재처리 불가" 다이얼로그를 표시한다.

---

## 6. Monitor API

### 6.1 `GET /api/monitor/stream` — SSE 스트림

| 항목 | 내용 |
|---|---|
| 권한 | `OPERATOR`, `AUDITOR` |
| Content-Type | `text/event-stream` |
| 타임아웃 | 3분 (브라우저 자동 재연결) |
| 연결 상한 | **세션당 3개**, **계정당 10개** (`ConnectionLimitFilter`) — 관리자 다중 장치(PC·모니터링 TV·모바일) 수용 |

**요청 예시**

```
GET /api/monitor/stream?clientId=7f3e9a8b-c2d4-4f6e-8a1b-2c3d4e5f6789
Last-Event-ID: 12345     (재연결 시 브라우저 자동 전송)
```

- `clientId` 쿼리 파라미터:
  - 포맷 규약: **UUID v4** (정규식 `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`). 위반 시 400 `VALIDATION_FAILED`
  - 생략 시 서버가 UUID 생성
  - **세션 바인딩**: 서버는 `(세션ID, clientId)` 쌍을 `ConnectionLimitFilter`에 기록. 다른 세션이 동일 `clientId`로 접근하면 400 거부 (타 세션 이벤트 스푸핑 차단)
- 연결 상한 초과 시 429 `TOO_MANY_CONNECTIONS`

**링버퍼 보관 정량 기준**

`Last-Event-ID` 재전송이 가능한 범위를 명시한다. 이 경계를 넘으면 브라우저는 §3.3 `?since=` 폴백으로 전환해야 한다.

| 기준 | 값 |
|---|---|
| 링버퍼 최대 항목 수 | **1,000건** (전역, 계정별 아님) |
| 링버퍼 최대 보관 시간 | **5분** |

- 둘 중 먼저 도달한 조건이 선입 이벤트를 밀어냄
- 재연결 `Last-Event-ID`가 링버퍼에 존재하지 않으면 서버는 `event: RESYNC_REQUIRED` emit → 프런트는 `?since={마지막이벤트timestamp}` 폴백 호출
- 정상 운영 시 분당 실행 이벤트가 200건을 넘으면 1,000건·5분 중 건수 한계가 먼저 터지므로, 폴백 호출이 평시에도 발생 가능

**이벤트 타입**

| `event:` 이름 | 설명 |
|---|---|
| `CONNECTED` | 연결 수락 직후 1회 emit |
| `EXECUTION_STARTED` | 실행 시작 (`status=RUNNING`) |
| `EXECUTION_SUCCESS` | 정상 종료 |
| `EXECUTION_FAILED` | 실패 종료 |
| `HEARTBEAT` | **30초 간격** keep-alive (`data: {}` 빈 객체). 브라우저 3분 재연결 타임아웃보다 여유 유지, 모바일 배터리·데이터 부담 완화 |
| `UNAUTHORIZED` | 세션 만료 감지 직전 재확인 후 emit + 연결 종료. 미전송 이벤트는 `audit_log`에 `SSE_DROPPED_ON_SESSION_EXPIRY`로 건수 기록 (감사 추적) |

**이벤트 페이로드 구조** (`SseEvent`)

```
id: 12346
event: EXECUTION_FAILED
data: {
  "type": "EXECUTION_FAILED",
  "logId": 12345,
  "interfaceId": 1,
  "interfaceName": "금감원_일일보고_REST",
  "status": "FAILED",
  "durationMs": 30123,
  "errorCode": "TIMEOUT_EXCEEDED",
  "timestamp": "2026-04-20T09:58:42.123+09:00"
}

```

- `id:` 라인은 링버퍼 키로 사용되는 단조 증가 시퀀스. 브라우저가 `Last-Event-ID` 헤더로 자동 전송
- 서버 재시작 시 시퀀스 초기화 가능 → 브라우저는 `?since=` 폴백으로 보강 (§3.3)
- payload는 `MaskingRule` 적용된 상태로 emit (§3.4)

### clientId 재할당 (grace 2초)

동일 `clientId`가 다른 세션에 바인딩 중일 때 `subscribe` 진입 시:
1. 이전 세션의 emitter를 2초 delayed complete 스케줄
2. 새 세션에 즉시 재할당, 이후 이벤트는 새 emitter에만 라우팅
3. 감사 로그: `event=CLIENT_ID_REASSIGNED clientId=<uuid> old_session=<hash> new_session=<hash> actor=<hash>`

브라우저 F5·탭 이동 시 이전 emitter complete 지연과의 경쟁 상태를 흡수한다.

### UNAUTHORIZED 이벤트

세션 만료를 `HttpSessionListener`로 감지하면 해당 세션의 모든 emitter에 `event: UNAUTHORIZED` 1회 송출 후 complete. 프런트는 이 이벤트를 수신해 `close()` + logout + `/login` 이동해야 한다. 감사 로그: `event=SSE_DROPPED_ON_SESSION_EXPIRY sessionId=<hash> clientId=<uuid> actor=<hash>`.

EventSource는 HTTP 401을 onerror 이벤트로만 노출하며 status 필드가 없으므로(WHATWG 스펙), 401 응답은 3초 간격 자동 재연결을 유발한다. 이 이벤트 기반 종료 프로토콜은 자동 재연결 루프를 방지한다.

### 6.2 `GET /api/monitor/dashboard` — 집계

**요청 예시**

```
GET /api/monitor/dashboard?since=2026-04-20T00:00:00.000+09:00
```

- `since` 생략 시 `date_trunc('day', now())` = 당일 00:00 기준

**응답 200 OK**

```json
{
  "success": true,
  "data": {
    "generatedAt": "2026-04-20T10:00:00.000+09:00",
    "since": "2026-04-20T00:00:00.000+09:00",
    "totals": {
      "success": 423,
      "failed": 12,
      "running": 3,
      "total": 438
    },
    "byProtocol": [
      { "protocol": "REST",  "success": 320, "failed": 8, "running": 2 },
      { "protocol": "SOAP",  "success":  40, "failed": 1, "running": 0 },
      { "protocol": "MQ",    "success":  33, "failed": 1, "running": 0 },
      { "protocol": "BATCH", "success":  20, "failed": 2, "running": 1 },
      { "protocol": "SFTP",  "success":  10, "failed": 0, "running": 0 }
    ],
    "recentFailures": [
      {
        "id": 12345,
        "interfaceName": "금감원_일일보고_REST",
        "errorCode": "TIMEOUT_EXCEEDED",
        "startedAt": "2026-04-20T09:58:12.000+09:00"
      }
    ],
    "sseConnections": 3
  }
}
```

---

## 7. 에러 응답 상세

### 7.1 400 Bad Request

**요청 본문 검증 실패**

```json
{
  "success": false,
  "message": "name은(는) 필수 입력 항목입니다, timeoutSeconds은(는) 1 이상 600 이하여야 합니다",
  "data": {
    "errorCode": "VALIDATION_FAILED",
    "fieldErrors": [
      { "field": "name", "rejectedValue": "", "message": "필수 입력 항목" },
      { "field": "timeoutSeconds", "rejectedValue": 0, "message": "1 이상 600 이하" }
    ]
  }
}
```

**`ConfigJsonValidator` 위반**

```json
{
  "success": false,
  "message": "configJson에 평문 시크릿이 포함되어 있습니다.",
  "data": {
    "errorCode": "CONFIG_JSON_INVALID",
    "violations": [
      { "path": "$.headers.Authorization", "reason": "JWT_PATTERN_DETECTED" },
      { "path": "$.password", "reason": "FORBIDDEN_KEY" }
    ]
  }
}
```

**`rejectedValue` 마스킹 규칙** — 일반 `VALIDATION_FAILED` 응답은 `rejectedValue`를 그대로 에코할 수 있으나, 아래 경우는 **무조건 `"***REDACTED***"`로 고정**한다.

- 필드명 또는 JSON 경로가 민감 키 블랙리스트에 매치. 블랙리스트는 `SensitiveKeyRegistry` 단일 출처로, [erd.md §3.3](erd.md) 15종(`password` · `pwd` · `secret` · `apikey` · `api_key` · `token` · `privatekey` · `authorization` · `ssn` · `rrn` · `memberrrn` · `cardno` · `custcardno` · `accountno` · `acctno`)과 동일 집합
- 값이 `MaskingRule` 정규식(JWT, 신용카드, 주민번호, 이메일)에 매치
- `CONFIG_JSON_INVALID` 응답은 `rejectedValue` 필드 자체를 응답에 포함하지 **않음** (path·reason만 반환)

**예외 → ErrorCode 매핑 매트릭스** (GlobalExceptionHandler 정본)

| 예외 | ErrorCode | 비고 |
|---|---|---|
| `ApiException` (sealed: `BusinessException`/`ConflictException`/`NotFoundException`) | `e.getErrorCode()` | 단일 핸들러로 17종 전수 커버 |
| `MethodArgumentNotValidException` | `VALIDATION_FAILED` | `@Valid` 요청 본문. fieldErrors 동봉, REDACTED 규칙 적용 |
| **`ConstraintViolationException`** | `VALIDATION_FAILED` | `@Valid @RequestParam` / `@PathVariable`. 별도 핸들러 필수 — REDACTED 규칙 공유 |
| `HttpMessageNotReadableException` | `VALIDATION_FAILED` | Enum 역직렬화 실패 시 `InvalidFormatException.getTargetType().isEnum()` 검사 후 메시지를 `"유효하지 않은 값입니다"`로 sanitize — enum 값 목록 노출 차단 |
| `MissingServletRequestParameterException` / `MethodArgumentTypeMismatchException` | `VALIDATION_FAILED` | 파라미터 누락·타입 불일치 |
| `DataIntegrityViolationException` | `DUPLICATE_NAME` | SQLState `23505` + constraint name `uk_ifc_name` **이중 판별**. 제약명은 응답 미노출, 논리 필드명(`"name"`)으로 번역. 그 외 UK → `INTERNAL_ERROR` |
| `OptimisticLockingFailureException` | `OPTIMISTIC_LOCK_CONFLICT` | Handler가 `ObjectProvider<InterfaceConfigRepository>`로 `findById()` 재호출 → 새 TX에서 최신 스냅샷 조회 → `serverSnapshot` 동봉. 2차 예외 시 `snapshot=null` |
| `MaxUploadSizeExceededException` | `PAYLOAD_TOO_LARGE` | |
| `AccessDeniedException` | `FORBIDDEN` | Spring Security |
| `AuthenticationException` | `UNAUTHENTICATED` | Spring Security |
| **그 외 `Exception`** | `INTERNAL_ERROR` | `traceId = UUID.randomUUID().toString()` (§7.6), 스택트레이스 미노출 |

`ApiException` 서브클래스로 17종을 모두 표현한다(각 ErrorCode의 HTTP status를 `BusinessException`/`ConflictException`/`NotFoundException`에서 보증). RETRY_*·INTERFACE_INACTIVE·CONFIG_JSON_INVALID 등 2-B 이후 throw되는 에러도 동일 핸들러가 처리.

목적: 사용자가 실수로 평문 비밀번호를 전송했을 때 **400 응답·브라우저 HAR·프록시 로그에 평문 잔존을 차단**.

### 7.2 401 / 403

```json
{ "success": false, "message": "로그인이 필요합니다.", "data": { "errorCode": "UNAUTHENTICATED" } }
```

### 7.3 404

```json
{ "success": false, "message": "인터페이스를 찾을 수 없습니다: id=99", "data": { "errorCode": "INTERFACE_NOT_FOUND" } }
```

### 7.4 409

§3.5 / §4.5 / §5.3 각 케이스 참조. 공통 스키마:

```json
{
  "success": false,
  "message": "<한글 사유>",
  "data": {
    "errorCode": "<enum>",
    "...": "케이스별 추가 필드"
  }
}
```

### 7.5 413 Payload Too Large

- Spring `MultipartProperties.maxRequestSize=1MB` 초과 시
- `request_payload` 64KB는 **DB 저장 단계**에서 truncate되는 별개 관심사 (413 아님)

### 7.6 500 Internal Server Error

```json
{
  "success": false,
  "message": "서버 내부 오류가 발생했습니다. 관리자에게 문의하세요.",
  "data": { "errorCode": "INTERNAL_ERROR", "traceId": "abc123def456" }
}
```

- **스택트레이스·DB 벤더 메시지 노출 금지** ([erd.md §3.2](erd.md) `error_message` 원칙 준수)
- `traceId`로 로그 조회 (`GlobalExceptionHandler`가 MDC `traceId` 주입)
- **`traceId` 포맷 규약**: `UUID.randomUUID().toString()` 고정 (36자 하이픈 포함). **DB 시퀀스·PK 값 사용 금지** — 공격자가 `traceId`로 내부 테이블 증가 패턴을 유추하지 못하도록 함
- **MDC 수명 주기** (스레드 풀 오염 방지):
  1. `INTERNAL_ERROR` 핸들러 진입 즉시 `String traceId = UUID.randomUUID().toString(); MDC.put("traceId", traceId);`
  2. 로그·응답에 동일 `traceId` 기록
  3. `finally { MDC.remove("traceId"); }` 로 스레드 반납 전 정리 필수. 누락 시 스레드 풀 재사용으로 이전 요청 `traceId`가 다음 요청에 섞여 감사 추적 훼손

---

## 8. DTO 레퍼런스

### 8.1 `InterfaceConfigRequest` / `Response`

```java
public class InterfaceConfigRequest {
    @NotBlank          @Size(max=100)    private String name;
    @Size(max=500)                       private String description;
    @NotNull                             private ProtocolType protocol;
    @NotBlank          @Size(max=500)    private String endpoint;
    @Size(max=10)                        private String httpMethod;
    @NotNull                             private Map<String, Object> configJson;
    @NotNull                             private ScheduleType scheduleType;
    @Size(max=100)                       private String cronExpression;
    @Min(1)  @Max(600)                   private Integer timeoutSeconds;
    @Min(0)  @Max(10)                    private Integer maxRetryCount;
    // PATCH 경로에서만 사용
                                         private Long version;
}
```

`Response`는 Entity 필드 + `createdAt`·`updatedAt` 포함, `version`은 낙관적 락 대조용으로 항상 포함.

### 8.2 `ExecutionLogListView` / `ExecutionLogResponse`

```java
public class ExecutionLogListView {
    private Long id;
    private Long interfaceConfigId;
    private String interfaceName;
    private ProtocolType protocol;
    private ExecutionStatus status;
    private TriggerType triggeredBy;
    private Integer retryCount;
    private Boolean payloadTruncated;
    private ErrorCode errorCode;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    // payload·errorMessage 원문 제외 (상세 API에서만 제공)
}

public class ExecutionLogResponse {
    // ListView 필드 + 아래
    private InterfaceConfigSummary interfaceConfig;
    private Long parentLogId;
    private String actorId;
    private String clientIp;
    private String userAgent;
    private PayloadFormat payloadFormat;
    // PayloadHolder 캡슐화: getRequestPayload()/getResponsePayload()가
    // payloadFormat에 따라 JSON Map 또는 XML String을 반환
    private Object requestPayload;
    private Object responsePayload;
    private String errorMessage;
}
```

### 8.3 `SseEvent`

```java
public class SseEvent {
    private Long id;               // 링버퍼 시퀀스, SSE "id:" 라인에도 사용
    private String type;           // EXECUTION_STARTED | SUCCESS | FAILED | CONNECTED | HEARTBEAT
    private Long logId;
    private Long interfaceId;
    private String interfaceName;
    private ExecutionStatus status;
    private Long durationMs;
    private ErrorCode errorCode;
    private LocalDateTime timestamp;
}
```

### 8.4 `DashboardResponse`

§6.2 응답 JSON 구조 참조. 컴포넌트:

- `DashboardTotals`: success/failed/running/total
- `ProtocolBreakdown[]`
- `RecentFailure[]` (상위 10건)
- `sseConnections`: 현재 SSE 연결 수 (디버그용)

---

## 9. (부록) 구현 힌트

> 이 섹션은 **API 계약 외의 백엔드 구현 참고 사항**이다. 프런트엔드 구현자는 §1~§8만 참고하면 된다. 운영 전환 시 별도 내부 문서로 이관 가능.

### 9.1 advisory lock 상수 (주입형)

네임스페이스 key1을 하드코드 대신 `application.yml` 주입형으로 관리한다. 같은 PostgreSQL 인스턴스를 타 서비스와 공유할 경우(운영 전환) 재사용 충돌을 피하기 위함.

```yaml
ifms:
  advisory-lock:
    namespace: 0x49464D53   # 'IFMS' ASCII, 기본값 (일반 실행)
    retry-namespace: 0x49464D54   # 'IFMT' ASCII, 재처리 체인 (ADR-005 Q4)
```

**lock key 2-도메인 분리** (ADR-005 Q4):

| 용도 | key1 (namespace) | key2 | 차단 대상 |
|---|---|---|---|
| 일반 실행 (`POST /api/interfaces/{id}/execute`) | `ifms.advisory-lock.namespace` | `interface_config_id` | 동일 인터페이스 동시 실행 |
| 재처리 (`POST /api/executions/{id}/retry`) | `ifms.advisory-lock.namespace` (1차) + `ifms.advisory-lock.retry-namespace` (2차) | `interface_config_id` (1차) + `parent_log_id` (2차) | 동일 인터페이스 동시 실행 + 동일 부모 동시 재처리 |

**획득 순서** (deadlock 방지): 항상 `interface_config_id` lock → `parent_log_id` lock 순.

```java
@ConfigurationProperties(prefix = "ifms.advisory-lock")
public record AdvisoryLockProperties(int namespace, int retryNamespace) {}

// 일반 실행
jdbcTemplate.queryForObject(
    "SELECT pg_try_advisory_xact_lock(?, ?)",
    Boolean.class, props.namespace(), interfaceConfigId);

// 재처리 (2개 lock 순차)
boolean lock1 = jdbcTemplate.queryForObject(
    "SELECT pg_try_advisory_xact_lock(?, ?)",
    Boolean.class, props.namespace(), interfaceConfigId);
if (!lock1) throw new ConflictException(DUPLICATE_RUNNING);
boolean lock2 = jdbcTemplate.queryForObject(
    "SELECT pg_try_advisory_xact_lock(?, ?)",
    Boolean.class, props.retryNamespace(), parentLogId);
if (!lock2) throw new ConflictException(RETRY_CHAIN_CONFLICT);
```

- 기본값 유지 시 본 문서 예시와 동일 동작
- 운영 DB 공유 환경에서는 두 namespace 모두 고유 값으로 재할당 (ADR로 기록 권장)

### 9.2 OpenAPI(Swagger) 가이드라인

- 모든 Controller에 `@Tag(name="…", description="…")`
- 엔드포인트에 `@Operation(summary="…")` + `@ApiResponse`
- DTO에 `@Schema(description="…", example="…")`
- 인증 필요 엔드포인트에 `@SecurityRequirement(name="session")`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

---

## 10. 체크리스트

- [ ] 모든 엔드포인트가 `ApiResponse<T>` 래핑 응답
- [ ] 타임스탬프가 ISO-8601 offset 포함으로 직렬화 (`+09:00`)
- [ ] `Pageable` `size`: 100 초과 시 **100으로 클램프 + `X-Size-Clamped: true` 헤더**, `size<1`만 400
- [ ] `since` 파라미터: 일반 호출 24h 초과 시 400, `?mode=RECOVERY` 시 7일까지 허용 + `audit_log` 기록 + 5회/시간 rate limit
- [ ] §1.3 에러 코드 우선순위 표대로 서버 평가 순서 강제 (재처리 단위 테스트에 포함)
- [ ] `ConfigJsonValidator`가 `@PrePersist`/`@PreUpdate`에 연결되어 PATCH 우회 차단
- [ ] `CONFIG_JSON_INVALID` 응답에 `rejectedValue` 필드 **미포함** (path·reason만)
- [ ] `VALIDATION_FAILED` 응답의 `rejectedValue`가 민감 키/정규식 매치 시 `"***REDACTED***"` 고정
- [ ] 낙관적 락 409 응답의 `serverSnapshot`이 `MaskingRule` + `DefensiveMaskingFilter` 2중 적용본
- [ ] 연속 5회 충돌 시 `CONCURRENT_EDIT_STORM` 경고 플래그
- [ ] `SseEvent.id`가 단조 증가하며 `Last-Event-ID` 재전송에 사용됨
- [ ] SSE 연결 상한: **세션당 3개 / 계정당 10개** (`ConnectionLimitFilter`)
- [ ] SSE `HEARTBEAT` **30초 간격**
- [ ] SSE `UNAUTHORIZED` emit 전 세션 재확인 + `SSE_DROPPED_ON_SESSION_EXPIRY` 감사 기록
- [ ] SSE emit 경로도 `MaskingRule` 적용본 소비 (응답 DTO 일관)
- [ ] Controller 응답 직전 `DefensiveMaskingFilter`가 DB 우회 데이터에도 2차 방어 적용
- [ ] `ExecutionLogListView`에 payload·errorMessage 원문 미포함
- [ ] `ExecutionLogResponse`의 XML payload 컬럼은 `@JsonIgnore`, 통합 게터로 분기
- [ ] Repository가 `@EntityGraph(attributePaths={"interfaceConfig"})`로 N+1 차단
- [ ] §5.1 `from`·`to`·`since` 동시 지정 시 400 `QUERY_PARAM_CONFLICT`
- [ ] 재처리 시 `actor_id` 매칭 검증: 타인 로그 시 403 `RETRY_FORBIDDEN_ACTOR`, `SYSTEM`/`ANONYMOUS_*` 예외 규칙
- [ ] 500 응답에 스택트레이스 누출 없음, `traceId`는 **UUID 고정** (DB 시퀀스 금지)
- [ ] advisory lock 네임스페이스 `ifms.advisory-lock.namespace` 주입형
- [ ] 재처리 실패 케이스 전체(DUPLICATE_RUNNING / RETRY_CHAIN_CONFLICT / RETRY_NOT_LEAF / RETRY_LIMIT_EXCEEDED / RETRY_TRUNCATED_BLOCKED / RETRY_FORBIDDEN_ACTOR / INTERFACE_INACTIVE) 단위 테스트
- [ ] 각 엔드포인트 "권한:" 표기는 **설계상 권한**이며 v0.1 구현은 단일 `OPERATOR` Role
- [ ] CSRF 토큰(`XSRF-TOKEN` / `X-XSRF-TOKEN`)이 모든 상태 변경 엔드포인트에 적용됨
- [ ] `clientId` UUID v4 정규식 검증 + 세션 바인딩 (다른 세션 `clientId` 스푸핑 차단)
- [ ] SSE 링버퍼 1,000건/5분 한계 도달 시 `RESYNC_REQUIRED` emit + 프런트 `?since=` 폴백 전환
- [ ] `mode=RECOVERY` rate limit이 IP 아닌 `actor_id` 기준으로 적용
- [ ] `mode=RECOVERY`가 자신의 `actor_id` 생성 로그만 반환 (Role=ADMIN 이전)
- [ ] `ANONYMOUS_` 해시에 `ifms.actor.anon-salt` 적용 확인
- [ ] `DefensiveMaskingFilter` 대량 응답(size=100 × payload 64KB) p95 측정 — 명시 목표 < 50ms
- [ ] Swagger UI에서 모든 엔드포인트 try-it-out 동작 확인

---

**문서 버전**

- v0.7 — 2026-04-20, ADR-005 (재처리 정책) 결정 반영
  - §1.3 에러 코드 표: `RETRY_CHAIN_CONFLICT` advisory lock(`parent_log_id`) 실패 추가, `RETRY_LIMIT_EXCEEDED` 기준을 `max_retry_snapshot`으로 갱신
  - §1.3 우선순위 표: 4·5·8·9·10번 항목에 ADR-005 결정 근거 주석 추가, advisory lock 획득 순서 명시
  - §5.3 재처리 엔드포인트: 제약을 `max_retry_snapshot`으로 변경, `RETRY_FORBIDDEN_ACTOR`를 체인 루트 actor 기준으로 명확화, 응답 본문에 `maxRetrySnapshot` + `rootLogId` 추가, `DUPLICATE_RUNNING`/`RETRY_CHAIN_CONFLICT` 발화 조건 보강
  - §9.1 advisory lock: lock key 2-도메인(`namespace`/`retry-namespace`) 분리 + 획득 순서(`interface_config_id` → `parent_log_id`) + 코드 예시 추가
- v0.1 — 2026-04-20, 초안 작성 (planning v0.3 / erd v0.4 기준)
- v0.6 — 2026-04-20, Day 2-B 완료 반영
  - §1.3 에러 코드 18종 (`NOT_IMPLEMENTED(501)` 추가) — stub 엔드포인트 전용
  - §4.5 `execute` 엔드포인트에 "Day 2 stub" 블록 + `interfaceId` 미에코 규약 명시. 정상 201 스펙은 Day 3 교체 예정으로 기록
- v0.5 — 2026-04-20, Day 2-A 설계 라운드 3 반영 (Security B 지적 3건 명문화)
  - §2.3 `SaltValidator` `@Profile("prod & !dev")` SpEL + `@PostConstruct` 기동 거부 규약 명시
  - §3.4 `DefensiveMaskingFilter` 구현 규약 6개 조목 신설 (적용 대상, ErrorDetail skip, Page.content[] 재귀, `maxDepth=10` + 노드 10,000 가드, X-Size-Clamped 헤더 주입 지점, p95 < 50ms 성능 목표)
- v0.4 — 2026-04-20, Day 2-A 설계 라운드 2 반영
  - §7.1 **예외 → ErrorCode 매핑 매트릭스** 신설 (GlobalExceptionHandler 정본). ConstraintViolationException 별도 행, ApiException sealed 계층, `DataIntegrityViolationException` SQLState+constraint 이중 판별, `OptimisticLockingFailureException`의 `ObjectProvider<Repository>` findById 재조회, Enum sanitize 규칙 포함
  - §7.1 `rejectedValue` 마스킹의 민감 키 블랙리스트를 `SensitiveKeyRegistry`(erd.md §3.3) 단일 출처 15종과 동기화
  - §7.6 `traceId` MDC 수명 주기 명시 (`MDC.put` 진입 즉시, `finally MDC.remove`) — 스레드 풀 오염 방지
- v0.3 — 2026-04-20, 2차 4-에이전트 재검토 반영 (전원 A 수렴 선언 속 경미 지적 9건 전량 반영)
  - §1.3: 재처리 우선순위 표에 `INTERFACE_NOT_FOUND` 삽입 (10단), 단일 출처 주석
  - §2.3: `ANONYMOUS_` 해시에 서버 salt(`ifms.actor.anon-salt`) 주입 — 레인보우 테이블 역산 방어
  - §3.3: `mode=RECOVERY` 쿼리를 자신의 `actor_id`로 자동 제약 (타 사용자 데이터 열람 차단), rate limit 주체 `actor_id`로 명시 (NAT 뒤 선의 사용자 보호)
  - §3.5: `CONCURRENT_EDIT_STORM` 카운터 저장소 명시 (in-memory, 운영 ADR 후보)
  - §6.1: `clientId` UUID v4 정규식 검증 + 세션 바인딩 (스푸핑 차단)
  - §6.1: 링버퍼 정량 기준 (1,000건/5분) 및 `RESYNC_REQUIRED` 이벤트·폴백 전환 규약 추가
  - §10: p95 측정·clientId 검증·링버퍼 한계·RECOVERY 제약·salt 체크리스트 6건 추가
- v0.2 — 2026-04-20, 1차 4-에이전트 검토 반영 (전원 B 조건부 → 18건 반영)
  - §1.3: 에러 코드 17종으로 확장 (`DUPLICATE_NAME`, `INTERFACE_INACTIVE`, `QUERY_PARAM_CONFLICT`, `RETRY_FORBIDDEN_ACTOR`, `TOO_MANY_CONNECTIONS` 추가)
  - §1.3: 재처리 에러 코드 평가 우선순위 표 추가 (서버·프런트 일관 분기)
  - §2.1: CSRF 보호를 `CookieCsrfTokenRepository`로 명시 (이전 문구 모순 해소)
  - §2.4: Role 표기가 "설계상"임을 감사 검토자용 주석 추가
  - §3.1: `size` 초과를 400 거부 → **클램프 + `X-Size-Clamped` 헤더**, `ApiResponse.data.content` 경로 고정 명시
  - §3.3: `since` 일반 호출 24h 상한 + `?mode=RECOVERY` 관리자 복구 경로(7일, 감사 기록, rate limit), 경계 부등호 명시
  - §3.4: **2단 마스킹** (1차 MaskingRule + 2차 `DefensiveMaskingFilter`), 우회 데이터 탐지 시 `POST_HOC_MASK_APPLIED` 감사
  - §3.5: version drift 재충돌 처리 + `CONCURRENT_EDIT_STORM` 5회 경보
  - §4.1: `name` LIKE 성능 경고 + pg_trgm 전환 가이드
  - §5.1: `from`/`to`/`since` 상호 배제 규칙 + `@EntityGraph` 명시
  - §5.3: 재처리 권한 검증 `RETRY_FORBIDDEN_ACTOR` + 예외(`SYSTEM`/`ANONYMOUS_*`) 규칙
  - §6.1: SSE 상한 완화 (세션 3 / 계정 10), HEARTBEAT 15s → 30s, UNAUTHORIZED emit 전 세션 재확인 + 감사 로그
  - §7.1: `rejectedValue` 민감값 `***REDACTED***` 규칙, `CONFIG_JSON_INVALID`에서 `rejectedValue` 제외
  - §7.6: `traceId` UUID 고정 규약 (DB 시퀀스 금지)
  - §9: "(부록) 구현 힌트"로 범위 명시
  - §9.1: advisory lock 주입형 (`ifms.advisory-lock.namespace`)
  - §10: 체크리스트 15 → 27건으로 보강
