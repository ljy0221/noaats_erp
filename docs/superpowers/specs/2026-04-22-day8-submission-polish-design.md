# Day 8 제출 완성도 보강 — Docker 통합 + CRON 스케줄러

**파일 유형**: spec (brainstorming 결과)
**날짜**: 2026-04-21
**범위**: 제출 전 추가 작업 5개 항목 중 **회의 결정된 우선순위 1·2번만** (옵션 B 선택)

---

## 1. Context

Day 7까지 MVP + 코드 부채 청산 + 수동/자동 E2E 검증이 모두 초록불로 끝난 상태에서, **제출 완성도**를 보강하기 위한 범위 축소된 보강 작업이다.

사용자가 제안한 추가 5개 항목(JWT / CRON 스케줄러 / 차트 / 성능 테스트 / Docker+README)을 4-에이전트 회의(@Architect, @Security, @DBA, @DevilsAdvocate)로 심사한 결과:

- **JWT**: 세 에이전트 전원 반대(보안 후퇴·DB 경계 재설계·일정 불가) → **이번 spec 제외**, ADR-008로 "세션 인증 유지 근거" 문서만 작성(후속)
- **차트**: Devils 지적대로 Chart.js·Vuetify 3 지뢰밭 위험 → **후속으로 이월** (sparkline 대안)
- **성능 테스트**: 로컬 1대 머신 부하 숫자는 무의미 → **후속으로 이월** (JMH 기존 벤치 확장 방식)
- **CRON 스케줄러**: planning §3 "필수 구현"에 명시된 `scheduleType=CRON`이 도메인·검증·UI까지 존재하나 **실 트리거가 없음** → **본 spec 대상**
- **Docker 통합**: compose는 `.env` 분리·volume 영속화·healthcheck까지 완비되어 있으나 **backend/frontend Dockerfile 부재**로 `docker compose up` 한 줄 기동이 안 됨 → **본 spec 대상**

이 spec이 지향하는 최종 상태:
1. 채점자가 `cp .env.example .env && docker compose --profile full up -d` **한 줄만 쳐도** PostgreSQL + backend(8080) + frontend(80/5173) 전부 뜬다.
2. UI에서 `scheduleType=CRON, cronExpression=0 */1 * * * *`로 인터페이스를 등록하면 **실제로 매 분 자동 실행**되어 `ExecutionLog.triggeredBy=SCHEDULER`로 기록된다. 기존 advisory lock·`uk_log_running` 보호막은 그대로 작동.
3. README에 "CRON 자동 실행 데모" 절 + 스크린샷/GIF 1~2종.

이 범위 선택의 근거는 회의록(본 대화 상단)에 정리되어 있으며, **회귀 리스크가 가장 낮은 두 항목**이다.

---

## 2. 현재 상태 (fact-finding)

### 2.1 Docker
- `docker-compose.yml`: **PostgreSQL 16 + Adminer(프로파일 dev-tools)** 완비. `.env` 필수(`.env.example` 존재), healthcheck, `Asia/Seoul` TZ, 127.0.0.1 바인딩
- `.env.example` / `.env`: 존재
- **backend/frontend Dockerfile**: 없음 (→ **신설 대상**)
- `README.md`: 기본 섹션 존재. 빠른 실행은 `docker-compose up -d` + 호스트 gradle/npm 병행 방식

### 2.2 CRON 스케줄러
- `InterfaceConfig.scheduleType`: `ScheduleType.MANUAL | CRON` enum 존재
- `InterfaceConfig.cronExpression`: 컬럼 존재
- `InterfaceConfigRequest.isCronExpressionRequired()`: `@AssertTrue` 교차 검증 구현됨
- `@EnableScheduling`: `IfmsApplication.java:20`에 활성
- `@Scheduled` 사용처: **OrphanRunningWatchdog(5분 fixedDelay) + SseEmitterService heartbeat 2건뿐**. InterfaceConfig CRON 자동 트리거 **미구현**
- `AdvisoryLockProperties`: namespace(`IFMS`=0x49464D53) + retry-namespace(`IFMT`=0x49464D54) 2-도메인 분리 완료
- `ExecutionLog.triggeredBy`: `TriggerType.MANUAL | SCHEDULER` enum 존재 (SCHEDULER는 현재 미사용 dead code)

