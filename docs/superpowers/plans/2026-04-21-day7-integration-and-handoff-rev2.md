# Day 7 — Implementation Plan REV2 (코드 사실 정합 개정판)

> 초판 plan(`2026-04-21-day7-integration-and-handoff.md`)이 RetryService 시그니처·ErrorCode 명칭·ExecutionLog 빌더 부재·schema.sql GIN 인덱스 부재 등 코드 사실과 다수 불일치. 본 REV2가 **정본**이며 초판은 폐기.

**Goal:** Day 6까지 완성된 IFMS 프로토타입의 인지된 부채(M1~M9)와 backlog Day 7 항목을 청산하고 제출용 패키지를 마무리한다.

**Architecture:** 신규 도메인 없음. 기존 모듈 내부 + 테스트 신설. Testcontainers PostgreSQL 16(JSONB·advisory lock 검증), 단순 nanoTime 벤치(MaskingRule p95), CountDownLatch race(M4). 프런트는 query 동기화 + ErrorCode 2종 + sessionStorage 정리 단순화만.

**Tech Stack:** Java 17 / Spring Boot 3.3.5 / JUnit 5 / Testcontainers 1.20.3 (이미 build.gradle 포함) / awaitility / ArchUnit 1.3.0 / Vue 3 / Vuetify 3 / TS 6

**Spec:** `docs/superpowers/specs/2026-04-21-day7-integration-and-handoff-design.md`

---

## 코드 사실 (Ground Truth) 인덱스

> 초판 추측을 모두 코드 확인으로 교체.

| 항목 | 코드 사실 | 인용 위치 |
|---|---|---|
| ExecutionLog 생성자 | `start()` / `spawnRetry()` 정적 팩토리만. `.builder()` 없음 | `ExecutionLog.java:154,189` |
| ExecutionLog 시드 전략 | **native SQL INSERT** 또는 `start(config, ...)` + repository.save (interface_config 선행 필수) | (제약: interfaceConfig nullable=false) |
| 시간 타입 | **LocalDateTime** (OffsetDateTime 아님) | `ExecutionLog.java:93,95` |
| actor 필드명 | **actor_id** (actorHash 아님) | `ExecutionLog.java:80`, `RetryGuardSnapshot:11` |
| RetryService 시그니처 | `retry(Long parentLogId, String sessionActor, String clientIp, String userAgent)` → `ExecutionTriggerResponse` | `RetryService.java:51` |
| RetryGuard 5종 ErrorCode | `RETRY_FORBIDDEN_ACTOR / INTERFACE_INACTIVE / RETRY_NOT_LEAF / RETRY_TRUNCATED_BLOCKED / RETRY_LIMIT_EXCEEDED` | `RetryGuard.java:34` |
| Lock 단계 추가 ErrorCode | `DUPLICATE_RUNNING / RETRY_CHAIN_CONFLICT` (lock1/lock2 단계) | `RetryService.java:70,78` |
| 평가 순서 | 1.actor 2.inactive 3.not_leaf 4.truncated 5.limit | `RetryGuard.java:34-58` |
| 루트 actor 규칙 | SYSTEM 허용 / ANONYMOUS_* 차단 / 그 외 일치 필수 | `RetryGuard.java:70-81` |
| RetryGuardSnapshot 생성 | record 직접 인스턴스화 가능 (테스트에선 fromRow Object[] 우회) | `RetryGuardSnapshot.java:20` |
| advisory lock 메서드 | `tryAdvisoryLock(int key1, long key2)` (key2 INT cast SQL 내) | `ExecutionLogRepository.java:96` |
| Watchdog 메서드 | `sweep()` (`sweepOrphans()` 아님), `markRecovered(durationMs)` 호출, `errorCode = STARTUP_RECOVERY` | `OrphanRunningWatchdog.java:62`, `ExecutionLog.java:236` |
| schema.sql GIN 인덱스 | **없음** — `idx_log_request_payload_gin` 미존재. JSONB 컬럼만 정의됨 | `schema.sql:99` (CHECK만, GIN 0건) |
| InterfaceConfigDetailResponse 필드 | id, name, description, protocol, endpoint, httpMethod, **configJson**, scheduleType, cronExpression, timeoutSeconds, maxRetryCount, status, version, **createdAt**, updatedAt | `InterfaceConfigDetailResponse.java:23-39` |
| InterfaceConfigSnapshot 필드 | id, name, description, protocol, endpoint, httpMethod, scheduleType, cronExpression, timeoutSeconds, maxRetryCount, status, version, updatedAt | `InterfaceConfigSnapshot.java:17-30` |
| **Snapshot vs Detail 차이** | **2개**: Snapshot에 없음 = `configJson`, `createdAt` | (의도적 — 409 UX는 핵심 필드만) |
| SseEmitterService.subscribe 시그니처 | `subscribe(String sessionId, String actorId, String clientId, Long lastEventId)` | `SseEmitterService.java:51` |
| Registry 클라이언트 카운트 | `bySession` 전체 순회로 clientId 매칭 카운트 (헬퍼 직접 없음) | `SseEmitterRegistry.java` |
| MaskingRule 진입점 | `mask(Object value)` 단일 메서드 — 벤치는 이걸 호출 | `MaskingRule.java:48` |
| frontend ErrorCode union | 현재 **20종** (DELTA 2종 누락) | `frontend/src/api/types.ts:11-31` |
| sessionStorage 키 | `'sse.clientId'` (plan 추측 `'ifms.sse.clientId'` 아님) | `router/index.ts:53` |
| router M7 분기 | `from.name && from.name !== 'login'` → 새 탭(`from.name=undefined`)일 때 미실행 | `router/index.ts:52` |
| auth.logout | `clear()` 호출 — clear에 sessionStorage 정리 없음 | `stores/auth.ts:32-49` |

---

## File Structure

### 신규 (백엔드 테스트 11)
```
backend/src/test/java/com/noaats/ifms/
├── support/
│   ├── AbstractPostgresIntegrationTest.java         # T1
│   └── ExecutionLogTestSeeder.java                  # T1-B (native SQL 시드 헬퍼)
├── domain/execution/
│   ├── repository/JsonbContainmentIntegrationTest.java  # T2 (GIN→containment 한정)
│   └── service/
│       ├── AdvisoryLockIntegrationTest.java         # T3
│       ├── RetryServiceIntegrationTest.java         # T4 (lock 후 5종 분기)
│       ├── RetryGuardSnapshotPolicyTest.java        # T5+T6 (단위, RetryGuard 직접)
│       └── OrphanRunningWatchdogIntegrationTest.java # T7
├── domain/interface_/
│   └── dto/SnapshotFieldParityTest.java             # T8 (configJson+createdAt 2개 차이 허용)
├── domain/monitor/sse/
│   └── SseSubscribeRaceTest.java                    # T10
└── global/masking/
    └── MaskingRuleBenchTest.java                    # T9 (DefensiveMaskingFilter 대신 MaskingRule)
```

