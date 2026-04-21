# Day 8 제출 완성도 보강 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** (1) `scheduleType=CRON` 인터페이스가 `cronExpression`대로 자동 실행되어 `ExecutionLog.triggeredBy=SCHEDULER`로 기록되게 한다. (2) `docker compose --profile full up -d` 한 줄로 postgres + backend + frontend 전체 기동이 되게 한다. (3) README·기록 정합.

**Architecture:**
- 단일 `@Scheduled(fixedDelay=PT1M, zone=Asia/Seoul)` 폴링 → `CronExpression.next(lastScheduledAt)` 평가 → `ExecutionTriggerService.trigger(id, SCHEDULER, "SYSTEM", null, null)` 호출. 추가 advisory lock 없음(기존 체인 그대로).
- `InterfaceConfig`에 `last_scheduled_at TIMESTAMPTZ NULL` 컬럼 1개 추가. 재기동 시 NULL=catch-up 안 함(명시적 설계).
- Docker: `docker/backend.Dockerfile`(multi-stage temurin-17-jre-alpine) + `docker/frontend.Dockerfile`(node20 build → nginx:alpine). nginx.conf는 Vite dev proxy 규칙 동일(`/api`·`/swagger-ui`·`/v3/api-docs` 프록시 + `/login`·`/logout` POST-only).
- Compose profile=`full`로 backend·frontend 서비스 추가. 기본 기동(`docker compose up`)은 postgres만 — Day 7 개발 워크플로 보존.

**Tech Stack:** Java 17 · Spring Boot 3.3.5 · PostgreSQL 16 · Vue 3 + Vuetify 3 + Vite 8 · Docker Compose v2.

---

## File Structure

### Create (신규)
| 파일 | 역할 |
|---|---|
| `backend/src/main/java/com/noaats/ifms/domain/execution/service/InterfaceCronScheduler.java` | 1분 폴링 + cron 발화 판정 + 트리거 호출 |
| `backend/src/test/java/com/noaats/ifms/domain/execution/service/InterfaceCronSchedulerTest.java` | cron 발화 판정 단위 테스트 |
| `docker/backend.Dockerfile` | backend 이미지 multi-stage 빌드 |
| `docker/frontend.Dockerfile` | frontend 이미지 multi-stage 빌드 |
| `docker/nginx.conf` | nginx 정적 서빙 + API 프록시 |
| `docker/backend-entrypoint.sh` | postgres wait-for (nc 기반) 후 java 기동 |

### Modify (기존)
| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/noaats/ifms/domain/interface_/domain/InterfaceConfig.java` | `last_scheduled_at` 필드 + `markScheduled()` 도메인 메서드 |
| `backend/src/main/resources/schema.sql` | `ALTER TABLE interface_config ADD COLUMN last_scheduled_at` |
| `docker-compose.yml` | `backend`·`frontend` 서비스 + `profiles: ["full"]` |
| `.env.example` | `IFMS_ADVISORY_LOCK_RETRY_NAMESPACE` 누락 라인 추가 |
| `README.md` | §2에 "옵션 B: 전체 Docker 기동" + "CRON 자동 실행 검증" 절 |

### Out of Scope
- Integration test(H2 기반)는 Day 7 Testcontainers 환경 이슈 그대로이므로 **단위 테스트만**. 수동 E2E로 통합 검증.
- Frontend 코드 변경 없음. nginx가 Vite proxy와 동일 규칙으로 서빙하면 그대로 동작.
- ActorContext 확장 없음. `"SYSTEM"` 문자열 상수를 호출자가 직접 전달.

---

## 공통 규약

- **테스트 실행 명령**은 PowerShell + `.\gradlew.bat` 고정 (Day 7 학습: `cmd /c gradlew.bat`은 cwd에서 gradlew를 못 찾음). 예시 모두 bash 표기(`./gradlew`)이나 Windows 실측은 `.\gradlew.bat`로 치환.
- **커밋 메시지**: 프로젝트 컨벤션(`feat(...)`·`fix(...)`·`docs(...)`·`test(...)`) + `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`. HEREDOC 사용.
- **빌드 게이트**: 각 Task의 "Step: Commit" 전에 `.\gradlew.bat test --no-daemon --console=plain` 전체 PASS 확인. 최소 묶음만 돌리지 않음 — Day 7 회귀 방지 원칙.

---

## Task 1: `InterfaceConfig`에 `last_scheduled_at` 필드 추가

**Files:**
- Modify: `backend/src/main/java/com/noaats/ifms/domain/interface_/domain/InterfaceConfig.java`

- [ ] **Step 1.1: 필드 + 도메인 메서드 추가**

`InterfaceConfig.java`의 `private Long version = 0L;` 바로 아래에 다음을 추가:

```java
/**
 * CRON 스케줄러 직전 발화 시각. InterfaceCronScheduler가 cronExpression.next(lastScheduledAt) 평가에 사용.
 * MANUAL 스케줄 타입은 항상 NULL. 재기동 시 NULL이면 첫 폴링 tick 시점을 기준으로 다음 발화를 계산(catch-up 안 함).
 */
@Column(name = "last_scheduled_at")
private java.time.LocalDateTime lastScheduledAt;

/**
 * 스케줄러가 방금 트리거했음을 기록. CronExpression.next(lastScheduledAt) 평가 기준점 갱신.
 * @param firedAt InterfaceCronScheduler tick 시각 (LocalDateTime.now())
 */
