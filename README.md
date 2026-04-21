# IFMS — 보험사 인터페이스 통합관리 프로토타입

> 노아에이티에스 2026년 공채 15기 입사 과제 제출물.
> 보험사 내부 시스템과 외부 기관(금감원·제휴사) 간 다수 인터페이스를 단일 화면에서 제어하는 중앙화된 통합관리 플랫폼 프로토타입.

---

## 1. 한눈에

- **언어/프레임워크**: Java 17 + Spring Boot 3.3.5 (백엔드) / Vue 3 + TypeScript 6 + Vuetify 3 + Vite 8 (프런트)
- **저장소**: PostgreSQL 16 (JSONB + advisory lock 활용)
- **실시간**: Server-Sent Events (Last-Event-ID 재동기화 + grace 재할당, ADR-007)
- **빌드 기간**: 2026-04-15 ~ 2026-04-21 (1주일, Day 1~7)
- **상태**: 백엔드 빌드 PASS / 프런트 빌드 PASS / 자동 테스트 30+ / ArchUnit 3 PASS

---

## 2. 빠른 실행

### 옵션 A: 호스트 개발 (기존, Day 5~7 워크플로)

PostgreSQL만 Docker로 띄우고 backend/frontend는 호스트에서 실행.

```bash
# 1) 최초 1회: .env 생성
cp .env.example .env

# 2) PostgreSQL 16 기동
docker-compose up -d

# 3) 백엔드 (8080)
cd backend
./gradlew bootRun

# 4) 프런트 (Vite dev 서버, 5173, 백엔드 프록시)
cd frontend
npm install
npm run dev
```

브라우저에서 <http://localhost:5173> 접속 → `operator@ifms.local` / `operator1234`로 로그인 (또는 `admin@ifms.local` / `admin1234`).

### 옵션 B: 전체 Docker 기동 (Day 8 추가)

한 줄로 postgres + backend + frontend 전부 컨테이너로.

```bash
# 최초 1회: .env 생성 (필수)
cp .env.example .env

# 전체 기동 (첫 빌드 ~5~8분)
docker compose --profile full up -d --build

# 상태 확인 (backend healthy까지 ~90s)
docker compose --profile full ps
```

브라우저에서 <http://localhost:8090> 접속 (80 아님 — Windows IIS 포트 충돌 회피).

기동 차이:
- 옵션 A: 소스 수정 → hot reload (개발용)
- 옵션 B: 이미지 기반, 운영 모드 데모용. 소스 수정 시 `--build` 재실행 필요.

정리: `docker compose --profile full down` (볼륨까지 초기화하려면 `down -v`).

### 환경 의존

- Docker Desktop 또는 Docker Engine
- JDK 17+ (옵션 A 선택 시)
- Node.js 20+ (옵션 A 선택 시)
- 포트 사용: 5432 (PostgreSQL), 8080 (백엔드), 5173 (프런트 dev) 또는 8090 (프런트 Docker), 2375 (Docker daemon, Testcontainers 옵션)

---

## 2-A. CRON 자동 실행 검증 (Day 8 신규)

`scheduleType=CRON` 인터페이스는 `InterfaceCronScheduler`(1분 폴링)가 `cronExpression`대로 자동 실행하고 `ExecutionLog.triggeredBy=SCHEDULER`로 기록한다.

### 등록 예시 (Swagger UI / curl)

```bash
curl -X POST http://localhost:8080/api/interfaces \
  -H "Content-Type: application/json" \
  --cookie "JSESSIONID=<로그인 후 세션>" \
  -H "X-XSRF-TOKEN: <CSRF 토큰>" \
  -d '{
    "name": "cron-demo-REST",
    "protocol": "REST",
    "endpoint": "http://mock/cron-demo",
    "httpMethod": "GET",
    "scheduleType": "CRON",
    "cronExpression": "0 * * * * *",
    "timeoutSeconds": 30,
    "maxRetryCount": 3,
    "configJson": {}
  }'
```

cronExpression은 **6-필드 Spring 형식**(초 분 시 일 월 요일). `0 * * * * *` = 매 분 0초.

### 확인

등록 후 최대 2분 대기 → ExecutionHistory(`/history`)에서 **triggeredBy=SCHEDULER** 필터.
또는:

```bash
curl http://localhost:8080/api/executions?triggeredBy=SCHEDULER
```

### 동작 원칙

- **최초 기동은 catch-up 안 함**: `last_scheduled_at` NULL → 첫 폴링 tick 시점을 기준점으로 기록만. 과거 누락 발화는 소급하지 않는다.
- **advisory lock + uk_log_running 보호막 재사용**: 스케줄러 시점과 사용자의 수동 트리거가 동시에 같은 인터페이스를 치면 한 쪽만 성공(409 DUPLICATE_RUNNING).
- **분 단위 이상 권장**: 스케줄러 폴링이 1분이므로 초 단위 cron은 1분에 1회만 발화한다.

### 스크린샷

- `docs/screenshots/day8-scheduler-history.png` — ExecutionHistory에서 SCHEDULER 필터 결과 (수동 검증 단계에서 촬영)
- `docs/screenshots/day8-docker-compose-ps.png` — `docker compose --profile full ps` healthy 출력 (수동 검증 단계에서 촬영)