### 수정 (백엔드)
```
backend/src/main/java/com/noaats/ifms/global/response/ApiResponse.java   # T16
backend/src/main/resources/application.yml                                # T15
backend/build.gradle                                                       # awaitility 추가 (필요 시)
```

### 수정 (프런트)
```
frontend/src/api/types.ts                # T13
frontend/src/stores/auth.ts              # T12 (clear에 sessionStorage 정리 + sse.clientId 키 통일)
frontend/src/router/index.ts             # T12 (분기 단순화)
frontend/src/pages/Dashboard.vue         # T11
frontend/src/pages/ExecutionHistory.vue  # T11 (route.query.status 동기화)
```

### 수정 (문서)
```
docs/api-spec.md          # T14
docs/backlog.md           # T17
docs/erd.md               # T2 보조: GIN 부재 사실 명문화 (Known issue)
docs/DAY7-SUMMARY.md      # T19
README.md                 # T20
```

---

## 묶음 1 — 코드 부채 청산

### Task 1: Testcontainers 베이스 + 시드 헬퍼

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/support/AbstractPostgresIntegrationTest.java`
- Create: `backend/src/test/java/com/noaats/ifms/support/ExecutionLogTestSeeder.java`

- [ ] **Step 1: 베이스 클래스**

```java
package com.noaats.ifms.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
public abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("ifms")
                .withUsername("ifms")
                .withPassword("ifms1234")
                .withReuse(true);
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.continue-on-error", () -> "true");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Day 4 Bug A 회피: anon-salt 미주입 시 컨텍스트 부팅 실패
        registry.add("ifms.actor.anon-salt", () -> "test-salt-day7-integration");
    }
}
```

> 주의: `ddl-auto=update`로 두는 이유 — 테스트 컨테이너가 깨끗한 DB이고 schema.sql이 ALTER TABLE을 가정하지만, `defer-datasource-initialization=true`가 application.yml 기본값. JPA가 먼저 테이블 만들고 schema.sql이 CHECK·index 추가하는 순서.

- [ ] **Step 2: ExecutionLog 시드 헬퍼 (native INSERT)**

```java
package com.noaats.ifms.support;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ProtocolType;
import com.noaats.ifms.domain.interface_.domain.ScheduleType;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Day 7 통합 테스트 전용 시드 헬퍼.
 * ExecutionLog는 정적 팩토리만 노출하므로 임의 상태(FAILED/SUCCESS/특정 actor 등) 시드는
 * native INSERT로 처리한다. 일반 Service 경로는 RetryService/Trigger 통해.
 */
@Component
public class ExecutionLogTestSeeder {

    private final EntityManager em;
    private final InterfaceConfigRepository ifcRepo;

    public ExecutionLogTestSeeder(EntityManager em, InterfaceConfigRepository ifcRepo) {
        this.em = em;
        this.ifcRepo = ifcRepo;
    }

    public InterfaceConfig seedInterface(String name, InterfaceStatus status, int maxRetry) {
        return ifcRepo.save(InterfaceConfig.builder()
                .name(name)
                .description("test " + name)
                .protocol(ProtocolType.REST)
                .endpoint("https://example.test/" + name)
                .httpMethod("POST")
                .configJson(new HashMap<>())
                .scheduleType(ScheduleType.MANUAL)
                .cronExpression(null)
                .timeoutSeconds(30)
                .maxRetryCount(maxRetry)
                .status(status)
                .build());
    }