public void markScheduled(java.time.LocalDateTime firedAt) {
    this.lastScheduledAt = firedAt;
}
```

`import` 구문에 `java.time.LocalDateTime`은 파일 풀패스(`java.time.LocalDateTime`)로 쓰되, 기존 import 스타일을 따라 상단에 `import java.time.LocalDateTime;`을 추가하고 본문은 `LocalDateTime`으로 치환해도 됨. 프로젝트의 다른 Entity(`ExecutionLog`)가 import 스타일이므로 **import 추가 + 본문 단순화** 경로 선택.

- [ ] **Step 1.2: 컴파일 확인**

Run: `.\gradlew.bat compileJava --no-daemon --console=plain`
Expected: `BUILD SUCCESSFUL`, 경고만 허용.

- [ ] **Step 1.3: 기존 테스트 회귀 없음 확인**

Run: `.\gradlew.bat test --no-daemon --console=plain`
Expected: Day 7 기준 PASS 갯수와 동일 (0 fail / 0 error).

- [ ] **Step 1.4: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/interface_/domain/InterfaceConfig.java
git commit -m "$(cat <<'EOF'
feat(interface): InterfaceConfig.last_scheduled_at 필드 + markScheduled() 도메인 메서드

CRON 스케줄러가 cronExpression.next(lastScheduledAt) 평가 기준점으로 사용.
NULL=catch-up 안 함 (명시적 설계, 재기동 시 첫 tick 기준).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: schema.sql에 `last_scheduled_at` 컬럼 + COMMENT 추가

**Files:**
- Modify: `backend/src/main/resources/schema.sql`

- [ ] **Step 2.1: schema.sql 상단 interface_config CHECK 섹션 바로 위에 ALTER 추가**

파일의 `-- interface_config : CHECK 제약` 섹션 바로 **위** (line 21 이후, line 22 이전에 삽입)에 다음 블록을 추가:

```sql
-- =============================================================================
-- interface_config : last_scheduled_at 컬럼 (Day 8, CRON 스케줄러)
-- =============================================================================
-- Hibernate ddl-auto=update는 신규 컬럼을 NULL 허용으로 추가하므로 local/개발은 이 블록 없이도 동작.
-- 운영(validate)은 schema.sql이 DDL 원천이므로 명시.
-- continue-on-error=true이므로 이미 존재 시 오류 무시.
ALTER TABLE interface_config
    ADD COLUMN IF NOT EXISTS last_scheduled_at TIMESTAMP NULL;

COMMENT ON COLUMN interface_config.last_scheduled_at IS
    'CRON 스케줄러 직전 발화 시각. InterfaceCronScheduler가 cronExpression.next(lastScheduledAt) 평가에 사용. MANUAL은 항상 NULL.';
```

주의: 엔티티는 `@JdbcTypeCode` 없이 `@Column(name = "last_scheduled_at")`만 지정 → Hibernate가 `timestamp without time zone`으로 매핑. 따라서 DDL도 `TIMESTAMP NULL`(TIMESTAMPTZ 아님). `hibernate.jdbc.time_zone=Asia/Seoul`이 이미 `application.yml`에 있으므로 저장 시 자동 KST 변환.

- [ ] **Step 2.2: local 기동 시 Hibernate ddl-auto=update가 컬럼 자동 생성하는지 확인**

schema.sql의 `continue-on-error=true` 동작을 이용하므로 별도 실행 없음. 다음 Task 3 스케줄러 컴파일이 `lastScheduledAt` 필드 getter/setter에 의존하므로 여기서는 수동 검증만.

- [ ] **Step 2.3: Commit**

```bash
git add backend/src/main/resources/schema.sql
git commit -m "$(cat <<'EOF'
feat(schema): interface_config.last_scheduled_at ALTER + COMMENT (Day 8 CRON 스케줄러)