### 2.3 기존 트리거 경로 (재사용 대상)
- `ExecutionTriggerService.trigger(configId, actor)`: advisory lock + `uk_log_running` + @Async 실행 체인 **완성**. 본 spec의 스케줄러는 이 서비스를 **호출만** 하면 됨(재구현 금지)

---

## 3. 목표와 비목표

### 3.1 목표 (이 spec이 달성)
1. `docker compose --profile full up -d` 한 줄로 postgres + backend + frontend 전부 기동
2. `scheduleType=CRON` 인터페이스가 `cronExpression`대로 자동 실행되고 `ExecutionLog.triggeredBy=SCHEDULER`로 기록
3. 스케줄러가 기존 advisory lock / `uk_log_running` / `@Async` / OrphanRunningWatchdog와 충돌 없음
4. README에 "CRON 자동 실행 검증" 1절 추가 + 스크린샷 1~2종
5. `.env.example`에 평문 시크릿 0건(기존 상태 유지 확인)

### 3.2 비목표 (명시적 제외)
- JWT 전환 — ADR-008 후속 (설계 문서만)
- 대시보드 차트/sparkline — 후속
- 성능 테스트 리포트 — 후속
- ShedLock / 분산 스케줄러 — `@Scheduled(pool-size=1)` + advisory lock 조합으로 프로토타입 충분 (DBA 판단)
- Flyway 도입 — backlog.md "운영 전환" 범위 밖
- 초 단위 cron — DBA/Devils 양측 지적. **최소 분단위(`0 * * * * *`) + UI 힌트**

---

## 4. 아키텍처

### 4.1 CRON 스케줄러 (신규 컴포넌트 1개)

```
backend/src/main/java/com/noaats/ifms/domain/execution/service/
  InterfaceCronScheduler.java    (신규)
```

**책임**: 1분마다 폴링 → `scheduleType=CRON, status=ACTIVE`인 InterfaceConfig를 조회 → 각 인터페이스의 `cronExpression`이 "직전 실행 시각 ~ 지금" 구간에 발화 시점이 있는지 판단 → 있으면 `ExecutionTriggerService.trigger(..., triggeredBy=SCHEDULER)` 호출.

**설계 선택**:

| 축 | 선택 | 대안 대비 근거 |
|---|---|---|
| 주기 | `@Scheduled(fixedDelay=PT1M)` 단일 지점 폴링 | 각 InterfaceConfig마다 동적 `@Scheduled` 등록(TaskScheduler API)은 복잡도↑, 프로토타입 과잉 |
| cron 매치 | `org.springframework.scheduling.support.CronExpression.next(lastTriggerAt)` | Spring 내장. 외부 의존성 0 |
| `lastTriggerAt` 저장 | **InterfaceConfig에 `last_scheduled_at TIMESTAMPTZ NULL` 컬럼 추가** | in-memory Map은 재기동 시 "한 번 더" 트리거 → 감사 이중기록 리스크 |
| 동시성 | `ExecutionTriggerService`가 이미 advisory lock + `uk_log_running` 보유 → **스케줄러는 가드 없이 호출만** | 이중 가드는 DBA 지적대로 advisory lock 경합만 유발 |
| 중복 트리거 방지 | **advisory lock namespace 분리 불필요** — scheduler와 manual trigger가 같은 인터페이스를 동시 치면 `uk_log_running`이 정확히 1건만 통과시킴 (의도된 동작) | 별도 namespace는 과잉 설계 |
| 재기동 시 catch-up | **catch-up 하지 않음**. 기동 시점 이후 발화만 처리 | planning.md 프로토타입 범위. backfill은 운영 전환 범위 |
| 타임존 | `@Scheduled(zone="Asia/Seoul")` + `CronExpression` 평가 시 `ZoneId.of("Asia/Seoul")` | application.yml에 이미 `hibernate.jdbc.time_zone: Asia/Seoul`. cron 표현식도 KST 기준 |
| Actor | `SystemActor` (기존 `ActorContext.SYSTEM` 재사용) | `ExecutionLog.triggeredBy=SCHEDULER`일 때 actor는 SYSTEM으로 통일(ADR-005 §5 선례) |