---

## 3. 주요 화면

| 경로 | 화면 | 핵심 기능 |
|---|---|---|
| `/dashboard` | 대시보드 | 4 카드(전체·실행중·성공·실패) + 프로토콜별 표 + 최근 실패 + SSE 실시간. 실패 카드 클릭 → `/history?status=FAILED` 드릴다운 |
| `/interfaces` | 인터페이스 목록 | 필터 + 페이지네이션 + 등록/수정 다이얼로그 + 수동 실행 + 낙관적 락 충돌 처리 |
| `/history` | 실행 이력 | 상태·정렬 필터 + 페이지네이션 + 재처리 + SSE 진행 상태 in-place 갱신 + RESYNC 시 delta 폴백 |
| `/login` | 로그인 | 세션 + CSRF (Cookie + XSRF-TOKEN 헤더) |

---

## 4. 평가용 문서 인덱스

### 설계
- [기획서](docs/planning.md) — 도메인·범위·구현 우선순위
- [ERD](docs/erd.md) — 엔티티·인덱스·CHECK 제약 정본
- [API 명세](docs/api-spec.md) — 엔드포인트 11개 + ErrorCode 21종 + ApiResponse 규약 + DefensiveMaskingFilter
- Swagger UI: <http://localhost:8080/swagger-ui.html> (백엔드 기동 후)

### Architecture Decision Records (7건)
- [ADR-001 ExecutionLog 트랜잭션 범위](docs/adr/ADR-001-execution-log-transaction.md)
- [ADR-004 동시 실행 중복 방지 (advisory lock + uk_log_running)](docs/adr/ADR-004-concurrent-execution-prevention.md)
- [ADR-005 재처리 정책 (max_retry_snapshot, root actor)](docs/adr/ADR-005-retry-policy.md)
- [ADR-006 ConfigJsonValidator 호출 지점](docs/adr/ADR-006-config-json-validator-call-site.md)
- [ADR-007 SSE 재동기화 + 세션 경계 프로토콜](docs/adr/ADR-007-sse-resync-session-boundary.md)
- (ADR-002·003은 본문 편입 — `planning §5.4` / `erd §10`)

### 일별 진행
- [Day 2](docs/DAY2-SUMMARY.md) — 도메인 모델 + JPA + 인터페이스 CRUD
- [Day 3](docs/DAY3-SUMMARY.md) — 실행 트리거 + Mock 실행기 + RetryService + Watchdog
- [Day 4](docs/DAY4-SUMMARY.md) — Security + SSE 기반 + 대시보드 집계 + ConnectionLimit
- [Day 5](docs/DAY5-SUMMARY.md) — 프런트 scaffold + InterfaceList/Form + 낙관적 락
- [Day 6](docs/DAY6-SUMMARY.md) — Dashboard + ExecutionHistory + delta API + ADR-007
- [Day 7](docs/DAY7-SUMMARY.md) — 부채 청산 + 통합·단위·벤치 테스트 + 문서 정합 + 핸드오프

### 백로그·운영 이관
- [backlog.md](docs/backlog.md) — 진행 상태·운영 이관 사유 명문화

---

## 5. 테스트

```bash
cd backend
./gradlew test          # 30+ 케이스 (단위 + ArchUnit 3)
./gradlew test -i       # 상세 로그
RUN_BENCH=1 ./gradlew test --tests "MaskingRuleBenchTest"   # 마스킹 p95 벤치
```

```bash
cd frontend
npm run build           # vue-tsc 타입체크 + vite 빌드
```

테스트 분포 (Day 7 기준):
- **ArchUnit 3** — Repository 주입 범위 / EntityManager.merge 금지 / @Modifying 한정
- **Day 6 17** — Delta 4 + Cursor 4 + Rate limiter 3 + SSE 4 + Unauthorized 1 + 기타 1
- **Day 7 12** — RetryGuard 8 + SnapshotParity 1 + ApiResponse 직렬화 2 + MaskingRule 벤치 1
- **Day 7 race** — `SseSubscribeRaceTest` @RepeatedTest(20)

---

## 6. 알려진 한계

본 프로토타입은 1주일 일정으로 의도적으로 다음 항목을 운영 이관:

- **Testcontainers 통합 테스트 5종 보류**: Docker Desktop 29 + Testcontainers 1.20.5 호환 이슈 (`DAY7-SUMMARY §6` 참조). 코드는 보존, 환경 회복 시 즉시 활성. RetryGuard 단위 8 케이스가 ADR-005 핵심 분기 커버.
- **JSONB GIN 인덱스**: erd 설계만, schema.sql 미선언. 데이터량 임계 전까지 컨테인먼트 동작은 보장됨
- **분산 SSE / Redis Pub/Sub**: 단일 인스턴스 전제
- **Role 분리 (ADMIN/OPERATOR/AUDITOR)**: 단일 OPERATOR만 구현
- **HMAC cursor 서명, rate limit 분산화, OFFSET→Keyset 전환**: 운영 환경 전환 시 일괄

상세는 [`backlog.md` "운영 전환" 섹션](docs/backlog.md) 참조.

---

## 7. 라이선스

본 프로토타입은 노아에이티에스 입사 과제 제출용으로 작성되었습니다.