운영(validate) 경로용. local(update)은 Hibernate가 엔티티 필드로 자동 생성.
continue-on-error=true로 재기동 시 중복 ALTER 오류 무시.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `InterfaceCronScheduler` — TDD로 단위 테스트 먼저

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/execution/service/InterfaceCronSchedulerTest.java`
- Create: `backend/src/main/java/com/noaats/ifms/domain/execution/service/InterfaceCronScheduler.java`

### 3-A. 테스트 스켈레톤 먼저

- [ ] **Step 3.1: 실패하는 테스트 작성**

`InterfaceCronSchedulerTest.java` 생성:

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noaats.ifms.domain.execution.domain.TriggerType;
import com.noaats.ifms.domain.execution.dto.ExecutionTriggerResponse;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ProtocolType;
import com.noaats.ifms.domain.interface_.domain.ScheduleType;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Day 8: CRON 스케줄러 단위 테스트.
 * ExecutionTriggerService는 mock으로, InterfaceConfig는 직접 인스턴스화.
 * cron 평가와 트리거 위임만 검증. Spring 컨텍스트·DB 우회.
 */
@ExtendWith(MockitoExtension.class)
class InterfaceCronSchedulerTest {

    @Mock InterfaceConfigRepository configRepository;
    @Mock ExecutionTriggerService triggerService;

    @InjectMocks InterfaceCronScheduler scheduler;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 테스트 헬퍼: 최소 필드로 CRON 타입 InterfaceConfig 생성 */
    private InterfaceConfig cronConfig(Long id, String cron, LocalDateTime lastScheduledAt) {
        InterfaceConfig c = InterfaceConfig.builder()
                .name("test-" + id)
                .protocol(ProtocolType.REST)
                .endpoint("http://mock/test")
                .httpMethod("GET")
                .scheduleType(ScheduleType.CRON)
                .cronExpression(cron)
                .status(InterfaceStatus.ACTIVE)
                .timeoutSeconds(30)
                .maxRetryCount(3)
                .build();
        // 리플렉션으로 id·lastScheduledAt 주입 (테스트 한정)
        try {
            var idField = InterfaceConfig.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);
            if (lastScheduledAt != null) {
                c.markScheduled(lastScheduledAt);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    @Test
    void tick_firesTrigger_whenCronElapsedSinceLastScheduled() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 22, 12, 5, 30);   // 12:05:30 KST
        LocalDateTime last = LocalDateTime.of(2026, 4, 22, 12, 0, 0);   // 직전 12:00

        // cron "0 * * * * *" = 매 분 0초 → 12:01, 12:02, 12:03, 12:04, 12:05 발화 예정
        InterfaceConfig c = cronConfig(10L, "0 * * * * *", last);
        when(configRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(c));
        when(triggerService.trigger(eq(10L), eq(TriggerType.SCHEDULER), eq("SYSTEM"), isNull(), isNull()))
                .thenReturn(mockTriggerResponse(10L, 999L));

        scheduler.tick(now);

        verify(triggerService, times(1)).trigger(eq(10L), eq(TriggerType.SCHEDULER), eq("SYSTEM"), isNull(), isNull());
        // markScheduled(now) 호출 → lastScheduledAt == now
        assertThat(c.getLastScheduledAt()).isEqualTo(now);
    }

    @Test
    void tick_doesNotFire_whenCronNotYetElapsed() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 22, 12, 0, 30);    // 12:00:30
        LocalDateTime last = LocalDateTime.of(2026, 4, 22, 12, 0, 0);    // 직전 12:00
        // cron "0 * * * * *" 다음 발화는 12:01:00 → 아직 아님
        InterfaceConfig c = cronConfig(11L, "0 * * * * *", last);
        when(configRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(c));

        scheduler.tick(now);

        verify(triggerService, never()).trigger(any(), any(), any(), any(), any());
        assertThat(c.getLastScheduledAt()).isEqualTo(last);
    }

    @Test
    void tick_initializesLastScheduled_whenNullOnFirstTick() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 22, 12, 0, 30);
        InterfaceConfig c = cronConfig(12L, "0 * * * * *", null);   // 최초 기동
        when(configRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(c));

        scheduler.tick(now);

        // 첫 tick은 catch-up 안 함 → trigger 호출 0, lastScheduledAt=now로 초기화
        verify(triggerService, never()).trigger(any(), any(), any(), any(), any());
        assertThat(c.getLastScheduledAt()).isEqualTo(now);
    }

    @Test
    void tick_skipsInterface_whenTriggerThrowsConflict() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 22, 12, 5, 30);
        LocalDateTime last = LocalDateTime.of(2026, 4, 22, 12, 0, 0);
        InterfaceConfig c = cronConfig(13L, "0 * * * * *", last);
        when(configRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(c));
        when(triggerService.trigger(any(), any(), any(), any(), any()))
                .thenThrow(new com.noaats.ifms.global.exception.ConflictException(
                        com.noaats.ifms.global.exception.ErrorCode.DUPLICATE_RUNNING));

        // 예외는 흡수되어야 함 (스케줄러가 죽으면 안 됨)
        scheduler.tick(now);

        // ConflictException이어도 lastScheduledAt은 갱신 (다음 tick에 또 재시도하면 무한 DUP 로그)
        assertThat(c.getLastScheduledAt()).isEqualTo(now);
    }

    @Test
    void tick_invalidCronExpression_isLoggedAndSkipped() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 22, 12, 5, 30);
        LocalDateTime last = LocalDateTime.of(2026, 4, 22, 12, 0, 0);
        InterfaceConfig c = cronConfig(14L, "this is not a cron", last);
        when(configRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(c));

        scheduler.tick(now);  // 예외 전파 안 됨

        verify(triggerService, never()).trigger(any(), any(), any(), any(), any());
        // 잘못된 cron은 lastScheduledAt도 갱신 안 함 (관리자가 고치면 즉시 재평가 가능)
        assertThat(c.getLastScheduledAt()).isEqualTo(last);
    }

    private static ExecutionTriggerResponse mockTriggerResponse(Long configId, Long logId) {
        // ExecutionTriggerResponse record: (logId, interfaceId, parentLogId, status, triggeredBy, retryCount, startedAt)
        return new ExecutionTriggerResponse(
                logId, configId, null,
                com.noaats.ifms.domain.execution.domain.ExecutionStatus.RUNNING,
                TriggerType.SCHEDULER,
                0,
                java.time.OffsetDateTime.now(KST)
        );
    }
}
```

**주의**: `ExecutionTriggerResponse`의 실제 시그니처를 모르므로 Task 3-B 단계에서 compile error가 나면 시그니처를 확인해 맞추세요. `ExecutionTriggerResponse.java`를 먼저 Read로 열어 필드 순서·타입을 확인한 뒤 `mockTriggerResponse` 헬퍼를 수정.

- [ ] **Step 3.2: 테스트가 컴파일 실패하는지 확인 (InterfaceCronScheduler 미존재)**

Run: `.\gradlew.bat compileTestJava --no-daemon --console=plain`
Expected: `cannot find symbol: class InterfaceCronScheduler` 컴파일 에러. 이는 TDD의 RED 단계.

### 3-B. 프로덕션 구현

- [ ] **Step 3.3: `ExecutionTriggerResponse` 시그니처 확인**

Read: `backend/src/main/java/com/noaats/ifms/domain/execution/dto/ExecutionTriggerResponse.java`

필드 순서·타입을 확인 후 Step 3.1 헬퍼를 실제 시그니처에 맞게 수정.

- [ ] **Step 3.4: `InterfaceCronScheduler` 구현**

`InterfaceCronScheduler.java` 생성:

```java
package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.TriggerType;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ScheduleType;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import com.noaats.ifms.global.security.ActorContext;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRON 스케줄러 (Day 8). planning.md §3 필수구현 — scheduleType=CRON 인터페이스의 자동 실행.
 *
 * <h3>동작</h3>
 * <ol>
 *   <li>1분 주기 fixedDelay 폴링 (OrphanRunningWatchdog 5분과 충돌 없음 — 본 스케줄러는 advisory lock을 잡지 않는다).</li>
 *   <li>ACTIVE + CRON 인터페이스 전체 조회</li>
 *   <li>각 인터페이스 cronExpression의 next(lastScheduledAt) &lt;= now이면 ExecutionTriggerService.trigger(SCHEDULER) 호출</li>
 *   <li>markScheduled(now)로 lastScheduledAt 갱신 (catch-up 방지)</li>
 * </ol>
 *
 * <h3>설계 결정</h3>
 * <ul>
 *   <li><b>최초 기동(lastScheduledAt=null)</b>: catch-up 안 함. 첫 tick 시점을 기준으로만 다음 발화 계산.</li>
 *   <li><b>동시 실행 방어</b>: ExecutionTriggerService가 이미 advisory lock + uk_log_running 보유 → 본 스케줄러는 가드 없이 호출만.</li>
 *   <li><b>예외 흡수</b>: ConflictException 등은 로그만 남기고 다음 tick으로. 단일 인터페이스 실패가 전체 스케줄러를 죽이지 않음.</li>
 *   <li><b>actor</b>: ActorContext.SYSTEM 상수 사용. SCHEDULER 경로 전용.</li>
 *   <li><b>타임존</b>: cron·now 모두 Asia/Seoul (application.yml hibernate.jdbc.time_zone 정합).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterfaceCronScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final InterfaceConfigRepository configRepository;
    private final ExecutionTriggerService triggerService;

    /**
     * 1분 주기 폴링. @Scheduled(zone)은 cron 표현식에만 적용되므로 fixedDelay는 무관.
     * initialDelay로 앱 기동 직후 폭주 방지 (OrphanRunningWatchdog 1분 지연과 동일).
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void sweep() {
        tick(LocalDateTime.now(KST));
    }

    /**
     * 테스트용 공개 진입점. @Scheduled 어노테이션 없이 now를 직접 주입.
     * Spring Transaction 경계 안에서 InterfaceConfig의 dirty checking이 동작하도록 @Transactional.
     */
    @Transactional
    public void tick(LocalDateTime now) {
        Specification<InterfaceConfig> activeCron = (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), InterfaceStatus.ACTIVE),
                cb.equal(root.get("scheduleType"), ScheduleType.CRON)
        );
        List<InterfaceConfig> targets = configRepository.findAll(activeCron);
        if (targets.isEmpty()) return;

        log.debug("CRON tick now={} candidates={}", now, targets.size());
        for (InterfaceConfig c : targets) {
            try {
                processOne(c, now);
            } catch (RuntimeException e) {
                log.warn("CRON 스케줄러 단일 인터페이스 처리 실패 id={} name={} cron={} : {}",
                        c.getId(), c.getName(), c.getCronExpression(), e.toString());
            }
        }
    }

    private void processOne(InterfaceConfig c, LocalDateTime now) {
        CronExpression cron;
        try {
            cron = CronExpression.parse(c.getCronExpression());
        } catch (IllegalArgumentException bad) {
            log.warn("잘못된 cron 표현식 id={} cron='{}' : {}", c.getId(), c.getCronExpression(), bad.getMessage());
            return;  // 표현식 수정 시 즉시 재평가 가능하도록 lastScheduledAt 갱신 안 함
        }

        LocalDateTime last = c.getLastScheduledAt();
        if (last == null) {
            // 최초 기동: catch-up 안 함. 첫 tick을 기준점으로만 기록.
            c.markScheduled(now);
            return;
        }

        // cron.next(last) — last 이후 가장 가까운 발화 시각
        ZonedDateTime nextZ = cron.next(last.atZone(KST));
        if (nextZ == null) {
            // 더 이상 발화 없음 (예: 특정 연도 한정). 갱신 안 함.
            log.debug("cron 더 이상 발화 없음 id={} cron='{}'", c.getId(), c.getCronExpression());
            return;
        }
        LocalDateTime next = nextZ.toLocalDateTime();
        if (next.isAfter(now)) {
            return;  // 아직 발화 안 됨
        }

        // 발화 시점 경과 → 트리거 호출
        try {
            triggerService.trigger(c.getId(), TriggerType.SCHEDULER, ActorContext.SYSTEM, null, null);
            log.info("CRON 트리거 id={} name={} cron='{}' last={} now={}",
                    c.getId(), c.getName(), c.getCronExpression(), last, now);
        } catch (RuntimeException e) {
            // DUPLICATE_RUNNING 등은 정상 경합 — 이미 RUNNING이면 이번 tick은 스킵하고 lastScheduledAt만 갱신.
            log.info("CRON 트리거 스킵 id={} cron='{}' 사유={}",
                    c.getId(), c.getCronExpression(), e.getClass().getSimpleName());
        }
        c.markScheduled(now);
    }
}
```

- [ ] **Step 3.5: Test RED → GREEN 확인**

Run: `.\gradlew.bat test --tests "*InterfaceCronSchedulerTest*" --no-daemon --console=plain`
Expected: 5 tests PASS.

테스트가 여전히 실패한다면:
- `ExecutionTriggerResponse` 생성자 시그니처 mismatch → Step 3.3에서 확인한 실제 시그니처로 헬퍼 수정
- `any(Specification.class)` 타입 경고 → `@SuppressWarnings("unchecked")` 추가 또는 `org.mockito.ArgumentMatchers.<Specification<InterfaceConfig>>any()` 사용

- [ ] **Step 3.6: 전체 회귀 — 기존 테스트 PASS 유지 확인**

Run: `.\gradlew.bat test --no-daemon --console=plain`
Expected: Day 7 기준 + 신규 5 케이스 모두 PASS. 0 fail / 0 error.

- [ ] **Step 3.7: Commit**

```bash
git add backend/src/main/java/com/noaats/ifms/domain/execution/service/InterfaceCronScheduler.java \
        backend/src/test/java/com/noaats/ifms/domain/execution/service/InterfaceCronSchedulerTest.java
git commit -m "$(cat <<'EOF'
feat(execution): InterfaceCronScheduler — scheduleType=CRON 자동 실행 (Day 8)

1분 폴링 + CronExpression.next(lastScheduledAt) + ExecutionTriggerService.trigger(SCHEDULER) 호출.
최초 기동 catch-up 안 함 (명시적 설계). 단일 인터페이스 예외는 흡수.
단위 테스트 5 케이스: 발화/미발화/최초기동/경합흡수/잘못된cron.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `backend.Dockerfile` — multi-stage 빌드

**Files:**
- Create: `docker/backend.Dockerfile`
- Create: `docker/backend-entrypoint.sh`

- [ ] **Step 4.1: 디렉토리 생성 확인**

Run: `ls docker/`
Expected: 기존 `postgres/` 하위 디렉토리 존재 확인.

- [ ] **Step 4.2: backend.Dockerfile 작성**

`docker/backend.Dockerfile`:

```dockerfile
# ============================================================
# IFMS backend — multi-stage build
# ============================================================
# Stage 1: Gradle로 bootJar 생성
# Stage 2: JRE 17 Alpine 런타임 — 약 170MB
# ============================================================

# ---- build stage ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# Gradle wrapper + 설정만 먼저 복사 → 의존성 레이어 캐시
COPY backend/gradlew /workspace/gradlew
COPY backend/gradle /workspace/gradle
COPY backend/build.gradle /workspace/build.gradle
COPY backend/settings.gradle /workspace/settings.gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 bootJar
COPY backend/src /workspace/src
RUN ./gradlew --no-daemon bootJar