### 4.2 Docker 이미지화 (신규 Dockerfile 2개 + compose 확장)

```
docker/
  backend.Dockerfile       (신규)
  frontend.Dockerfile      (신규)
  nginx.conf               (신규 — frontend 정적 서빙 + /api 백엔드 프록시)
docker-compose.yml         (수정 — backend/frontend service + profiles: full 추가)
```

**설계 선택**:

| 축 | 선택 | 근거 |
|---|---|---|
| backend 빌드 | **multi-stage Gradle build + `eclipse-temurin:17-jre-alpine` 런타임** | JIB은 gradle 플러그인 추가 부담. multi-stage는 기존 `./gradlew bootJar`만 호출하면 됨 |
| frontend 빌드 | **multi-stage Node 20 build + `nginx:alpine` 런타임** | npm run build로 생성되는 `dist/`를 nginx에 올림. dev 서버(Vite)는 제외 — 운영 모드 데모 |
| nginx 프록시 | `/api`·`/actuator`·`/swagger-ui`·`/v3/api-docs` → `http://backend:8080` | Vite dev proxy와 동일한 경로. 프런트 코드 변경 0 |
| CSRF 경계 | 세션 쿠키 Same-Origin 유지 — nginx가 같은 origin에서 서빙하므로 별도 CORS 설정 불필요 | 현 세션+CSRF 설계 그대로 동작 |
| compose profile | `profiles: ["full"]` 부여. **기본 기동(`docker compose up -d`)은 PostgreSQL만** (개발자 호환) | 기존 Day 7 개발 워크플로 보존 |
| 시드 데이터 | **이 spec 범위 밖**. `docker/postgres/init/*.sql` 기존 경로 그대로 | planning/backlog에 시드 언급 없음 |
| 이미지 태깅 | `ifms-backend:local`, `ifms-frontend:local` 고정 | 제출용 로컬 빌드 |

### 4.3 README 보강
- **§2 "빠른 실행"에 "옵션 B: 전체 Docker 기동" 추가** (기존 "옵션 A: 호스트 개발" 보존)
- **§? "CRON 자동 실행 검증"** 1절 신규 — 등록 JSON 예시 + 확인 방법(`/api/executions?triggeredBy=SCHEDULER`)
- 스크린샷 2종: (1) Dashboard에 SCHEDULER 실행 카운트, (2) ExecutionHistory 필터 스크린샷

---

## 5. 데이터 변경

### 5.1 스키마 (InterfaceConfig 컬럼 1개 추가)

```sql
ALTER TABLE interface_config
  ADD COLUMN last_scheduled_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN interface_config.last_scheduled_at IS
  'CRON 스케줄러 직전 발화 시각. InterfaceCronScheduler가 cronExpression.next(lastScheduledAt) 평가에 사용. MANUAL은 항상 NULL.';
```

- `ddl-auto=validate`(운영)/`update`(local)이므로 `schema.sql`에 컬럼 추가 (운영) + 엔티티에 필드 추가 (local)
- 기존 데이터 마이그레이션 불필요 (NULL = 최초 폴링 시 `now()` 기준 다음 발화 대기)

### 5.2 엔티티 변경 (기존 파일 수정)
- `InterfaceConfig.java`: `last_scheduled_at` 필드 + getter + `markScheduled(LocalDateTime now)` 도메인 메서드
- 인덱스 추가 불필요 — 폴링 주기가 1분이고 ACTIVE+CRON 인터페이스 수가 수십~수백 건 예상, WHERE 절 단순