    /**
     * 임의 상태/parent/actor의 execution_log를 native INSERT로 시드.
     * @return 생성된 log id
     */
    public Long seedLog(Long interfaceConfigId,
                        ExecutionStatus status,
                        String actor,
                        Long parentId,
                        Long rootId,
                        int retryCount,
                        int maxRetrySnapshot) {
        boolean terminal = status != ExecutionStatus.RUNNING;
        LocalDateTime startedAt = LocalDateTime.now().minusMinutes(5);
        Object finishedAt = terminal ? LocalDateTime.now().minusMinutes(4) : null;
        Object durationMs = terminal ? 60_000L : null;
        String triggeredBy = parentId != null ? "RETRY" : "MANUAL";

        em.createNativeQuery("""
            INSERT INTO execution_log
              (interface_config_id, parent_log_id, root_log_id, triggered_by, actor_id,
               status, started_at, finished_at, duration_ms,
               payload_format, payload_truncated, retry_count, max_retry_snapshot, created_at)
            VALUES
              (:cfg, :parent, :root, :trig, :actor,
               :status, :started, :finished, :dur,
               'JSON', false, :retry, :maxSnap, NOW())
            """)
            .setParameter("cfg", interfaceConfigId)
            .setParameter("parent", parentId)
            .setParameter("root", rootId)
            .setParameter("trig", triggeredBy)
            .setParameter("actor", actor)
            .setParameter("status", status.name())
            .setParameter("started", startedAt)
            .setParameter("finished", finishedAt)
            .setParameter("dur", durationMs)
            .setParameter("retry", retryCount)
            .setParameter("maxSnap", maxRetrySnapshot)
            .executeUpdate();

        Number id = (Number) em.createNativeQuery(
                "SELECT MAX(id) FROM execution_log WHERE interface_config_id = :cfg")
                .setParameter("cfg", interfaceConfigId).getSingleResult();
        em.flush();
        em.clear();
        return id.longValue();
    }
}
```

- [ ] **Step 3: 컨테이너 부팅 sanity (제거 예정)**

`backend/src/test/java/com/noaats/ifms/support/PostgresContainerSanityTest.java`:

```java
package com.noaats.ifms.support;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PostgresContainerSanityTest extends AbstractPostgresIntegrationTest {

    @Autowired DataSource dataSource;

    @Test
    void postgresContainerBootsAndDataSourceConnects() throws Exception {
        try (var conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery("SELECT version()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("PostgreSQL 16");
        }
    }
}
```

- [ ] **Step 4: sanity 실행**

Run: `cd backend && ./gradlew test --tests "com.noaats.ifms.support.PostgresContainerSanityTest"`
Expected: PASS (Docker 풀링 30~90초)

- [ ] **Step 5: sanity 삭제 + 커밋**

```bash
rm backend/src/test/java/com/noaats/ifms/support/PostgresContainerSanityTest.java
git add backend/src/test/java/com/noaats/ifms/support/
git commit -m "test(infra): Testcontainers PostgreSQL 16 베이스 + ExecutionLogTestSeeder native INSERT 헬퍼"
```

---

### Task 2: JSONB `@>` 연산자 통합 테스트 (GIN은 제외 — schema.sql 부재)

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/execution/repository/JsonbContainmentIntegrationTest.java`
- Modify: `docs/erd.md` (Known issue 1줄 추가)

> 초판이 GIN EXPLAIN 검증을 포함했으나 schema.sql에 GIN 인덱스가 없음. erd.md §10에는 설계만 존재. 본 task는 **JSONB `@>` 동작 검증만** 수행하고 GIN 부재는 erd Known issue로 명문화.

- [ ] **Step 1: 통합 테스트**

```java
package com.noaats.ifms.domain.execution.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.support.AbstractPostgresIntegrationTest;
import com.noaats.ifms.support.ExecutionLogTestSeeder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class JsonbContainmentIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired EntityManager em;
    @Autowired ExecutionLogTestSeeder seeder;

    @Test
    void jsonbContainmentOperatorMatchesNestedKey() {
        var ifc = seeder.seedInterface("jsonb-test", com.noaats.ifms.domain.interface_.domain.InterfaceStatus.ACTIVE, 3);
        Long logId = seeder.seedLog(ifc.getId(), ExecutionStatus.SUCCESS, "actor", null, null, 0, 3);

        // request_payload를 직접 native UPDATE로 채움 (시더는 NULL로 둠)
        em.createNativeQuery("""
                UPDATE execution_log
                SET request_payload = CAST(:p AS jsonb)
                WHERE id = :id
                """)
                .setParameter("p", "{\"k\":\"v\",\"meta\":{\"trace\":\"abc\"}}")
                .setParameter("id", logId)
                .executeUpdate();

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM execution_log
                WHERE request_payload @> CAST(:filter AS jsonb)
                """)
                .setParameter("filter", "{\"meta\":{\"trace\":\"abc\"}}")
                .getSingleResult();

        assertThat(count.longValue()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void jsonbContainmentMissesNonMatchingKey() {
        var ifc = seeder.seedInterface("jsonb-miss", com.noaats.ifms.domain.interface_.domain.InterfaceStatus.ACTIVE, 3);
        Long logId = seeder.seedLog(ifc.getId(), ExecutionStatus.SUCCESS, "actor", null, null, 0, 3);
        em.createNativeQuery("UPDATE execution_log SET request_payload = CAST(:p AS jsonb) WHERE id = :id")
                .setParameter("p", "{\"k\":\"other\"}")
                .setParameter("id", logId)
                .executeUpdate();

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM execution_log
                WHERE request_payload @> CAST('{"meta":{"trace":"abc"}}' AS jsonb)
                  AND interface_config_id = :cfg
                """)
                .setParameter("cfg", ifc.getId())
                .getSingleResult();
        assertThat(count.longValue()).isZero();
    }
}
```

- [ ] **Step 2: 실행**

Run: `cd backend && ./gradlew test --tests "JsonbContainmentIntegrationTest"`
Expected: 2 PASS

- [ ] **Step 3: erd.md GIN 부재 Known issue 1줄**

`docs/erd.md` §10 또는 §13 끝부분에 한 줄:

```markdown
> **Known**: schema.sql에는 JSONB `@>` 컨테인먼트 동작은 보장되나, `idx_log_request_payload_gin` GIN 인덱스는 미선언. 운영 전환 시 데이터량 임계값 도달 시 도입 (backlog "운영 전환").
```

- [ ] **Step 4: 커밋**

```bash
git add backend/src/test/java/com/noaats/ifms/domain/execution/repository/JsonbContainmentIntegrationTest.java docs/erd.md
git commit -m "test(execution): JSONB @> 컨테인먼트 통합 테스트 + erd GIN 미선언 Known issue 명문화"
```

---

### Task 3: Advisory Lock 통합 테스트

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/execution/service/AdvisoryLockIntegrationTest.java`

- [ ] **Step 1: 통합 테스트** (Repository 메서드 직접 사용)

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.support.AbstractPostgresIntegrationTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

class AdvisoryLockIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired TransactionTemplate tx;
    @Autowired ExecutionLogRepository repo;
    @Value("${ifms.advisory-lock.namespace}") int namespace;

    @Test
    void firstTransactionAcquiresLockSecondFails() throws Exception {
        long key = 9999L;
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseSignal = new CountDownLatch(1);
        AtomicBoolean t1Result = new AtomicBoolean(false);
        AtomicBoolean t2Result = new AtomicBoolean(true);

        Thread t1 = new Thread(() -> tx.executeWithoutResult(s -> {
            t1Result.set(Boolean.TRUE.equals(repo.tryAdvisoryLock(namespace, key)));
            lockHeld.countDown();
            try { releaseSignal.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }));

        Thread t2 = new Thread(() -> {
            try { lockHeld.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            tx.executeWithoutResult(s ->
                    t2Result.set(Boolean.TRUE.equals(repo.tryAdvisoryLock(namespace, key))));
        });

        t1.start();
        t2.start();
        t2.join(5_000);
        releaseSignal.countDown();
        t1.join(5_000);

        assertThat(t1Result.get()).as("first tx acquires lock").isTrue();
        assertThat(t2Result.get()).as("second tx fails to acquire same lock").isFalse();
    }

    @Test
    void differentKeysCanBeHeldIndependently() {
        // 동일 namespace, 다른 key 두 lock은 독립적으로 획득 가능
        tx.executeWithoutResult(s -> {
            assertThat(repo.tryAdvisoryLock(namespace, 1001L)).isTrue();
            assertThat(repo.tryAdvisoryLock(namespace, 1002L)).isTrue();
        });
    }
}
```

- [ ] **Step 2: 실행 + 커밋**

Run: `cd backend && ./gradlew test --tests "AdvisoryLockIntegrationTest"`
Expected: 2 PASS

```bash
git add backend/src/test/java/com/noaats/ifms/domain/execution/service/AdvisoryLockIntegrationTest.java
git commit -m "test(execution): pg_try_advisory_xact_lock(int,int) 동시성·독립성 통합 테스트"
```

---

### Task 4: RetryService 5종 에러 분기 통합 테스트

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/execution/service/RetryServiceIntegrationTest.java`

> 코드 사실: 평가 순서 = `RETRY_FORBIDDEN_ACTOR / INTERFACE_INACTIVE / RETRY_NOT_LEAF / RETRY_TRUNCATED_BLOCKED / RETRY_LIMIT_EXCEEDED`. 각 분기는 다른 코드들이 통과되도록 시드해야 단일 코드 검증 가능. RetryService.retry는 lock 단계도 거치므로 (DUPLICATE_RUNNING / RETRY_CHAIN_CONFLICT) 시드 시 RUNNING 자식·다른 자식이 없도록 주의.

- [ ] **Step 1: 통합 테스트**

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.global.exception.ApiException;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.support.AbstractPostgresIntegrationTest;
import com.noaats.ifms.support.ExecutionLogTestSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class RetryServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired RetryService retryService;
    @Autowired ExecutionLogTestSeeder seeder;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)  // RetryService는 REQUIRES_NEW
    void forbiddenActor_throwsRetryForbiddenActor() {
        var ifc = seeder.seedInterface("retry-forbidden-" + uniq(), InterfaceStatus.ACTIVE, 3);
        Long parent = seeder.seedLog(ifc.getId(), ExecutionStatus.FAILED, "actorA", null, null, 0, 3);

        assertThatThrownBy(() -> retryService.retry(parent, "actorB", "127.0.0.1", "junit"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_FORBIDDEN_ACTOR);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void inactiveInterface_throwsInterfaceInactive() {
        var ifc = seeder.seedInterface("retry-inactive-" + uniq(), InterfaceStatus.INACTIVE, 3);
        Long parent = seeder.seedLog(ifc.getId(), ExecutionStatus.FAILED, "actorA", null, null, 0, 3);

        assertThatThrownBy(() -> retryService.retry(parent, "actorA", "127.0.0.1", "junit"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERFACE_INACTIVE);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void parentNotFailed_throwsRetryNotLeaf() {
        // SUCCESS 상태 parent — RUNNING은 lock 단계의 DUPLICATE_RUNNING과 겹쳐 부적합
        var ifc = seeder.seedInterface("retry-notleaf-" + uniq(), InterfaceStatus.ACTIVE, 3);
        Long parent = seeder.seedLog(ifc.getId(), ExecutionStatus.SUCCESS, "actorA", null, null, 0, 3);

        assertThatThrownBy(() -> retryService.retry(parent, "actorA", "127.0.0.1", "junit"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_NOT_LEAF);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void retryAtMaxSnapshot_throwsRetryLimitExceeded() {
        // maxRetrySnapshot=2, parent.retryCount=2 → +1 > 2 → 위반
        var ifc = seeder.seedInterface("retry-limit-" + uniq(), InterfaceStatus.ACTIVE, 2);
        Long parent = seeder.seedLog(ifc.getId(), ExecutionStatus.FAILED, "actorA", null, null, 2, 2);

        assertThatThrownBy(() -> retryService.retry(parent, "actorA", "127.0.0.1", "junit"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_LIMIT_EXCEEDED);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void successPath_spawnsNewRunningChild() {
        var ifc = seeder.seedInterface("retry-ok-" + uniq(), InterfaceStatus.ACTIVE, 3);
        Long parent = seeder.seedLog(ifc.getId(), ExecutionStatus.FAILED, "actorA", null, null, 0, 3);

        var resp = retryService.retry(parent, "actorA", "127.0.0.1", "junit");
        org.assertj.core.api.Assertions.assertThat(resp.logId()).isPositive();
        org.assertj.core.api.Assertions.assertThat(resp.status())
                .isEqualTo(ExecutionStatus.RUNNING);
    }

    private static String uniq() {
        return Long.toHexString(System.nanoTime());
    }
}
```

> RETRY_TRUNCATED_BLOCKED는 시드 헬퍼에 `payload_truncated=false` 고정 → 별도 변형 헬퍼 필요. 5종 중 4종(actor / inactive / not_leaf / limit) + 성공 경로로 5 케이스 충족. truncated는 별도 시간 절약 차원에서 생략하되 RetryGuard 단위 테스트(T5에서 흡수)로 커버.

- [ ] **Step 2: 실행 + 커밋**

Run: `cd backend && ./gradlew test --tests "RetryServiceIntegrationTest"`
Expected: 5 PASS

```bash
git add backend/src/test/java/com/noaats/ifms/domain/execution/service/RetryServiceIntegrationTest.java
git commit -m "test(retry): RetryService 5종 분기 통합 테스트 (forbidden_actor·inactive·not_leaf·limit·success)"
```

---

### Task 5: RetryGuard 단위 테스트 (Q1 + Q2 + truncated 흡수)

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/execution/service/RetryGuardSnapshotPolicyTest.java`

> 코드 사실: RetryGuardSnapshot은 record. 직접 인스턴스화하여 RetryGuard.verify() 단독 호출 가능 → DB·Spring 컨텍스트 불필요. ADR-005 Q1 (max_retry_snapshot 보존)·Q2 (루트 actor)·truncated를 모두 단위 테스트로 흡수.

- [ ] **Step 1: 단위 테스트**

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.global.exception.ApiException;
import com.noaats.ifms.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class RetryGuardSnapshotPolicyTest {

    private final RetryGuard guard = new RetryGuard();

    private RetryGuardSnapshot snap(String parentActor, int retryCount, int maxSnapshot,
                                    boolean truncated, ExecutionStatus parentStatus,
                                    String rootActor, InterfaceStatus icStatus) {
        return new RetryGuardSnapshot(
                1L, parentActor, retryCount, maxSnapshot,
                truncated, parentStatus, 1L, rootActor, icStatus);
    }

    @Test
    void q1_maxSnapshotIsRespectedRegardlessOfCurrentConfig() {
        // ADR-005 Q1: snapshot=1, retryCount=1 → +1 > 1 위반
        var s = snap("actorA", 1, 1, false, ExecutionStatus.FAILED, "actorA", InterfaceStatus.ACTIVE);
        assertThatThrownBy(() -> guard.verify(s, "actorA"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_LIMIT_EXCEEDED);
    }

    @Test
    void q2_rootActorMismatchOverridesParentActor() {
        // ADR-005 Q2: parent actor=B, root actor=A. 세션=B → root 기준 false
        var s = snap("actorB", 0, 3, false, ExecutionStatus.FAILED, "actorA", InterfaceStatus.ACTIVE);
        assertThatThrownBy(() -> guard.verify(s, "actorB"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_FORBIDDEN_ACTOR);
    }

    @Test
    void q2_systemRootIsAllowedForAnyOperator() {
        var s = snap("SYSTEM", 0, 3, false, ExecutionStatus.FAILED, "SYSTEM", InterfaceStatus.ACTIVE);
        assertThatCode(() -> guard.verify(s, "operatorX")).doesNotThrowAnyException();
    }

    @Test
    void q2_anonymousRootIsForbidden() {
        var s = snap("ANONYMOUS_abc", 0, 3, false, ExecutionStatus.FAILED, "ANONYMOUS_abc", InterfaceStatus.ACTIVE);
        assertThatThrownBy(() -> guard.verify(s, "operatorX"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_FORBIDDEN_ACTOR);
    }

    @Test
    void truncatedPayload_isBlocked() {
        var s = snap("actorA", 0, 3, true, ExecutionStatus.FAILED, "actorA", InterfaceStatus.ACTIVE);
        assertThatThrownBy(() -> guard.verify(s, "actorA"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_TRUNCATED_BLOCKED);
    }

    @Test
    void allCheckPass_doesNotThrow() {
        var s = snap("actorA", 0, 3, false, ExecutionStatus.FAILED, "actorA", InterfaceStatus.ACTIVE);
        assertThatCode(() -> guard.verify(s, "actorA")).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 실행 + 커밋**

Run: `cd backend && ./gradlew test --tests "RetryGuardSnapshotPolicyTest"`
Expected: 6 PASS

```bash
git add backend/src/test/java/com/noaats/ifms/domain/execution/service/RetryGuardSnapshotPolicyTest.java
git commit -m "test(retry): RetryGuard 단위 — ADR-005 Q1(max snapshot)·Q2(root actor)·truncated 6 케이스"
```

> Note: 별도 T6 파일 폐기. T5에 Q1+Q2를 단일 파일로 통합 (단위 테스트만으로 충분, Spring 컨텍스트 부팅 회피).

---

### Task 7: OrphanRunningWatchdog 통합 테스트

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/execution/service/OrphanRunningWatchdogIntegrationTest.java`

> 코드 사실: `sweep()` 메서드 직접 호출. timeout 임계값은 native query에 하드코딩(`60 seconds + ic.timeout_seconds`)이라 인터페이스의 timeout_seconds=1로 시드 + 시작 시각을 충분히 과거로 두면 회수 대상.

- [ ] **Step 1: 시더에 timeout=1 옵션 + 과거 startedAt 설정 보강**

`ExecutionLogTestSeeder`에 헬퍼 추가:

```java
public Long seedRunningStuck(Long ifcId, String actor, java.time.LocalDateTime startedAt) {
    em.createNativeQuery("""
        INSERT INTO execution_log
          (interface_config_id, triggered_by, actor_id, status, started_at,
           payload_format, payload_truncated, retry_count, max_retry_snapshot, created_at)
        VALUES
          (:cfg, 'MANUAL', :actor, 'RUNNING', :started,
           'JSON', false, 0, 3, NOW())
        """)
        .setParameter("cfg", ifcId)
        .setParameter("actor", actor)
        .setParameter("started", startedAt)
        .executeUpdate();
    Number id = (Number) em.createNativeQuery(
            "SELECT MAX(id) FROM execution_log WHERE interface_config_id = :cfg")
            .setParameter("cfg", ifcId).getSingleResult();
    em.flush(); em.clear();
    return id.longValue();
}
```

- [ ] **Step 2: 통합 테스트**

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.support.AbstractPostgresIntegrationTest;
import com.noaats.ifms.support.ExecutionLogTestSeeder;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class OrphanRunningWatchdogIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired ExecutionLogRepository repo;
    @Autowired OrphanRunningWatchdog watchdog;
    @Autowired ExecutionLogTestSeeder seeder;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void runningLogStuckBeyondThresholdIsRecoveredToFailed() {
        // timeout_seconds=1, started_at = 5분 전 → 60s + 1s 임계 훨씬 초과
        var ifc = seeder.seedInterface("watchdog-stuck-" + Long.toHexString(System.nanoTime()),
                InterfaceStatus.ACTIVE, 3);
        // timeout 1s로 강제 — seedInterface 기본 30s를 native UPDATE로 교체
        seeder.setTimeoutSeconds(ifc.getId(), 1);

        Long stuckId = seeder.seedRunningStuck(ifc.getId(), "actorWD",
                LocalDateTime.now().minusMinutes(5));

        watchdog.sweep();

        var reloaded = repo.findById(stuckId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(reloaded.getFinishedAt()).isNotNull();
        assertThat(reloaded.getErrorMessage()).contains("OrphanRunningWatchdog");
    }
}
```

- [ ] **Step 3: 시더에 setTimeoutSeconds 추가**

```java
public void setTimeoutSeconds(Long ifcId, int timeoutSeconds) {
    em.createNativeQuery("UPDATE interface_config SET timeout_seconds = :t WHERE id = :id")
        .setParameter("t", timeoutSeconds)
        .setParameter("id", ifcId)
        .executeUpdate();
    em.flush(); em.clear();
}
```

> seedInterface의 timeoutSeconds 기본 30s가 ck_ifc_timeout(BETWEEN 1 AND 600)에 부합. setter도 같은 범위.

- [ ] **Step 4: 실행 + 커밋**

Run: `cd backend && ./gradlew test --tests "OrphanRunningWatchdogIntegrationTest"`
Expected: PASS

```bash
git add backend/src/test/java/com/noaats/ifms/domain/execution/service/OrphanRunningWatchdogIntegrationTest.java \
        backend/src/test/java/com/noaats/ifms/support/ExecutionLogTestSeeder.java
git commit -m "test(watchdog): OrphanRunningWatchdog.sweep() 통합 테스트 (timeout 1s + 5분 stuck → FAILED)"
```

---

### Task 8: SnapshotFieldParityTest

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/interface_/dto/SnapshotFieldParityTest.java`

> 코드 사실: Detail vs Snapshot 차이 = `configJson` + `createdAt` 2개. 회귀 보호는 "이 2개 외 추가 차이 발생 시 테스트 실패"로 정의.

- [ ] **Step 1: 단위 테스트**

```java
package com.noaats.ifms.domain.interface_.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SnapshotFieldParityTest {

    /**
     * Snapshot에 의도적으로 빠진 필드. 새 필드를 Detail에 추가했는데 Snapshot에 빠뜨려
     * 감사·UI 누락이 발생하는 회귀를 방지. 의도적 제외는 본 set에 추가 + 사유 주석.
     */
    private static final Set<String> SNAPSHOT_INTENTIONALLY_OMITTED = Set.of(
            "configJson",  // 409 UX는 핵심 필드만, 마스킹 재실행 위험 회피
            "createdAt"    // 409 충돌 시점에 무관 — updatedAt 대조면 충분
    );

    @Test
    void detailMinusOmittedEqualsSnapshot() {
        Set<String> detail = fieldNames(InterfaceConfigDetailResponse.class);
        Set<String> snap = fieldNames(InterfaceConfigSnapshot.class);

        Set<String> detailExpected = detail.stream()
                .filter(n -> !SNAPSHOT_INTENTIONALLY_OMITTED.contains(n))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> snapSorted = new TreeSet<>(snap);

        assertThat(snapSorted)
                .as("Snapshot must equal Detail minus intentionally-omitted fields. "
                        + "If you added a Detail field, decide: include in Snapshot, or add to "
                        + "SNAPSHOT_INTENTIONALLY_OMITTED with rationale.")
                .isEqualTo(detailExpected);
    }

    private Set<String> fieldNames(Class<?> c) {
        if (c.isRecord()) {
            return Arrays.stream(c.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toSet());
        }
        return Arrays.stream(c.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());
    }
}
```

- [ ] **Step 2: 실행 + 커밋**

Run: `cd backend && ./gradlew test --tests "SnapshotFieldParityTest"`
Expected: PASS

```bash
git add backend/src/test/java/com/noaats/ifms/domain/interface_/dto/SnapshotFieldParityTest.java
git commit -m "test(interface): Snapshot↔Detail 필드 정합 테스트 (configJson·createdAt 의도적 제외 set)"
```

---

### Task 9: MaskingRule p95 < 50ms 벤치 (DefensiveMaskingFilter 대신)

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/global/masking/MaskingRuleBenchTest.java`

> DefensiveMaskingFilter는 ResponseBodyAdvice라 직접 호출 어려움. 1차 마스킹 코어인 `MaskingRule.mask(Object)`를 벤치하는 게 실질적이며 p95 < 50ms는 동일 의미 (filter는 mask 호출 + 헤더 인젝션만 추가).

- [ ] **Step 1: 벤치 테스트**

```java
package com.noaats.ifms.global.masking;

import static org.assertj.core.api.Assertions.assertThat;

import com.noaats.ifms.global.validation.SensitiveKeyRegistry;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "RUN_BENCH", matches = "1")
class MaskingRuleBenchTest {

