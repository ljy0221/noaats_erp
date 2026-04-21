# Day 8 완료 요약 — 2026-04-21

> 제출 완성도 보강. 4-에이전트 회의 결정(옵션 B): CRON 스케줄러 실 구현 + Docker 전체 기동만.
> JWT·차트·성능 테스트는 후속 이월 (회의 근거는 spec §1).

---

## 1. 산출물

| 파일 | 상태 |
|---|---|
| [superpowers/specs/2026-04-22-day8-submission-polish-design.md](superpowers/specs/2026-04-22-day8-submission-polish-design.md) | spec |
| [superpowers/plans/2026-04-22-day8-submission-polish.md](superpowers/plans/2026-04-22-day8-submission-polish.md) | plan (9 task) |
| `backend/.../InterfaceCronScheduler.java` + 단위 테스트 5 | 신규 |
| `backend/.../InterfaceConfig.java` `last_scheduled_at` 필드 + `markScheduled()` | 수정 |
| `backend/src/main/resources/schema.sql` ALTER | 수정 |
| `docker/backend.Dockerfile` · `docker/frontend.Dockerfile` · `docker/nginx.conf` · `docker/backend-entrypoint.sh` | 신규 |
| `docker-compose.yml` profile=full + `.env.example` RETRY_NAMESPACE | 수정 |
| `.gitattributes` + `.dockerignore` | 신규 (부수 품질 보강) |
| `README.md` §2 옵션 A/B + §2-A CRON 검증 | 수정 |
| `docs/screenshots/day8-*.png` + `day8-docker-compose-ps.txt` | 신규 (Playwright 실측) |

---

## 2. 커밋 이력 (13개)

| 순서 | 커밋 | 설명 |
|---|---|---|
| 1 | `b26ca5a` | chore(gitignore): .worktrees/ 추가 |
| 2 | `a9b0e60` | feat(interface): last_scheduled_at 필드 + markScheduled() |
| 3 | `ca9601e` | feat(schema): interface_config ALTER + COMMENT |
| 4 | `c289b94` | feat(execution): InterfaceCronScheduler 초판 + 테스트 5 |
| 5 | `501b201` | fix: 코드 리뷰 Important 2건 (catch Exception + ActorContext DI) |
| 6 | `f6d7bd3` | build(docker): backend.Dockerfile + entrypoint + .gitattributes |
| 7 | `e90c7c3` | fix(docker): CRLF 근본 해결 (gradlew 재정규화 + sed 제거) |
| 8 | `d93412c` | build(docker): frontend.Dockerfile + nginx.conf |
| 9 | `3298c18` | fix: Task 5 리뷰 2건 (X-Forwarded-Host + .dockerignore) |
| 10 | `60cf1dc` | build(compose): profile=full backend/frontend |
| 11 | `1c0062e` | fix(compose): start_period 60s → 90s |
| 12 | `88f1985` | docs(readme): 옵션 B + CRON 검증 절 |
| 13 | `ee8e1a9` | **fix(execution): E2E 회귀 — @Transactional self-invocation 제거 + save 명시** |
| 14 | `f0743c3` | docs(day8): Playwright E2E 증거물 (스크린샷 2 + ps 텍스트) |

---

## 3. 행동 변화

1. **CRON 자동 실행**: `scheduleType=CRON, status=ACTIVE` 인터페이스가 `cronExpression`대로 자동 실행 → `ExecutionLog.triggeredBy=SCHEDULER` 신규 기록 경로 가동. planning.md §3 "필수 구현"의 누락 기능 회수.
2. **Docker 한 줄 기동**: `docker compose --profile full up -d`로 postgres+backend+frontend 전체 컨테이너 기동. 기본 기동(`docker compose up -d`)은 postgres만 — Day 7 개발 워크플로 보존.
3. **CRLF 근본 해결**: `.gitattributes` + `.dockerignore` 도입으로 Windows/Linux 혼합 개발 환경의 line ending 문제 재발 방지.

---

## 4. 빌드·테스트 상태

### 4-A 자동 테스트

- `./gradlew test` 전체 PASS: **45 tests / 0 failures / 5 skipped** (Day 7 baseline 40 + Day 8 신규 5)
- `InterfaceCronSchedulerTest` 5 케이스: 발화/미발화/최초기동/경합흡수/잘못된cron
- E2E 회귀 fix(ee8e1a9)로 `verify(configRepository).save(...)` 추가 — 동일 회귀(self-invocation) 재발 방어

### 4-B Docker 이미지

| 이미지 | 크기 | base |
|---|---|---|
| ifms-backend:local | 365MB | temurin-17-jre-alpine |
| ifms-frontend:local | 28.5MB | nginx:alpine |

### 4-C E2E 실측 (Playwright MCP 자동화, 2026-04-21 16:34~16:47)