### 5.3 ExecutionLog.triggeredBy = SCHEDULER 활성화
- enum 값은 이미 존재. **SCHEDULER가 실제로 기록되기 시작하는 것이 이 spec의 행동 변화**
- 기존 쿼리·필터·DTO·프런트 모두 이미 대응되어 있음(확인 완료)

---

## 6. 구현 단위 (plan이 쪼갤 대상)

### 6.1 Backend — CRON 스케줄러
1. `InterfaceConfig`에 `last_scheduled_at` 필드 + `markScheduled()` 도메인 메서드
2. `schema.sql`에 `ALTER TABLE` 반영 (운영 경로)
3. `InterfaceConfigRepository.findAllActiveCron()` 쿼리 메서드 추가 (`status=ACTIVE AND schedule_type=CRON`)
4. `InterfaceCronScheduler` 신규 작성 (@Component, @Scheduled(fixedDelay=PT1M, zone=Asia/Seoul))
5. 단위 테스트: cron 표현식 → 다음 발화 판정 로직 (`CronExpression.next()` 호출 결과 검증, 3~5 케이스)
6. 통합 테스트(H2 또는 기존 Testcontainers 보존 경로): SCHEDULER로 ExecutionLog 1건 기록 확인

### 6.2 Backend — Dockerfile
7. `docker/backend.Dockerfile` (multi-stage: gradle 8 + eclipse-temurin 17)
8. `docker-compose.yml`에 `backend` 서비스 + `depends_on: postgres (service_healthy)` + `profiles: ["full"]`
9. backend 헬스체크(`/actuator/health` 또는 port open)

### 6.3 Frontend — Dockerfile + nginx
10. `docker/frontend.Dockerfile` (multi-stage: node 20 build + nginx:alpine)
11. `docker/nginx.conf` (정적 루트 + `/api`·`/actuator`·`/swagger-ui`·`/v3/api-docs` 프록시)
12. `docker-compose.yml`에 `frontend` 서비스 + `depends_on: backend` + `profiles: ["full"]` + 80 포트

### 6.4 문서
13. `README.md` §2에 "옵션 B: 전체 Docker 기동" 서브섹션
14. `README.md`에 "CRON 자동 실행 검증" 1절 (JSON 예시 + curl 확인 방법)
15. 스크린샷 2종 (스케줄러 SCHEDULER 실행 건 확인용 — 수동 검증 단계에서 촬영)

---

## 7. 재사용 대상 기존 자산

| 자산 | 위치 | 용도 |
|---|---|---|
| `ExecutionTriggerService.trigger(...)` | `domain/execution/service/` | 스케줄러가 호출할 단일 진입점 |
| `AdvisoryLockProperties` | `global/config/` | 이미 namespace 분리됨 — 추가 없음 |
| `ActorContext.SYSTEM` / `SystemActor` | (확인 필요) | SCHEDULER 트리거 시 actor |
| `ExecutionLogRepository.tryAdvisoryLock(key1,key2)` | `domain/execution/repository/` | 이미 트리거 체인에 포함 |
| `CronExpression` (Spring Core) | `org.springframework.scheduling.support` | 표현식 파싱·다음 발화 계산. 외부 의존성 0 |
| `@Scheduled` 패턴 | `OrphanRunningWatchdog`·`SseEmitterService` | `fixedDelayString`/`zone` 사용 선례 |

---

## 8. 검증 (plan 후반부 실행)

### 8.1 자동
- `InterfaceCronSchedulerTest` (단위, 3~5 케이스): cron 만료 판정 + `markScheduled` 호출 + SystemActor 넘김
- `InterfaceCronSchedulerIntegrationTest` (선택 — H2로도 가능하면 포함): `scheduleType=CRON` 인터페이스 1건 + 1분 대기 + ExecutionLog 1건(SCHEDULER) 확인
- `./gradlew test`: 기존 PASS 유지 (회귀 0)
- `./gradlew bootJar`: Docker 빌드에 사용. PASS 확인
- `npm run build` (frontend): 기존 PASS 유지
- `docker compose --profile full build`: backend + frontend 이미지 빌드 성공
- `docker compose --profile full up -d`: 3개 컨테이너 healthy