# ---- runtime stage ----
FROM eclipse-temurin:17-jre-alpine AS runtime
RUN apk add --no-cache netcat-openbsd bash tzdata \
 && ln -sf /usr/share/zoneinfo/Asia/Seoul /etc/localtime \
 && echo "Asia/Seoul" > /etc/timezone
ENV TZ=Asia/Seoul

WORKDIR /app
COPY --from=build /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar
COPY docker/backend-entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
```

- [ ] **Step 4.3: backend-entrypoint.sh 작성**

`docker/backend-entrypoint.sh`:

```bash
#!/usr/bin/env bash
set -e

# postgres 대기 (compose depends_on: condition=service_healthy가 있어도 race 방지)
: "${DB_HOST:=postgres}"
: "${DB_PORT:=5432}"
echo "[entrypoint] waiting for ${DB_HOST}:${DB_PORT} ..."
for i in $(seq 1 60); do
  if nc -z "${DB_HOST}" "${DB_PORT}"; then
    echo "[entrypoint] db ready"
    break
  fi
  sleep 1
done

exec java $JAVA_OPTS -jar /app/app.jar
```

**Windows 주의**: `chmod +x`는 Dockerfile 내부에서 적용되므로 호스트에서 권한 부여 불필요. 단, 파일이 CRLF로 저장되면 Linux 컨테이너에서 `/usr/bin/env: 'bash\r': No such file or directory`가 뜬다. 저장 시 **LF 강제**. VSCode에서 우하단 `CRLF` 클릭 → `LF` 선택 후 저장.

- [ ] **Step 4.4: 로컬에서 이미지 빌드만 검증**

Run: `docker build -f docker/backend.Dockerfile -t ifms-backend:local .`
Expected: `Successfully tagged ifms-backend:local`. 첫 빌드는 ~5분 (Gradle 의존성 다운로드 포함).

실패 시 흔한 원인:
- `gradle-wrapper.jar`가 `.gitignore`에 들어있어 컨텍스트에 없음 → `git ls-files backend/gradle/wrapper/`로 확인
- entrypoint.sh CRLF → `file docker/backend-entrypoint.sh` 결과에 `CRLF` 확인 → LF로 재저장

- [ ] **Step 4.5: Commit**

```bash
git add docker/backend.Dockerfile docker/backend-entrypoint.sh
git commit -m "$(cat <<'EOF'
build(docker): backend multi-stage Dockerfile (gradle build → temurin-17-jre-alpine)

Asia/Seoul TZ 설정, nc 기반 db-wait 스크립트. 이미지 ~170MB.
compose profile=full에서 ifms-backend:local로 기동.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `frontend.Dockerfile` + `nginx.conf`

**Files:**
- Create: `docker/frontend.Dockerfile`
- Create: `docker/nginx.conf`

- [ ] **Step 5.1: nginx.conf 작성**

`docker/nginx.conf`:

```nginx
# IFMS frontend nginx (Day 8, docker compose profile=full)
# ---------------------------------------------------------
# Vite dev proxy(vite.config.ts)와 동일 규칙:
#   /api, /swagger-ui, /v3/api-docs → backend:8080
#   /login, /logout                  → POST만 backend:8080 (GET은 SPA 라우트)
# ---------------------------------------------------------

upstream backend_upstream {
    server backend:8080;
}

server {
    listen 80;
    server_name _;

    # SPA 정적 파일
    root /usr/share/nginx/html;
    index index.html;

    # API 프록시 — 세션 쿠키·CSRF 헤더·SSE 통과
    location /api/ {
        proxy_pass http://backend_upstream;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # SSE 스트리밍 호환
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
        chunked_transfer_encoding on;
    }

    location /swagger-ui/ {
        proxy_pass http://backend_upstream;
        proxy_set_header Host $host;
    }

    location /v3/api-docs {
        proxy_pass http://backend_upstream;
        proxy_set_header Host $host;
    }

    # Spring formLogin: POST만 backend로, GET은 SPA 라우트로
    location = /login {
        if ($request_method = POST) {
            proxy_pass http://backend_upstream;
        }
        try_files $uri /index.html;
    }

    location = /logout {
        if ($request_method = POST) {
            proxy_pass http://backend_upstream;
        }
        try_files $uri /index.html;
    }

    # SPA 라우터 폴백
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

**주의**: nginx `if`는 위치 블록 안에서 제한적이지만 `proxy_pass` 분기는 허용되는 안전 패턴.

- [ ] **Step 5.2: frontend.Dockerfile 작성**

`docker/frontend.Dockerfile`:

```dockerfile
# ============================================================
# IFMS frontend — Vite build → nginx:alpine 정적 서빙
# ============================================================

# ---- build stage ----
FROM node:20-alpine AS build
WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json* /workspace/
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund

COPY frontend /workspace
RUN npm run build

# ---- runtime stage ----
FROM nginx:alpine AS runtime
# Alpine TZ
RUN apk add --no-cache tzdata \
 && ln -sf /usr/share/zoneinfo/Asia/Seoul /etc/localtime \
 && echo "Asia/Seoul" > /etc/timezone

COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 80
# nginx:alpine 기본 CMD 사용 (nginx -g 'daemon off;')
```

- [ ] **Step 5.3: 로컬에서 frontend 이미지 빌드**

Run: `docker build -f docker/frontend.Dockerfile -t ifms-frontend:local .`
Expected: `Successfully tagged ifms-frontend:local`. 첫 빌드 ~3분 (npm 의존성).

- [ ] **Step 5.4: Commit**

```bash
git add docker/frontend.Dockerfile docker/nginx.conf
git commit -m "$(cat <<'EOF'
build(docker): frontend Dockerfile + nginx.conf (Vite build → nginx:alpine)

