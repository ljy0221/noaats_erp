# CLAUDE.md — 보험사 금융 IT 인터페이스 통합관리시스템

## 프로젝트 개요

보험사 내부 핵심 시스템과 외부 기관(금감원, 제휴사 등) 간 다수의 인터페이스를
단일 화면에서 제어하는 중앙화된 통합관리 플랫폼 프로토타입.

- **목적**: 노아에이티에스 2026년 공채 15기 입사 과제 제출
- **기간**: 1주일 (개인 프로젝트)
- **제출물**: 기획서 + 개발문서 + 동작 가능한 웹앱 프로토타입

---

## 기술 스택

### Backend (`/backend`)
- Java 17 (LTS, Spring Boot 3.3.5 최소 요구 + 현 개발 환경 기본)
- Spring Boot 3.x
- Spring Data JPA + Hibernate
- Spring Security (간단한 인증)
- PostgreSQL (운영) / H2 (테스트)
- SSE (Server-Sent Events) — 실시간 실행 상태 스트리밍
- Gradle
- Lombok, MapStruct

### Frontend (`/frontend`)
- Vue 3 (Composition API)
- Vite
- Pinia (상태관리)
- Vue Router
- Axios
- Vuetify 3 (UI 컴포넌트)

### 공통
- Docker / Docker Compose (로컬 PostgreSQL)
- Swagger / SpringDoc OpenAPI

---

## 모노레포 구조

```
root/
├── CLAUDE.md
├── docker-compose.yml          # PostgreSQL 로컬 실행
├── docs/                       # 기획서, ERD, API 명세
│   ├── planning.md
│   ├── erd.md
│   └── api-spec.md
├── backend/
│   ├── build.gradle
│   ├── src/main/java/com/noaats/ifms/
│   │   ├── domain/
│   │   │   ├── interface_/     # 인터페이스 등록/조회 (예약어 회피)
│   │   │   ├── execution/      # 실행 이력, 트리거
│   │   │   └── monitor/        # SSE, 대시보드 집계
│   │   ├── global/
│   │   │   ├── config/
│   │   │   ├── exception/
│   │   │   └── response/       # 공통 ApiResponse<T>
│   │   └── IfmsApplication.java
│   └── src/test/
└── frontend/
    ├── package.json
    ├── vite.config.js
    └── src/
        ├── api/                # Axios 인스턴스 + API 모듈
        ├── components/
        ├── pages/
        │   ├── Dashboard.vue   # 실시간 모니터링
        │   ├── InterfaceList.vue
        │   ├── InterfaceForm.vue
        │   └── ExecutionHistory.vue
        ├── stores/             # Pinia
        └── router/
```

---

## 핵심 도메인 모델

### InterfaceConfig (인터페이스 정의)
```
id, name, description,
protocol (REST | SOAP | MQ | BATCH | SFTP),
endpoint, httpMethod,
scheduleType (MANUAL | CRON),
cronExpression,
timeoutSeconds,
status (ACTIVE | INACTIVE),
createdAt, updatedAt
```

### ExecutionLog (실행 이력)
```
id, interfaceConfigId,
triggeredBy (MANUAL | SCHEDULER),
status (RUNNING | SUCCESS | FAILED),
startedAt, finishedAt,
durationMs, requestPayload, responsePayload,
errorMessage, retryCount
```

---

## 구현 범위 (MVP)

### 필수 구현
- [ ] 인터페이스 목록 조회 / 등록 / 수정 / 비활성화
- [ ] 수동 실행 트리거 (Mock HTTP 호출 시뮬레이션)
- [ ] 실행 이력 조회 (페이지네이션, 상태 필터)
- [ ] 실패 건 재처리
- [ ] SSE 기반 실시간 실행 상태 스트리밍
- [ ] 대시보드 (프로토콜별 현황, 최근 실패 목록)

### 구현 제외 (문서에 설계만 포함)
- 실제 SOAP/MQ/SFTP 연동 → Mock으로 대체
- 사용자 권한 관리 (Role 분리)
- 알림 발송 (Slack/Email)

---

## 코드 컨벤션

### Backend
- 패키지: `com.noaats.ifms`
- 도메인별 패키지 분리: `controller / service / repository / domain / dto`
- 공통 응답: `ApiResponse<T> { success, data, message, timestamp }`
- 예외: `GlobalExceptionHandler` + 커스텀 Exception 클래스
- `interface`는 Java 예약어이므로 패키지명 `interface_` 또는 `iface` 사용
- 트랜잭션: Service 레이어에서만 `@Transactional` 선언
- JPA: 양방향 연관관계 최소화, fetch는 기본 LAZY

### Frontend
- Composition API + `<script setup>` 문법 사용
- API 호출은 `/src/api/` 모듈에서만
- 컴포넌트명: PascalCase
- 페이지 컴포넌트는 `/src/pages/`에만 위치

---

## 실행 방법

```bash
# PostgreSQL 실행
docker-compose up -d

# Backend
cd backend
./gradlew bootRun

# Frontend
cd frontend
npm install
npm run dev
```