### 8.2 수동 E2E (제출 전 필수)
- [ ] Docker 한 줄 기동 시나리오: 깨끗한 `.env`로 시작 → `docker compose --profile full up -d` → `http://localhost/` 로그인 → InterfaceList 조회
- [ ] CRON 실 동작 시나리오: UI에서 `scheduleType=CRON, cronExpression=0 */1 * * * *` 인터페이스 등록 → 2분 대기 → ExecutionHistory에서 `triggeredBy=SCHEDULER` 2건 확인 → 기존 수동 트리거도 정상 동작 (회귀 없음)
- [ ] 동시 실행 방어 시나리오: CRON 트리거 직후 같은 인터페이스 수동 트리거 시도 → `uk_log_running` 충돌 → 409 응답

### 8.3 Security 체크 3줄 (회의 확정 항목)
1. `git grep -n "ifms1234\|password:"` 결과가 `.env.example`·README 예시·주석 외 0건
2. 차트/대시보드 API 응답 JSON에 `requestPayload/responsePayload` 0건 (SCHEDULER 실행도 마찬가지 — curl 실측)
3. `triggeredBy=SCHEDULER` 건이 advisory lock 하에 중복 없이 기록 (2분 관찰 후 중복 0 확인)

---

## 9. 수용 리스크

| 리스크 | 수용 근거 | 완화 |
|---|---|---|
| 스케줄러가 `@Scheduled(fixedDelay=1M)`이므로 최소 분단위 cron만 의미 있음 (초 단위는 1분 동안 여러 번 발화해도 1번만 트리거됨) | 프로토타입 데모 충분. 초 단위는 운영급 요구사항 | UI cronExpression 힌트에 "분 단위 이상 권장" 1줄 추가 (선택, 최소 구현 시 생략 가능) |
| Docker frontend(nginx)가 세션 쿠키 same-origin을 유지해야 함 | nginx가 동일 origin에서 서빙하면 별도 CORS 설정 불필요 | nginx.conf에서 `/api` proxy_pass 시 `proxy_cookie_path` 수정 없이 그대로 전달 확인 |
| backend 이미지가 `application-local.yml`을 읽을 수 없음 | compose에서 `SPRING_PROFILES_ACTIVE=docker`로 override + 환경변수만 필요한 값 주입 | `application-docker.yml` 신설(선택) 또는 기본 `application.yml` + env 변수로 충분 |
| 재기동 시 `last_scheduled_at`이 `NULL`→첫 폴링에서 `now()` 기준 다음 발화 대기 | "재기동 시 과거 발화 catch-up 불필요"가 명시적 설계 선택 | README에 명시 |
| 채점자가 `docker compose up`을 실제로 실행하지 않을 수 있음 (Devils 지적) | README에 **구동 스크린샷/로그 발췌**를 함께 넣어 "실행 안 해도 동작 증명" | §4.3 문서 보강에 포함 |

---

## 10. 후속 (이 spec 밖 · backlog 이관)

- ADR-008 "세션 인증 유지 근거 + JWT 미채택 판단" 문서화
- 대시보드 sparkline (회의 대안)
- JMH 벤치 확장 — ExecutionLog 페이지네이션 + advisory lock 경합
- 스케줄러 catch-up(재기동 시 누락 발화) — 운영 전환 범위
- 초 단위 cron 지원 여부 — 폴링 주기 축소 결정 필요

---

## 11. 예상 소요

| 단계 | 예상 |
|---|---|
| Backend CRON 스케줄러 (6.1) | 3~4시간 |
| Dockerfile backend (6.2) | 1~2시간 |
| Dockerfile frontend + nginx (6.3) | 2~3시간 |
| README 보강 + 스크린샷 (6.4) | 1~2시간 |
| 수동 E2E + 회귀 | 1~2시간 |
| **합계** | **0.8~1.3일** |

회의 추정(0.5+0.5=1일)과 일치 범위.