nginx 프록시 규칙은 vite.config.ts와 동일 (API·SSE·Swagger + POST-only login/logout).
SSE 스트리밍 호환: proxy_buffering off, chunked_transfer_encoding on.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `docker-compose.yml` 확장 — profile=full에 backend·frontend

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.env.example`

- [ ] **Step 6.1: .env.example 갱신**

`.env.example` 파일 말미에 추가:

```bash
# -------- 재처리 체인 advisory lock 네임스페이스 (ADR-005 Q4) --------
# 기본값 1229931348 = 0x49464D54 = 'IFMT' ASCII. 일반 실행 namespace와 분리.
IFMS_ADVISORY_LOCK_RETRY_NAMESPACE=1229931348
```

- [ ] **Step 6.2: docker-compose.yml에 backend·frontend 서비스 추가**

`docker-compose.yml` 파일의 `adminer:` 블록(line 49~63) **다음**에, `volumes:` 블록(line 65) **이전**에 다음을 삽입:

```yaml
  backend:
    image: ifms-backend:local
    container_name: ifms-backend
    profiles: ["full"]
    build:
      context: .
      dockerfile: docker/backend.Dockerfile
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      # 기본 application.yml 값을 env로 override
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD}
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-local}
      IFMS_ACTOR_ANON_SALT: ${IFMS_ACTOR_ANON_SALT}
      IFMS_ADVISORY_LOCK_NAMESPACE: ${IFMS_ADVISORY_LOCK_NAMESPACE:-1229931347}
      IFMS_ADVISORY_LOCK_RETRY_NAMESPACE: ${IFMS_ADVISORY_LOCK_RETRY_NAMESPACE:-1229931348}
      DB_HOST: postgres
      DB_PORT: "5432"
      TZ: Asia/Seoul
      JAVA_OPTS: "-Xms256m -Xmx512m -XX:+UseContainerSupport"
    ports:
      - "127.0.0.1:8080:8080"
    healthcheck:
      # swagger-ui는 백엔드 기동 완료의 명확한 증거 (actuator starter 미도입)
      test: ["CMD-SHELL", "wget -q --spider http://localhost:8080/swagger-ui/index.html || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 60s
    networks:
      - ifms-net

  frontend:
    image: ifms-frontend:local
    container_name: ifms-frontend
    profiles: ["full"]
    build:
      context: .
      dockerfile: docker/frontend.Dockerfile
    restart: unless-stopped
    depends_on:
      backend:
        condition: service_healthy
    ports:
      - "127.0.0.1:8090:80"
    environment:
      TZ: Asia/Seoul
    networks:
      - ifms-net
```

**포트 선택 근거**: frontend를 `8090`에 바인딩. `80`을 피한 이유는 Windows에서 IIS/Hyper-V가 80을 선점하는 경우가 잦음. 로컬 전용이므로 임의 포트 허용.

- [ ] **Step 6.3: Profile 기본 기동이 여전히 postgres만 뜨는지 확인**

Run: `docker compose config --profiles`
Expected 출력에 `full`이 포함됨.

Run: `docker compose up -d`
Expected: `postgres`, (dev-tools 프로파일 활성 시 `adminer`)만 기동. backend/frontend는 profile=full일 때만.

Run: `docker compose ps`
Expected: `ifms-postgres Up (healthy)`만 표시.

Run: `docker compose down`
Expected: `Container ifms-postgres Stopped`.

- [ ] **Step 6.4: Profile=full로 전체 기동 스모크**

Run: `docker compose --profile full up -d --build`
Expected: postgres → backend(60~90s 후 healthy) → frontend 순차 기동.

Run: `docker compose --profile full ps`
Expected:
- `ifms-postgres Up (healthy)`
- `ifms-backend Up (healthy)`
- `ifms-frontend Up`

확인:
- Run: `curl -s http://localhost:8080/swagger-ui/index.html | head -5` → HTML 나오면 OK
- Run: `curl -s http://localhost:8090/ | head -5` → Vue index.html 나오면 OK
- Run: `curl -s http://localhost:8090/v3/api-docs | head -c 200` → OpenAPI JSON 나오면 OK (nginx 프록시 작동)

- [ ] **Step 6.5: 정리**

Run: `docker compose --profile full down`

- [ ] **Step 6.6: Commit**

```bash
git add docker-compose.yml .env.example
git commit -m "$(cat <<'EOF'
build(compose): profile=full에 backend·frontend 서비스 추가

docker compose --profile full up -d 한 줄로 postgres+backend+frontend 기동.
기본 기동(profile 없음)은 postgres만 — Day 7 개발 워크플로 보존.
backend healthcheck: /swagger-ui/index.html (actuator starter 미도입).
frontend port: 127.0.0.1:8090 (80은 Windows IIS 충돌 회피).
.env.example에 IFMS_ADVISORY_LOCK_RETRY_NAMESPACE 누락 라인 추가.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: README — 옵션 B 절 + CRON 자동 실행 검증 절

**Files:**
- Modify: `README.md`

- [ ] **Step 7.1: 현재 README §2 "빠른 실행" 구조 확인**

Read: `README.md`의 1~50 라인. 이미 `docker-compose up -d` + gradle/npm 병행 스타일임을 확인(fact-finding에서 확인).

- [ ] **Step 7.2: §2를 "옵션 A (호스트 개발)"로 유지하고 "옵션 B (Docker 전체)" 추가**

기존 §2의 3-명령어 블록을 유지하되, 제목을 `## 2. 빠른 실행`에서 다음으로 변경:

```markdown
## 2. 빠른 실행

### 옵션 A: 호스트 개발 (기존, Day 5~7 워크플로)

PostgreSQL만 Docker로 띄우고 backend/frontend는 호스트에서 실행.

    # 1) 최초 1회: .env 생성
    cp .env.example .env

    # 2) PostgreSQL 16 기동
    docker compose up -d

    # 3) 백엔드 (8080)
    cd backend
    ./gradlew bootRun

    # 4) 프런트 (Vite dev 서버, 5173, 백엔드 프록시)
    cd frontend
    npm install
    npm run dev

브라우저에서 <http://localhost:5173> 접속 → `operator@ifms.local` / `operator1234`로 로그인 (또는 `admin@ifms.local` / `admin1234`).

### 옵션 B: 전체 Docker 기동 (Day 8 추가)

한 줄로 postgres + backend + frontend 전부 컨테이너로.

    # 최초 1회: .env 생성 (필수)
    cp .env.example .env

    # 전체 기동 (첫 빌드 ~5~8분)
    docker compose --profile full up -d --build

    # 상태 확인 (backend healthy까지 ~60s)
    docker compose --profile full ps

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
```

기존 §2 본문을 위 블록으로 대체. `### 환경 의존` 서브섹션은 기존 파일에서 이미 존재하므로 줄바꿈만 맞춤.

- [ ] **Step 7.3: "CRON 자동 실행 검증" 신규 절 추가**

README에서 §2 바로 **다음**(§3 이전)에 다음 섹션을 삽입:

```markdown
## 2-A. CRON 자동 실행 검증 (Day 8 신규)

`scheduleType=CRON` 인터페이스는 `InterfaceCronScheduler`(1분 폴링)가 `cronExpression`대로 자동 실행하고 `ExecutionLog.triggeredBy=SCHEDULER`로 기록한다.

### 등록 예시 (Swagger UI / curl)

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

cronExpression은 **6-필드 Spring 형식**(초 분 시 일 월 요일). `0 * * * * *` = 매 분 0초.

### 확인

등록 후 최대 2분 대기 → ExecutionHistory(`/history`)에서 **triggeredBy=SCHEDULER** 필터.
또는:

    curl http://localhost:8080/api/executions?triggeredBy=SCHEDULER

### 동작 원칙
- **최초 기동은 catch-up 안 함**: `last_scheduled_at` NULL → 첫 폴링 tick 시점을 기준점으로 기록만. 과거 누락 발화는 소급하지 않는다.
- **advisory lock + uk_log_running 보호막 재사용**: 스케줄러 시점과 사용자의 수동 트리거가 동시에 같은 인터페이스를 치면 한 쪽만 성공(409 DUPLICATE_RUNNING).
- **분 단위 이상 권장**: 스케줄러 폴링이 1분이므로 초 단위 cron은 1분에 1회만 발화한다.

### 스크린샷
- `docs/screenshots/day8-scheduler-history.png` — ExecutionHistory에서 SCHEDULER 필터 결과 (수동 검증 단계에서 촬영)
- `docs/screenshots/day8-docker-compose-ps.png` — `docker compose --profile full ps` healthy 출력 (수동 검증 단계에서 촬영)
```

- [ ] **Step 7.4: Commit (스크린샷 제외, 문서만 먼저)**

```bash
git add README.md
git commit -m "$(cat <<'EOF'
docs(readme): 옵션 B 전체 Docker 기동 + CRON 자동 실행 검증 절 (Day 8)

옵션 A(호스트 개발) 기존 워크플로 보존 + 옵션 B(docker compose --profile full) 신설.
CRON 등록 curl 예시 + triggeredBy=SCHEDULER 확인 방법 + 동작 원칙 3줄.
스크린샷은 수동 검증 단계에서 별도 커밋.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: 수동 E2E 검증 (옵션 A 기준, Docker 선택적)

**Files:** 없음 (검증만)

- [ ] **Step 8.1: 옵션 A로 전체 회귀**

Run: `docker compose up -d` (postgres only) → 백엔드/프런트는 호스트에서 기동.

Day 5~7에 통과한 로그인/CSRF/Interface 등록/수동 트리거/SSE 모두 여전히 PASS하는지 최소 5건:

- [ ] 로그인 `operator@ifms.local` → `/interfaces` 진입 성공
- [ ] 기존 MANUAL 인터페이스 수동 실행 → ExecutionHistory에 `triggeredBy=MANUAL` 기록
- [ ] SSE 연결 녹색 유지 (Dashboard 오른쪽 상단 상태)
- [ ] OptimisticLock 충돌 다이얼로그(Day 5 테스트 1건 재실행)
- [ ] `/logout` 후 재로그인 정상

- [ ] **Step 8.2: CRON 자동 실행 수동 검증**

- [ ] `/interfaces`에서 등록 다이얼로그 열기 → scheduleType=CRON → cronExpression=`0 * * * * *` → 등록
- [ ] 2분 대기
- [ ] `/history?triggeredBy=SCHEDULER` 또는 필터 선택 → 새로운 SCHEDULER 행이 1~2건 존재 확인
- [ ] 행 상세 다이얼로그 → `Actor=SYSTEM` 확인
- [ ] 해당 인터페이스 비활성화(Status=INACTIVE) → 추가 2분 대기 → 새로운 SCHEDULER 기록 0건 확인

- [ ] **Step 8.3: Security 체크 3줄 (spec §8.3)**

- [ ] `git grep -n "ifms1234"` → `.env.example`·README 옵션 A 주석·DOCKER 주석 외 0건 (있다면 제거)
- [ ] `curl http://localhost:8080/api/monitor/dashboard | python -m json.tool | grep -i "payload"` → `requestPayload`/`responsePayload` 키 0건 (차트 API 신설 안 했으므로 기존 엔드포인트 회귀만 확인)
- [ ] `/history`에서 SCHEDULER 필터 → 동일 cron으로 2분 내 중복 행 0건 (advisory lock 정상 동작 증거)

- [ ] **Step 8.4: (선택) 옵션 B 풀 Docker 스모크**

Docker Desktop이 안정적이면:
Run: `docker compose --profile full up -d --build`
Run: `docker compose --profile full ps` → 3개 healthy

- [ ] <http://localhost:8090> 접속 → 로그인
- [ ] CRON 인터페이스 등록 → 2분 대기 → SCHEDULER 실행 확인

풀 Docker가 불안정하면 스킵하고 README에 한정 명시.

- [ ] **Step 8.5: 스크린샷 촬영 + 커밋**

- [ ] `docs/screenshots/day8-scheduler-history.png` — ExecutionHistory SCHEDULER 필터 결과
- [ ] `docs/screenshots/day8-docker-compose-ps.png` — (옵션 B 통과 시) healthy 3개 / (미통과 시) 옵션 A 3-명령어 터미널 캡처로 대체

```bash
mkdir -p docs/screenshots
# (수동: 스크린샷 배치)
git add docs/screenshots/day8-*.png
git commit -m "$(cat <<'EOF'
docs(day8): 수동 E2E 검증 스크린샷 2종 — SCHEDULER 필터 + compose ps

README §2-A에서 참조하는 시각 증거. 풀 Docker 스모크 또는 옵션 A 터미널 캡처.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Day 8 요약 문서 + backlog 갱신

**Files:**
- Create: `docs/DAY8-SUMMARY.md`
- Modify: `docs/backlog.md`

- [ ] **Step 9.1: DAY8-SUMMARY.md 작성**

`docs/DAY8-SUMMARY.md`:

```markdown
# Day 8 완료 요약 — 2026-04-22

> 제출 완성도 보강. 4-에이전트 회의 결정(옵션 B): CRON 스케줄러 실 구현 + Docker 전체 기동만.
> JWT·차트·성능 테스트는 후속으로 이월 (회의 근거는 spec §1).

## 1. 산출물

| 파일 | 상태 |
|---|---|
| [superpowers/specs/2026-04-22-day8-submission-polish-design.md](superpowers/specs/2026-04-22-day8-submission-polish-design.md) | spec |
| [superpowers/plans/2026-04-22-day8-submission-polish.md](superpowers/plans/2026-04-22-day8-submission-polish.md) | plan |
| `backend/.../InterfaceCronScheduler.java` + 테스트 5 | 신규 |
| `docker/backend.Dockerfile` · `docker/frontend.Dockerfile` · `docker/nginx.conf` | 신규 |
| `docker-compose.yml` profile=full | 수정 |
| `README.md` 옵션 B + §2-A | 수정 |
| `docs/screenshots/day8-*.png` × 2 | 신규 |

## 2. 행동 변화

- `scheduleType=CRON, status=ACTIVE` 인터페이스가 `cronExpression`대로 자동 실행 → `ExecutionLog.triggeredBy=SCHEDULER` 신규 기록 경로 가동
- `docker compose --profile full up -d`로 postgres+backend+frontend 전체 컨테이너 기동
- 기본 기동(`docker compose up -d`)은 postgres만 — Day 7 개발 워크플로 보존

## 3. 이월 (Day 9+ / 운영 전환)

- ADR-008 JWT 미채택 근거 문서
- 대시보드 sparkline (회의 대안)
- JMH 벤치 확장
- 스케줄러 catch-up (재기동 시 누락 발화 소급)

## 4. 회의 근거

spec §1 참조. @Security/@DBA/@DevilsAdvocate 세 에이전트 모두 JWT 반대 수렴.
```

- [ ] **Step 9.2: backlog.md 갱신**

`docs/backlog.md`의 "Day 7 (통합 테스트 · 문서 정리 · 제출) — 진행 결과" 섹션 **다음**에 "Day 8 (제출 완성도) — 진행 결과" 섹션을 삽입:

```markdown
## Day 8 (제출 완성도 보강) — 진행 결과

### 자동 완료

| 항목 | 상태 |
|---|---|
| `InterfaceCronScheduler` + 단위 테스트 5 | ✅ Day 8 |
| `docker compose --profile full` backend/frontend 컨테이너화 | ✅ Day 8 |
| README 옵션 B + §2-A CRON 검증 절 | ✅ Day 8 |
| `.env.example`에 `IFMS_ADVISORY_LOCK_RETRY_NAMESPACE` 명시 | ✅ Day 8 |

### 사용자 협조 단계

| 항목 | 출처 |
|---|---|
| 수동 E2E 5 + CRON 2 + Security 체크 3 | plan Task 8 |

### Day 8 미진행 — 운영/후속 이관

| 항목 | 사유 |
|---|---|
| JWT 전환 | 4-에이전트 회의 반대 수렴(spec §1). ADR-008 후속 |
| 대시보드 차트/sparkline | 회의 이월 결정 |
| JMH 확장 — ExecutionLog 조회 벤치 | 회의 이월 결정 |
| 스케줄러 catch-up (재기동 누락 발화 소급) | 운영 전환 범위 |
| ShedLock / 분산 스케줄러 | 단일 인스턴스 프로토타입 — advisory lock+uk_log_running으로 충분 |
```

- [ ] **Step 9.3: Commit**

```bash
git add docs/DAY8-SUMMARY.md docs/backlog.md
git commit -m "$(cat <<'EOF'
docs(day8): SUMMARY + backlog Day 8 섹션 (CRON 스케줄러 + Docker 전체 기동 완료)

JWT/차트/성능은 회의 결정으로 후속 이관 — backlog에 사유 명시.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## 최종 체크

- [ ] **Final: 전체 빌드 게이트**

Run: `.\gradlew.bat clean test bootJar --no-daemon --console=plain`
Expected: `BUILD SUCCESSFUL`, 모든 테스트 PASS (Day 7 + Day 8 신규 5), `backend/build/libs/*-SNAPSHOT.jar` 생성.

Run: `cd frontend; npm run build`
Expected: `dist/` 생성, vue-tsc PASS.

- [ ] **Final: 커밋 이력 확인**

Run: `git log --oneline -15`
Expected: Day 8 커밋 9개 (Task 1·2·3·4·5·6·7·8-스크린샷·9) + spec 커밋 1개(이미 완료).

- [ ] **Final: Day 8 커밋 이력을 DAY8-SUMMARY.md §1에 역참조**

누락 시 본문에 커밋 해시 추가 후 self-amend 대신 **신규 docs(day8) 커밋**으로 반영 (프로젝트 컨벤션: amend 금지).

---

## Self-Review 요약

- Spec §3.1 목표 5개 전부 Task로 매핑: 목표1→Task 4~6, 목표2→Task 1~3, 목표3→Task 3 설계, 목표4→Task 7·8-스크린샷, 목표5→Task 6(env) + Task 8.3(검증)
- Spec §6 구현 단위 15개 전부 Task 매핑됨 (Task 6.1.3 `findAllActiveCron()`만 Task 3-B 내 Specification 사용으로 대체 — 별도 Repository 메서드 추가 불필요하므로 플랜 단순화)
- 타입 일관성: `markScheduled(LocalDateTime)`·`getLastScheduledAt(): LocalDateTime`·`CronExpression.next(ZonedDateTime): ZonedDateTime` 시그니처 모두 Task 1·Task 3-B에서 교차 검증
- Placeholder 0건 — 모든 코드 블록·명령·기대값 구체값