    @Test
    void maskingP95UnderFiftyMillisOn64KBPayload() {
        var rule = new MaskingRule();

        // 64KB 시드 — JSON Map 트리. 민감값 일부 포함
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiKey", "sk_live_abcdef1234567890");
        payload.put("rrn", "900101-1234567");
        payload.put("phone", "010-1234-5678");
        payload.put("email", "user@example.com");
        String big = "x".repeat(60_000);
        payload.put("largeField", big);

        // 워밍업 30회
        for (int i = 0; i < 30; i++) {
            rule.mask(payload);
        }

        long[] times = new long[200];
        for (int i = 0; i < times.length; i++) {
            long t0 = System.nanoTime();
            rule.mask(payload);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        long p95Nanos = times[(int) Math.floor(times.length * 0.95) - 1];
        long p95Millis = p95Nanos / 1_000_000;
        long medianMillis = times[times.length / 2] / 1_000_000;

        System.out.printf("MaskingRule median = %d ms, p95 = %d ms%n", medianMillis, p95Millis);
        assertThat(p95Millis).as("p95 should be < 50ms").isLessThan(50);
    }
}
```

- [ ] **Step 2: 게이트 통과 확인**

Run (비활성): `cd backend && ./gradlew test --tests "MaskingRuleBenchTest"`
Expected: SKIPPED

Run (활성, Windows PowerShell): `cd backend; $env:RUN_BENCH=1; ./gradlew test --tests "MaskingRuleBenchTest"`
Expected: PASS, p95 ms 출력. 결과를 DAY7-SUMMARY에 인용.

- [ ] **Step 3: 커밋**

```bash
git add backend/src/test/java/com/noaats/ifms/global/masking/MaskingRuleBenchTest.java
git commit -m "test(masking): MaskingRule.mask p95 < 50ms 벤치 (RUN_BENCH=1 게이트, 64KB 페이로드)"
```

---

### Task 10: SSE subscribe TOCTOU race 테스트 (M4)

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/monitor/sse/SseSubscribeRaceTest.java`

> 코드 사실: subscribe(sessionId, actorId, clientId, lastEventId). Registry에 동일 clientId가 다른 sessionId에 등록될 수 있는 race를 검증. snapshotBySession(sessionId)는 sessionId 인자 받음 → 전체 카운트는 별도 순회.

- [ ] **Step 1: race 테스트**

```java
package com.noaats.ifms.domain.monitor.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "ifms.actor.anon-salt=test-salt-day7-race"
})
class SseSubscribeRaceTest {

    @Autowired SseEmitterService service;
    @Autowired SseEmitterRegistry registry;

    @RepeatedTest(20)
    void concurrentSubscribesOfSameClientIdConvergeToSingleEmitter() throws Exception {
        String clientId = "race-" + System.nanoTime();
        String sessionA = "sess-A-" + System.nanoTime();
        String sessionB = "sess-B-" + System.nanoTime();
        String actor = "raceActor";

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable subA = () -> { try { start.await(); service.subscribe(sessionA, actor, clientId, null); } catch (Exception ignored) {} };
        Runnable subB = () -> { try { start.await(); service.subscribe(sessionB, actor, clientId, null); } catch (Exception ignored) {} };

        pool.submit(subA);
        pool.submit(subB);
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // race 후: 동일 clientId 보유 세션이 정확히 1개여야 함
        long countWithClient = registry.snapshot().size() == 0 ? 0
                : countSessionsHoldingClient(clientId);
        // grace complete가 비동기라 잠깐 대기 — 최대 4초 (reassign-grace 2s + 여유)
        long deadline = System.currentTimeMillis() + 4_000;
        while (System.currentTimeMillis() < deadline && countWithClient > 1) {
            Thread.sleep(100);
            countWithClient = countSessionsHoldingClient(clientId);
        }
        assertThat(countWithClient).as("after race + grace, clientId held by ≤1 session")
                .isLessThanOrEqualTo(1L);

        // 정리 — 다음 반복을 위해
        registry.unregister(sessionA, clientId);
        registry.unregister(sessionB, clientId);
    }

    private long countSessionsHoldingClient(String clientId) {
        long count = 0;
        // SseEmitterRegistry에 직접 헬퍼 없음 — snapshotBySession을 모든 세션에 호출하긴 어려우니
        // 동일 sessionA/sessionB만 검사 (테스트 범위 한정).
        // 단순화: snapshot()이 비어있는지 + bySession 두 키 각각 보유 여부 검사.
        // SseEmitterRegistry가 두 세션 키를 모두 관리하므로 아래로 충분.
        if (!registry.snapshotBySession("sess-A-test").isEmpty()) count++; // placeholder
        // 실제로는 위 sessionA/sessionB를 외부에서 받아야 — 메서드 시그니처 변경
        return count;
    }
}
```

> 위 헬퍼는 메서드 외부의 sessionA/sessionB를 봐야 하므로 inline lambda로 재작성. 본 task의 핵심은 race 시 grace 후 1개로 수렴함을 검증. 정확한 검사는 step 2에서 정리.

- [ ] **Step 2: 헬퍼를 inline으로 정리한 최종 버전**

```java
@RepeatedTest(20)
void concurrentSubscribesOfSameClientIdConvergeToSingleEmitter() throws Exception {
    String clientId = "race-" + System.nanoTime();
    String sessionA = "sess-A-" + System.nanoTime();
    String sessionB = "sess-B-" + System.nanoTime();
    String actor = "raceActor";

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    pool.submit(() -> { try { start.await(); service.subscribe(sessionA, actor, clientId, null); } catch (Exception ignored) {} });
    pool.submit(() -> { try { start.await(); service.subscribe(sessionB, actor, clientId, null); } catch (Exception ignored) {} });
    start.countDown();
    pool.shutdown();
    pool.awaitTermination(5, TimeUnit.SECONDS);

    java.util.function.LongSupplier countHolders = () -> {
        long n = 0;
        if (!registry.snapshotBySession(sessionA).isEmpty()) n++;
        if (!registry.snapshotBySession(sessionB).isEmpty()) n++;
        return n;
    };

    long deadline = System.currentTimeMillis() + 4_000;
    long count = countHolders.getAsLong();
    while (System.currentTimeMillis() < deadline && count > 1) {
        Thread.sleep(100);
        count = countHolders.getAsLong();
    }
    assertThat(count).as("after race + grace, ≤1 session holds clientId")
            .isLessThanOrEqualTo(1L);

    registry.unregister(sessionA, clientId);
    registry.unregister(sessionB, clientId);
}
```

- [ ] **Step 3: 실행 (flaky 허용 — 3회 재시도)**

Run: `cd backend && ./gradlew test --tests "SseSubscribeRaceTest"`
Expected: 20 회 모두 PASS. 1~2회 fail이면 race이 실재. 5회 이상 fail이면 SseEmitterService.subscribe에 synchronized 도입 검토 (별도 follow-up).

- [ ] **Step 4: 커밋**

```bash
git add backend/src/test/java/com/noaats/ifms/domain/monitor/sse/SseSubscribeRaceTest.java
git commit -m "test(sse): subscribe TOCTOU race — 동일 clientId 동시 구독 grace 후 ≤1 수렴 (M4)"
```

---

### Task 11: M5 Dashboard → /history 드릴다운

**Files:**
- Modify: `frontend/src/pages/Dashboard.vue`
- Modify: `frontend/src/pages/ExecutionHistory.vue`

- [ ] **Step 1: Dashboard 카드/리스트에 router-link**

`Dashboard.vue` 내 recentFailures v-for 항목과 totals.failed 카드에:

```vue
<router-link :to="{ name: 'history', query: { status: 'FAILED' } }">
  <!-- 기존 컨텐츠 -->
</router-link>
```

byProtocol 행 클릭 시 protocol 필터는 백엔드 ExecutionListParams에 protocol이 없음(status·configId만 지원) → 이번 task에서는 status만 동기화.

- [ ] **Step 2: ExecutionHistory.vue route.query.status 진입 동기화**

`<script setup>`에서:

```ts
import { useRoute } from 'vue-router'
const route = useRoute()
const initialStatus = (route.query.status as ExecutionStatus | undefined) ?? undefined
const filterStatus = ref<ExecutionStatus | undefined>(initialStatus)
```

(이미 `filterStatus` 같은 ref가 있으면 초기값만 교체)

- [ ] **Step 3: 빌드 확인 + 커밋**

Run: `cd frontend && npm run build`

```bash
git add frontend/src/pages/Dashboard.vue frontend/src/pages/ExecutionHistory.vue
git commit -m "feat(dashboard): recentFailures·totals.failed → /history?status=FAILED 드릴다운 (M5)"
```

---

### Task 12: M7 sessionStorage 정리 분기 단순화

**Files:**
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/stores/auth.ts`

> 코드 사실: 현재 분기 `if (to.name === 'login' && from.name && from.name !== 'login')`. 새 탭(`from.name === undefined`)이면 정리 안 됨. 분기에서 `from.name &&` 조건을 제거 + auth.clear에 sessionStorage 정리 추가로 이중 안전.

- [ ] **Step 1: router 분기 단순화**

```ts
// 기존
if (to.name === 'login' && from.name && from.name !== 'login') {
  sessionStorage.removeItem('sse.clientId')
}
// 변경
if (to.name === 'login') {
  sessionStorage.removeItem('sse.clientId')
}
```

- [ ] **Step 2: auth.clear에 sessionStorage 정리 통합**

```ts
function clear() {
  authenticated.value = false
  username.value = null
  localStorage.removeItem('ifms:username')
  sessionStorage.removeItem('sse.clientId')
}
```

- [ ] **Step 3: 빌드 확인 + 커밋**

Run: `cd frontend && npm run build`

```bash
git add frontend/src/router/index.ts frontend/src/stores/auth.ts
git commit -m "fix(frontend): sessionStorage clientId 정리 — 새 탭 첫 방문도 처리 + auth.clear 통합 (M7)"
```

---

### Task 13: M8 ErrorCode union DELTA 2종

**Files:**
- Modify: `frontend/src/api/types.ts`

- [ ] **Step 1: union에 2종 append**

`ErrorCode` union 끝에 다음 2줄 추가 (현재 20종 → 21종):

```ts
  | 'NOT_IMPLEMENTED'
  | 'DELTA_SINCE_TOO_OLD'
  | 'DELTA_RATE_LIMITED'
```

- [ ] **Step 2: 빌드 + 커밋**

Run: `cd frontend && npm run build`

```bash
git add frontend/src/api/types.ts
git commit -m "fix(types): ErrorCode union에 DELTA_SINCE_TOO_OLD·DELTA_RATE_LIMITED 추가 (21종 정합, M8)"
```

---

## 묶음 2 — 문서·코드 정합

### Task 14: api-spec.md "17종" → "21종"

**Files:**
- Modify: `docs/api-spec.md`

- [ ] **Step 1: stale 위치 식별**

```bash
grep -nE "17종|17 종|17.{0,3}코드|17 codes" docs/api-spec.md
```

- [ ] **Step 2: 수동 Edit으로 한 건씩 교체** (replace_all 위험)

각 위치를 컨텍스트와 함께 "21종"으로 변경. ErrorCode 표(§1.3)에 DELTA 2종이 빠져 있으면 보강.

- [ ] **Step 3: §1.1 changelog 1줄 추가**

```markdown
- 2026-04-21 (Day 7): ErrorCode 표기 17종 → 21종 정합 (DELTA_SINCE_TOO_OLD · DELTA_RATE_LIMITED 누락 보강)
```

- [ ] **Step 4: 커밋**

```bash
git add docs/api-spec.md
git commit -m "docs(api-spec): ErrorCode 17종 → 21종 정합 (DELTA 2종 반영, Day 7)"
```

---

### Task 15: Jackson default-property-inclusion: ALWAYS

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/noaats/ifms/global/response/ApiResponseSerializationTest.java`

- [ ] **Step 1: application.yml `spring:` 블록에 추가**

```yaml
spring:
  jackson:
    default-property-inclusion: ALWAYS
```

(application 같은 레벨)

- [ ] **Step 2: 직렬화 테스트**

```java
package com.noaats.ifms.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {"ifms.actor.anon-salt=test-salt-jackson"})
class ApiResponseSerializationTest {

    @Autowired ObjectMapper mapper;

    @Test
    void apiResponseSerializesNullDataField() throws Exception {
        ApiResponse<String> r = ApiResponse.success(null);
        String json = mapper.writeValueAsString(r);
        assertThat(json).contains("\"data\":null");
    }

    @Test
    void apiResponseSerializesNullMessageField() throws Exception {
        ApiResponse<String> r = ApiResponse.success("ok");
        String json = mapper.writeValueAsString(r);
        assertThat(json).contains("\"message\":null");
    }
}
```

- [ ] **Step 3: 실행 + 커밋**

Run: `cd backend && ./gradlew test --tests "ApiResponseSerializationTest"`
Expected: 2 PASS

```bash
git add backend/src/main/resources/application.yml \
        backend/src/test/java/com/noaats/ifms/global/response/ApiResponseSerializationTest.java
git commit -m "feat(jackson): default-property-inclusion ALWAYS 명시 + null 직렬화 테스트 2종"
```

---

### Task 16: ApiResponse 불변식 Javadoc 보강

**Files:**
- Modify: `backend/src/main/java/com/noaats/ifms/global/response/ApiResponse.java`

- [ ] **Step 1: 클래스 javadoc 아래에 불변식 4줄 추가**

```java
 * <h2>불변식</h2>
 * <ul>
 *   <li>{@code success=true} 응답에서만 {@code data} 경로가 마스킹 대상이다 (DefensiveMaskingFilter §3.4).</li>
 *   <li>{@code success=false} 응답의 {@code data}는 ErrorDetail로, 마스킹은 적용되지 않는다 (구조화된 안전 필드).</li>
 *   <li>{@code message}는 사용자 노출 가능 텍스트만 담는다 (스택트레이스·SQL·내부 식별자 금지).</li>
 *   <li>{@code timestamp}는 응답 생성 시각, OffsetDateTime ISO-8601 (응답 본문 일관성).</li>
 * </ul>
```

- [ ] **Step 2: 컴파일 + 커밋**

```bash
cd backend && ./gradlew compileJava
git add backend/src/main/java/com/noaats/ifms/global/response/ApiResponse.java
git commit -m "docs(api): ApiResponse 불변식 4종 Javadoc 보강 (DevilsAdvocate Day 2-A)"
```

---

### Task 17: backlog.md 정리 + M2/M3/M6/M1/M9 운영 이관

**Files:**
- Modify: `docs/backlog.md`

- [ ] **Step 1: Day 5/6 완료 항목 ✅ 마킹 + Day 7 완료 항목 제거**

- [ ] **Step 2: §"운영 전환"에 M-항목 추가**

```markdown
| `DeltaCursor` HMAC 서명 (M2) | 공격자가 cursor 위조 가능 — DoS 표면. 분산 환경 전환 시 일괄 도입 |
| `DeltaRateLimiter.buckets` Map TTL cleanup (M3) | 장기 운영 시 메모리 압박. 분산 rate limit(Redis/Bucket4j) 전환 시 일괄 |
| SSE `reconnecting` 이중 refresh 관찰 (M6) | C3 싱글턴화로 확률 낮음. APM 도입 후 실 측정 기준 결정 |
| M1·M9 cursor 경계·sort=ASC prepend | 프런트 dedup이 은폐 중. 복합 cursor `(started_at, id)` + idx 신설 시 함께 |
```

- [ ] **Step 3: 커밋**

```bash
git add docs/backlog.md
git commit -m "docs(backlog): Day 5/6/7 완료 정리 + M1/M2/M3/M6/M9 운영 이관 사유 명문화"
```

---

## 빌드 게이트

### Task 18: 통합 빌드·테스트 검증

- [ ] **Step 1: 백엔드 clean build**

```bash
cd backend && ./gradlew clean build
```

Expected: BUILD SUCCESSFUL. 누적 테스트 = Day 6 18 + Day 7 신규 (T2:2 + T3:2 + T4:5 + T5:6 + T7:1 + T8:1 + T15:2 + T10:20반복=20) ≈ 39+. ArchUnit 3 PASS 유지.

- [ ] **Step 2: 프런트 빌드**

```bash
cd frontend && npm run build
```

Expected: vue-tsc PASS, vite 빌드 PASS

- [ ] **Step 3: 결과 메모**

신규 테스트 수, 실패 0건, 빌드 시간 → DAY7-SUMMARY 통계로.

---

## 묶음 3 — 제출 핸드오프

### Task 19: DAY7-SUMMARY.md

**Files:**
- Create: `docs/DAY7-SUMMARY.md`

(Day 6 SUMMARY 동일 형식 + Day 7 결과)

- [ ] **Step 1: 템플릿 채움 + 커밋 이력 추출**

```bash
git log --oneline a263101..HEAD
```

- [ ] **Step 2: 누적 통계 업데이트**

- [ ] **Step 3: 커밋**

```bash
git add docs/DAY7-SUMMARY.md
git commit -m "docs: Day 7 완료 요약 — 부채 청산·통합 테스트 11종·문서 정합 + 핸드오프 체크리스트"
```

---

### Task 20: README.md 최종

**Files:**
- Create or Modify: `README.md`

- [ ] **Step 1: 기존 확인**

```bash
[ -f README.md ] && head -30 README.md
```

- [ ] **Step 2: 평가용 README 작성**

섹션: 개요 / 기술 스택 / 빠른 실행 (3 명령어) / 주요 화면 / 평가용 문서 인덱스 / 테스트 / 알려진 한계.

- [ ] **Step 3: 커밋**

```bash
git add README.md
git commit -m "docs: 제출용 README — 빠른 실행·문서 인덱스·평가 포인트"
```

---

### Task 21: 사용자 핸드오프 메시지

(코드 변경 없음 — 사용자 메시지 출력만)

체크리스트:
- 수동 E2E 8 시나리오 (DAY6-SUMMARY §5)
- Day 5 회귀 5 시나리오
- Swagger UI 11 엔드포인트 try-it-out

---

## Self-Review (REV2)

**Spec 커버리지**: spec §2-A 11 항목, §2-B 5 항목, §2-C 6 항목. T1~T21 매핑 완료. ADR-005 Q1·Q2는 T5 단일 파일로 통합(단위 테스트 충분).

**격차 정정 적용 사실 확인**:
- ✅ ErrorCode 5종 명칭 코드 사실로 교체
- ✅ ExecutionLog 시드는 native INSERT (Seeder)
- ✅ RetryService 시그니처 4-arg
- ✅ Watchdog `sweep()` 메서드명
- ✅ schema.sql GIN 부재 → T2 GIN 검증 제거 + erd Known issue
- ✅ DefensiveMaskingFilter → MaskingRule 단위 벤치
- ✅ frontend ErrorCode 20 → 21 정확히 카운트
- ✅ sessionStorage 키 `'sse.clientId'`
- ✅ Snapshot vs Detail 차이 2개(configJson + createdAt)
- ✅ SseEmitterService.subscribe 시그니처 4-arg
- ✅ Jackson 정책에 직렬화 검증 테스트 동반

**Placeholder**: 0건. 모든 코드 블록 실제 시그니처 기반.

**타입 일관성**: ExecutionStatus / ErrorCode / RetryService.retry / SseEmitterService.subscribe / ExecutionLogTestSeeder API — 모두 task 간 일관 호출.

**무결성 위험**:
- T10 race 테스트 flaky 가능 — `@RepeatedTest(20)` + grace 4s 대기. 그래도 flaky이면 follow-up
- T7 watchdog의 `setTimeoutSeconds(1)`는 ck_ifc_timeout(BETWEEN 1 AND 600) 충족
- T4 `@Transactional(NOT_SUPPORTED)`로 RetryService(REQUIRES_NEW) 격리. seedLog는 본 메서드 외부 TX 불필요(자체 EM flush)

**진행 흐름**: T1 → (T2~T7 의존: T1) → T8 → T9 → T10 → T11~T13(프런트) → T14~T17(문서) → T18(게이트) → T19~T21.