| 검증 | 결과 |
|---|---|
| 전체 스택 풀 기동 (`docker compose --profile full up -d`) | ✅ 3 컨테이너 healthy |
| 로그인 (operator@ifms.local) → /dashboard | ✅ |
| 인터페이스 등록: day8_cron_demo (scheduleType=CRON, cron=`0 * * * * *`) | ✅ ID 5 |
| CRON 자동 실행: 2분 대기 후 ExecutionLog.triggeredBy=SCHEDULER | ✅ ID 30 `RUNNING` @ 16:45:05 KST |
| 다음 tick에서 DUPLICATE_RUNNING 경합 흡수 | ✅ 16:46:05 `CRON 트리거 스킵 사유=ConflictException` INFO 로그 |
| UI /history 최상단 SCHEDULER 행 표시 | ✅ [day8-scheduler-history.png](screenshots/day8-scheduler-history.png) |
| SSE 연결 유지 (/dashboard `SSE open` 녹색) | ✅ [day8-dashboard-sse-open.png](screenshots/day8-dashboard-sse-open.png) |

### 4-D Security 체크 3줄

| 체크 | 결과 |
|---|---|
| `git grep ifms1234`: `.env.example`·README·주석 외 0건 | ✅ CLAUDE.md·AbstractPostgresIntegrationTest만 (둘 다 예시/테스트) |
| 대시보드 API JSON에 `requestPayload/responsePayload` 키 0건 | ✅ `['generatedAt', 'since', 'totals', 'byProtocol', 'recentFailures', 'sseConnections']` |
| 2분 관찰 SCHEDULER 중복 0건 | ✅ interface_config_id=5의 SCHEDULER 로그 1건만 (uk_log_running + advisory lock 정상) |

---

## 5. E2E에서 드러난 회귀와 교훈 — `ee8e1a9`

Task 3 단위 테스트 5건은 모두 PASS였고 spec + code quality 리뷰도 통과했으나, **Docker 풀 기동 후 실 스케줄 tick 4회(16:36~16:39)를 관찰**한 결과 `ExecutionLog.triggeredBy=SCHEDULER` 건이 0건이었다.

### 원인

```java
@Scheduled(fixedDelayString = "PT1M", ...)
public void sweep() {
    tick(LocalDateTime.now(KST));   // ← self-invocation
}

@Transactional                     // ← 프록시 미경유라 적용 안 됨
public void tick(LocalDateTime now) {
    ...
    c.markScheduled(now);          // ← dirty 마킹만, 플러시 안 됨
}
```

Spring AOP 프록시는 같은 빈 안의 메서드 호출(`sweep()` → `this.tick()`)에 트랜잭션을 씌우지 않는다. Day 3의 `@Async self-invocation` 함정(`project_day3_pitfalls.md`)과 **정확히 동일한 메커니즘**이다. 결과: 매 tick마다 `last_scheduled_at`이 영영 NULL로 남아 "catch-up 안 함 → return" 분기만 반복.

### Fix

- `@Transactional` 제거 (실제로 작동 안 했으니 잘못된 의도였음)
- `configRepository.save(c)` 명시 호출로 영속화 보장 (null 초기화 + 트리거 성공 + 트리거 경합 3 경로 모두)
- 단위 테스트 4건에 `verify(configRepository).save(c)` / `verify(...).never().save()` 추가

### 교훈

- **단위 테스트가 직접 `scheduler.tick()`을 호출하면 프록시 우회 버그를 잡지 못한다.** Mockito 단위 테스트는 "의도한 동작 공리"를 검증하지만, Spring proxy 경유 transaction boundary가 실제로 작동하는지는 검증하지 못함.
- **E2E(실 Docker + 실 DB + 실 HTTP)가 단위 테스트의 맹점을 드러낸다.** Day 7까지는 `@SpringBootTest`로 부분 검증했지만, `@Scheduled` fixedDelay는 부분 검증 경계 밖.
- **@Async/@Transactional self-invocation은 한 번 학습한 뒤에도 재발한다.** `MEMORY.md`의 project_day3_pitfalls에 명시되어 있었음에도 Task 3 설계에서 놓침.

---

## 6. 이월 (Day 9+ / 운영 전환)

| 항목 | 사유 |
|---|---|
| ADR-008 JWT 미채택 근거 문서 | 4-에이전트 회의 세션 인증 유지 결정 근거 기록 |
| 대시보드 sparkline (회의 대안) | 프로토콜별 성공률 시각화 |
| JMH 벤치 확장 — ExecutionLog 조회 | 성능 리포트 대안 |
| 스케줄러 catch-up (재기동 누락 발화 소급) | 운영 전환 범위 |
| frontend healthcheck | nginx 기동 확인 (Task 6 리뷰 M2 제안) |
| `ifms-net` 네트워크 worktree 경고 | `external: true` 선언으로 해결 가능 |
| ShedLock / 분산 스케줄러 | 단일 인스턴스 프로토타입 — advisory lock+uk_log_running으로 충분 |

---

## 7. 회의 근거

spec §1 참조. @Security/@DBA/@DevilsAdvocate 세 에이전트 모두 JWT 반대 수렴(세션+CSRF 유지가 금융권 표준에 더 가깝고 1일 내 완전 전환 불가 + 기존 E2E 회귀 리스크). @DBA가 스케줄러 3조건 지적(pool=1, zone Asia/Seoul, advisory lock 분리 — 후자는 동일 체인 재사용으로 해소).

---

## 8. 워크플로 메타

- 브랜치: `day8` (worktree `.worktrees/day8`)
- 검증 도구: **Playwright MCP**로 UI 자동화 + `docker logs`/PostgreSQL psql로 backend/DB 직접 검증
- 병합 경로: Day 7까지의 관례에 따라 `day8` → `main` fast-forward 예정 (finishing-a-development-branch 스킬로 마감)