### 환경변수 (`backend/src/main/resources/application.yml`)
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ifms
    username: ifms
    password: ifms1234
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
server:
  port: 8080
```

---

## API 설계 원칙

- RESTful: `GET /api/interfaces`, `POST /api/interfaces`, `PATCH /api/interfaces/{id}`
- 실행 트리거: `POST /api/interfaces/{id}/execute`
- 재처리: `POST /api/executions/{logId}/retry`
- SSE: `GET /api/monitor/stream` (Content-Type: text/event-stream)
- 모든 응답은 `ApiResponse<T>` 래핑
- HTTP 상태코드 명확히 사용 (200/201/400/404/500)

---

## 제약 및 주의사항

### 절대 하지 말 것
- `@Transactional` 을 Controller에 선언하지 말 것
- Mock 실행 시 실제 외부 네트워크 호출 금지 (타임아웃 리스크)
- Vue에서 API URL 하드코딩 금지 → Vite proxy 또는 env 변수 사용
- 단일 God Service 클래스 금지 → 도메인별 Service 분리

### 반드시 할 것
- 모든 실행은 `ExecutionLog`에 기록 (감사 추적)
- SSE 연결 해제 시 리소스 정리 (`SseEmitter` onCompletion/onTimeout 처리)
- 페이지네이션은 `Pageable` 사용 (오프셋 기반)
- Swagger UI 동작 확인 후 제출 (`/swagger-ui.html`)

---

## Mock 실행 시뮬레이션 전략

실제 외부 연동 대신 프로토콜별 Mock 실행기 구현:

```
REST   → Thread.sleep(200~800ms) + 랜덤 성공/실패
SOAP   → 고정 XML 응답 반환
MQ     → 메시지 발행 성공 시뮬레이션
BATCH  → 처리 건수 카운트 시뮬레이션
SFTP   → 파일 전송 완료 시뮬레이션
```

실패율은 설정 가능하게 (`failureRate: 0.2` 등).

---

## 개발 우선순위 (Day별)

| Day | 작업 |
|-----|------|
| 1 | 기획서 + ERD + API 명세 작성, 프로젝트 초기화 |
| 2 | Backend: 도메인 모델, JPA, 인터페이스 CRUD API |
| 3 | Backend: 실행 트리거, Mock 실행기, 이력 저장 |
| 4 | Backend: 재처리, SSE, 대시보드 집계 API |
| 5 | Frontend: 인터페이스 목록/등록 화면 |
| 6 | Frontend: 실행 이력, 재처리, 대시보드 + SSE 연동 |
| 7 | 통합 테스트, 버그 수정, 개발문서 정리, 제출 |

---

## Multi-Agent 기술 회의

기술 결정이 필요한 시점에 4명의 에이전트가 회의를 통해 결정한다.
구현 세부 사항은 스킬 파일로 해결하고, 회의는 아래 상황에서만 소집한다.

### 에이전트 구성

| 에이전트 | 파일 | 역할 |
|---|---|---|
| @Architect | `.claude/agents/architect.md` | 사회자, 설계 결정, ADR 작성 |
| @Security | `.claude/agents/security.md` | 금융 보안, 감사 로그, 취약점 |
| @DBA | `.claude/agents/dba.md` | ERD, 인덱스, 쿼리 성능 |
| @DevilsAdvocate | `.claude/agents/devils-advocate.md` | 반론, 엣지 케이스 |

### 회의 소집 명령어

```
/meeting [안건 제목]

컨텍스트:
- 배경 및 결정해야 할 내용

참석: @Architect @Security @DBA @DevilsAdvocate
```

### 예상 회의 일정

| ADR | 안건 | 시점 |
|---|---|---|
| ADR-001 ✅ | ExecutionLog 트랜잭션 범위 | Day 2 완료 |
| ADR-002 | SSE 브로드캐스트 전략 | **본문 편입** (planning §5.4) |
| ADR-003 | payload 저장 방식 (TEXT vs JSONB) | **본문 편입** (erd §10, JSONB + `@JdbcTypeCode`) |
| ADR-004 ✅ | 동시 실행 중복 방지 전략 | Day 2 완료 (advisory lock + `uk_log_running`) |
| ADR-005 | 재처리 최대 횟수·체인 분기 정책 | Day 3 예정 |
| ADR-006 ✅ | ConfigJsonValidator 호출 지점 | Day 2 완료 (Service 호출 + ArchUnit) |

> 상세 프로토콜: `.claude/agents/MEETING-PROTOCOL.md`
> ADR 저장 위치: `docs/adr/`
> 스킬 파일: `.claude/skills/`

---

## Claude Code 작업 요청 템플릿

작업 요청 시 아래 형식으로 요청하면 맥락 유지가 쉽습니다:

```
[작업 유형] 구현 | 수정 | 리뷰 | 설계
[대상] 파일명 또는 기능명
[요청] 구체적인 내용
[제약] 있으면 명시
```

예시:
```
[구현] ExecutionService
[요청] 수동 실행 트리거 메서드 구현. Mock 실행기 호출 후 ExecutionLog 저장.
[제약] 비동기(@Async) 처리, 실행 중 SSE로 상태 emit
```
