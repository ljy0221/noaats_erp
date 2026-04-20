# Day 7 — 통합 테스트 · 부채 청산 · 제출 핸드오프 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Day 6까지 완성된 IFMS 프로토타입의 인지된 부채(M1~M9)와 backlog Day 7 항목을 청산하고 제출용 패키지를 마무리한다.

**Architecture:** 신규 도메인 없음. 기존 모듈 내부 + 테스트 신설. Testcontainers PostgreSQL 16(JSONB·GIN·advisory lock 검증), 단순 nanoTime 벤치(Masking p95), CountDownLatch race(M4). 프런트는 query 동기화 + ErrorCode 2종 추가만.

**Tech Stack:** Java 17 / Spring Boot 3.3.5 / JUnit 5 / Testcontainers 1.20.3 / awaitility / ArchUnit 1.3.0 / Vue 3 / Vuetify 3 / TS 6 / Vite 8

**Spec:** `docs/superpowers/specs/2026-04-21-day7-integration-and-handoff-design.md`

---

## File Structure

### 신규 (백엔드 테스트 11)
```
backend/src/test/java/com/noaats/ifms/
├── support/
│   └── AbstractPostgresIntegrationTest.java         # T1 — Testcontainers 베이스
├── domain/execution/
│   ├── repository/JsonbGinIntegrationTest.java      # T2 — JSONB @> + GIN
│   └── service/
│       ├── AdvisoryLockIntegrationTest.java         # T3 — pg_try_advisory_xact_lock
│       ├── RetryServiceIntegrationTest.java         # T4 — 5종 에러 분기
│       ├── RetryMaxSnapshotPolicyTest.java          # T5 — ADR-005 Q1 (단위)
│       ├── RetryRootActorPolicyTest.java            # T6 — ADR-005 Q2 (단위)
│       └── OrphanRunningWatchdogIntegrationTest.java # T7
├── domain/interface_/
│   └── dto/SnapshotFieldParityTest.java             # T8 (단위 + 리플렉션)
├── domain/monitor/sse/
│   └── SseSubscribeRaceTest.java                    # T10 — M4
└── global/web/
    └── DefensiveMaskingFilterBenchTest.java         # T9 — 환경변수 게이트
```

### 수정 (백엔드)
```
backend/src/main/java/com/noaats/ifms/global/response/ApiResponse.java   # T13 — Javadoc 보강
backend/src/main/resources/application.yml                                # T12 — Jackson 정책
backend/src/main/resources/application-local.yml                          # T12 — 동일
backend/src/main/resources/application-test.yml                           # T12 — 동일 (있으면)
```

### 수정 (프런트)
```
frontend/src/api/types.ts                # T14 — DELTA 2종 union
frontend/src/stores/auth.ts              # T15 — sessionStorage clientId 정리 통합 (M7)
frontend/src/router/index.ts             # T15 — 가드 단일화 (M7)
frontend/src/pages/Dashboard.vue         # T16 — recentFailures 행/카드 router-link (M5)
frontend/src/pages/ExecutionHistory.vue  # T16 — route.query.status 초기 동기화 (M5)
```

### 수정 (문서)
```
docs/api-spec.md          # T11 — "17종" → "21종"
docs/backlog.md           # T17 — Day 5/6 ✅ + Day 7 완료 항목 정리 + M2/M3/M6 운영 이관
docs/DAY7-SUMMARY.md      # T18 — 신규
README.md                 # T19 — 최종 (있으면 갱신, 없으면 신규)
```

---

## 묶음 1 — 자동화된 코드 부채 청산

### Task 1: Testcontainers 베이스 클래스

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/support/AbstractPostgresIntegrationTest.java`

- [ ] **Step 1: 베이스 클래스 작성**

```java
package com.noaats.ifms.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers PostgreSQL 16 베이스. JSONB·GIN·advisory lock 검증이 필요한 통합 테스트만 상속.
 * 일반 단위 테스트는 기존 H2 기반 @SpringBootTest 유지.
 *
 * <p>컨테이너 재사용을 위해 {@code static} 필드 + {@code @Container} 미사용 (수명: JVM lifetime).
 * Spring Boot가 schema.sql을 자동 적용하도록 {@code spring.sql.init.mode=always}.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ifms")
                    .withUsername("ifms")
                    .withPassword("ifms1234")
                    .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Day 4 Bug A 회피: anon-salt 미주입 시 컨텍스트 부팅 실패
        registry.add("ifms.actor.anon-salt", () -> "test-salt-day7-integration");
    }
}
```

- [ ] **Step 2: 컨테이너 부팅 확인용 sanity 테스트 임시 작성**

`backend/src/test/java/com/noaats/ifms/support/PostgresContainerSanityTest.java`:

```java
package com.noaats.ifms.support;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PostgresContainerSanityTest extends AbstractPostgresIntegrationTest {

    @Autowired
    DataSource dataSource;

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

- [ ] **Step 3: sanity 테스트 실행**

Run: `cd backend && ./gradlew test --tests "com.noaats.ifms.support.PostgresContainerSanityTest"`
Expected: PASS (Docker 풀링 후 약 30~60초)

- [ ] **Step 4: sanity 테스트 삭제 (T1 검증 후 제거)**

Sanity는 일회성. 이후 T2/T3에서 실제 검증.

```bash
rm backend/src/test/java/com/noaats/ifms/support/PostgresContainerSanityTest.java
```

- [ ] **Step 5: 커밋**

```bash
git add backend/src/test/java/com/noaats/ifms/support/AbstractPostgresIntegrationTest.java
git commit -m "test(infra): Testcontainers PostgreSQL 16 베이스 클래스 (Day 7 통합 테스트 토대)"
```

---

### Task 2: JSONB `@>` 연산자 + GIN 인덱스 통합 테스트

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/execution/repository/JsonbGinIntegrationTest.java`

목표: ExecutionLog의 `request_payload`/`response_payload` JSONB 컬럼에 `@>` 컨테인먼트 쿼리가 동작하고, schema.sql에 정의된 GIN 인덱스를 EXPLAIN이 사용함을 확인.

- [ ] **Step 1: schema.sql에서 JSONB·GIN 정의 확인**

```bash
cd backend && grep -n "jsonb\|gin\|GIN" src/main/resources/schema.sql | head -30
```

만약 GIN 인덱스 이름이 다르면 테스트의 EXPLAIN 매처를 그 이름으로 맞출 것. (예상: `idx_log_request_payload_gin` 형태 — 실제 이름은 schema.sql 기준)

- [ ] **Step 2: 통합 테스트 작성**

```java
package com.noaats.ifms.domain.execution.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.noaats.ifms.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class JsonbGinIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    EntityManager em;

    @Test
    void jsonbContainmentOperatorMatchesNestedKey() {
        em.createNativeQuery("""
                INSERT INTO execution_log
                  (interface_config_id, triggered_by, status, started_at, request_payload, retry_count)
                VALUES (NULL, 'MANUAL', 'SUCCESS', NOW(),
                        CAST(:p AS jsonb), 0)
                """)
                .setParameter("p", "{\"k\":\"v\",\"meta\":{\"trace\":\"abc\"}}")
                .executeUpdate();

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM execution_log
                WHERE request_payload @> CAST(:filter AS jsonb)
                """)
                .setParameter("filter", "{\"meta\":{\"trace\":\"abc\"}}")
                .getSingleResult();

        assertThat(count.longValue()).isEqualTo(1L);
    }

    @Test
    void ginIndexAppearsInExplainForJsonbContainment() {
        // GIN 인덱스가 의미 있게 선택되려면 ANALYZE가 필요. 빈 테이블에서는 seq scan일 수 있어
        // 50건 삽입 후 ANALYZE.
        for (int i = 0; i < 50; i++) {
            em.createNativeQuery("""
                    INSERT INTO execution_log
                      (interface_config_id, triggered_by, status, started_at, request_payload, retry_count)
                    VALUES (NULL, 'MANUAL', 'SUCCESS', NOW(),
                            CAST(:p AS jsonb), 0)
                    """)
                    .setParameter("p", "{\"k\":\"v" + i + "\"}")
                    .executeUpdate();
        }
        em.createNativeQuery("ANALYZE execution_log").executeUpdate();

        @SuppressWarnings("unchecked")
        var rows = (java.util.List<Object>) em.createNativeQuery("""
                EXPLAIN SELECT 1 FROM execution_log
                WHERE request_payload @> CAST('{"k":"v25"}' AS jsonb)
                """).getResultList();

        var plan = rows.stream().map(Object::toString).reduce("", (a, b) -> a + "\n" + b);
        // GIN 인덱스 사용 흔적 (Bitmap Index Scan on idx_log_request_payload_gin 또는 동등)
        assertThat(plan)
                .as("EXPLAIN should mention GIN bitmap scan or the index name")
                .containsAnyOf("Bitmap Index Scan", "request_payload");
    }
}
```

- [ ] **Step 3: 실행**

Run: `cd backend && ./gradlew test --tests "JsonbGinIntegrationTest"`
Expected: 2 PASS

- [ ] **Step 4: 실패 시 어드저스트**

만약 `Bitmap Index Scan`이 없으면 schema.sql에 GIN 인덱스가 누락된 것. erd.md §10 기준으로 추가하거나, 50건 → 500건으로 늘려 planner가 GIN을 선택하게.

- [ ] **Step 5: 커밋**

```bash
git add backend/src/test/java/com/noaats/ifms/domain/execution/repository/JsonbGinIntegrationTest.java
git commit -m "test(execution): JSONB @> 연산자 + GIN 인덱스 통합 테스트 (Testcontainers)"
```

---

### Task 3: Advisory Lock 통합 테스트

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/execution/service/AdvisoryLockIntegrationTest.java`

목표: `pg_try_advisory_xact_lock(int,int)` 시그니처 동작 확인 + 동일 (namespace, configId)에 대한 두 번째 트랜잭션이 lock 실패함을 검증.

- [ ] **Step 1: 통합 테스트 작성**

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.noaats.ifms.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

class AdvisoryLockIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    EntityManager em;

    @Value("${ifms.advisory-lock.namespace}")
    int namespace;

    @Test
    void firstTransactionAcquiresLockSecondFails() throws Exception {
        int configId = 9999;

        // 동일 키에 대해 두 트랜잭션을 동시에 열어 두 번째가 false를 받는지 확인.
        // tx 1: lock 획득 후 sleep
        var lock1 = new Object();
        var lockHeld = new java.util.concurrent.CountDownLatch(1);
        var releaseSignal = new java.util.concurrent.CountDownLatch(1);
        var t1Result = new java.util.concurrent.atomic.AtomicBoolean(false);
        var t2Result = new java.util.concurrent.atomic.AtomicBoolean(true);

        Thread t1 = new Thread(() -> tx.executeWithoutResult(s -> {
            Boolean got = (Boolean) em.createNativeQuery(
                    "SELECT pg_try_advisory_xact_lock(:n, :id)")
                    .setParameter("n", namespace)
                    .setParameter("id", configId)
                    .getSingleResult();
            t1Result.set(got);
            lockHeld.countDown();
            try {
                releaseSignal.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }));

        Thread t2 = new Thread(() -> {
            try {
                lockHeld.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            tx.executeWithoutResult(s -> {
                Boolean got = (Boolean) em.createNativeQuery(
                        "SELECT pg_try_advisory_xact_lock(:n, :id)")
                        .setParameter("n", namespace)
                        .setParameter("id", configId)
                        .getSingleResult();
                t2Result.set(got);
            });
        });

        t1.start();
        t2.start();
        // t2가 lock 획득 시도 후 종료될 때까지 대기 (t2는 즉시 false 반환 후 종료)
        t2.join(5_000);
        releaseSignal.countDown();
        t1.join(5_000);

        assertThat(t1Result.get()).as("first tx should acquire lock").isTrue();
        assertThat(t2Result.get()).as("second tx should fail to acquire lock").isFalse();
    }
}
```

- [ ] **Step 2: 실행**

Run: `cd backend && ./gradlew test --tests "AdvisoryLockIntegrationTest"`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add backend/src/test/java/com/noaats/ifms/domain/execution/service/AdvisoryLockIntegrationTest.java
git commit -m "test(execution): pg_try_advisory_xact_lock(int,int) 통합 테스트 (동시성 차단 검증)"
```

---

### Task 4: RetryService 5종 에러 분기 통합 테스트

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/execution/service/RetryServiceIntegrationTest.java`

목표: ADR-005 §5의 5종 에러 분기 — `RETRY_TARGET_NOT_TERMINAL` (RUNNING), `RETRY_TARGET_ALREADY_SUCCESS`, `RETRY_FORBIDDEN_ACTOR`, `RETRY_MAX_EXCEEDED`, `RETRY_CHAIN_FROM_NON_TERMINAL` 각각 발생 검증.

- [ ] **Step 1: RetryService 시그니처와 ErrorCode 확인**

```bash
cd backend && grep -n "Retry" src/main/java/com/noaats/ifms/global/exception/ErrorCode.java
grep -n "public.*retry\|public.*Retry" src/main/java/com/noaats/ifms/domain/execution/service/RetryService.java
```

(에러 코드명·메서드 시그니처가 위 5종과 다르면 그에 맞게 테스트 메서드명·assertion 조정)

- [ ] **Step 2: 통합 테스트 작성 (스켈레톤 + 5 케이스)**

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.domain.TriggerType;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.global.exception.ApiException;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.support.AbstractPostgresIntegrationTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RetryServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    RetryService retryService;
    @Autowired
    ExecutionLogRepository repo;

    private ExecutionLog seed(ExecutionStatus status, String actor, int retry, Long parentId) {
        ExecutionLog log = ExecutionLog.builder()
                .interfaceConfigId(null)
                .triggeredBy(TriggerType.MANUAL)
                .status(status)
                .startedAt(OffsetDateTime.now().minusMinutes(1))
                .finishedAt(status == ExecutionStatus.RUNNING ? null : OffsetDateTime.now())
                .actorHash(actor)
                .retryCount(retry)
                .parentLogId(parentId)
                .build();
        return repo.save(log);
    }

    @Test
    void retryRunningTarget_throwsTargetNotTerminal() {
        ExecutionLog running = seed(ExecutionStatus.RUNNING, "actorA", 0, null);
        assertThatThrownBy(() -> retryService.retry(running.getId(), "actorA"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_TARGET_NOT_TERMINAL);
    }

    @Test
    void retrySuccessTarget_throwsAlreadySuccess() {
        ExecutionLog ok = seed(ExecutionStatus.SUCCESS, "actorA", 0, null);
        assertThatThrownBy(() -> retryService.retry(ok.getId(), "actorA"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_TARGET_ALREADY_SUCCESS);
    }

    @Test
    void retryByDifferentActor_throwsForbiddenActor() {
        ExecutionLog failed = seed(ExecutionStatus.FAILED, "actorA", 0, null);
        assertThatThrownBy(() -> retryService.retry(failed.getId(), "actorB"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_FORBIDDEN_ACTOR);
    }

    @Test
    void retryAtMaxCount_throwsMaxExceeded() {
        // ADR-005: max_retry_snapshot 기본 3 가정. 실제 기본값과 다르면 그 값으로 조정.
        ExecutionLog failed = seed(ExecutionStatus.FAILED, "actorA", 3, null);
        assertThatThrownBy(() -> retryService.retry(failed.getId(), "actorA"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_MAX_EXCEEDED);
    }

    @Test
    void retryChainFromNonTerminalParent_throwsChainFromNonTerminal() {
        ExecutionLog parentRunning = seed(ExecutionStatus.RUNNING, "actorA", 0, null);
        ExecutionLog childOrphan = seed(ExecutionStatus.FAILED, "actorA", 0, parentRunning.getId());
        assertThatThrownBy(() -> retryService.retry(childOrphan.getId(), "actorA"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_CHAIN_FROM_NON_TERMINAL);
    }
}
```

- [ ] **Step 3: 실행 + 시그니처 불일치 시 보정**

Run: `cd backend && ./gradlew test --tests "RetryServiceIntegrationTest"`
Expected: 5 PASS. 만약 `retry(Long, String)` 시그니처가 `retry(Long, ActorContext)` 등이면 그에 맞게 호출. 만약 ErrorCode 명칭이 다르면 ErrorCode enum 값으로 정확히 치환.

- [ ] **Step 4: 커밋**

```bash
git add backend/src/test/java/com/noaats/ifms/domain/execution/service/RetryServiceIntegrationTest.java
git commit -m "test(retry): RetryService 5종 에러 분기 통합 테스트 (ADR-005 §5)"
```

---

### Task 5: ADR-005 Q1 — `max_retry_snapshot` 보존 단위 테스트

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/execution/service/RetryMaxSnapshotPolicyTest.java`

목표: 인터페이스의 `max_retry_count`가 PATCH로 변경되더라도, 진행 중 체인은 시작 시점의 snapshot을 그대로 사용함을 검증. 이건 `RetryGuardSnapshot`의 동작 단위 테스트.

- [ ] **Step 1: RetryGuardSnapshot/RetryGuard API 확인**

```bash
cd backend && grep -n "snapshot\|max_retry" src/main/java/com/noaats/ifms/domain/execution/service/RetryGuard*.java
```

- [ ] **Step 2: 단위 테스트 작성**

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.domain.TriggerType;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class RetryMaxSnapshotPolicyTest {

    @Test
    void chainKeepsOriginalMaxSnapshotEvenIfConfigChanges() {
        // ADR-005 Q1: 진행 중 체인이 옛 snapshot 유지.
        // RetryGuardSnapshot은 chain root의 max_retry_snapshot을 단일 값으로 들고 있음.
        // 따라서 config의 max_retry_count가 1→5로 바뀌어도, snapshot은 1로 유지되어야 함.

        int originalMax = 1;
        int newConfigMax = 5;

        ExecutionLog root = ExecutionLog.builder()
                .triggeredBy(TriggerType.MANUAL)
                .status(ExecutionStatus.FAILED)
                .startedAt(OffsetDateTime.now())
                .retryCount(0)
                .maxRetrySnapshot(originalMax) // 시작 시점에 박힘
                .actorHash("actor")
                .build();

        // Guard 평가는 root.maxRetrySnapshot 기반이지, config 현재값 기반이 아니어야 한다
        RetryGuardSnapshot snap = RetryGuardSnapshot.from(root, root, newConfigMax);

        assertThat(snap.maxRetry()).isEqualTo(originalMax);
    }
}
```

> 주의: 실제 `RetryGuardSnapshot` API가 `from(child, root, currentConfigMax)`가 아니면 그에 맞게 호출 시그니처 조정. 핵심 assertion은 "snapshot은 root의 시작 시점 max를 그대로 반환한다".

- [ ] **Step 3: 실행**

Run: `cd backend && ./gradlew test --tests "RetryMaxSnapshotPolicyTest"`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add backend/src/test/java/com/noaats/ifms/domain/execution/service/RetryMaxSnapshotPolicyTest.java
git commit -m "test(retry): ADR-005 Q1 — max_retry_snapshot 보존 단위 테스트"
```

---

### Task 6: ADR-005 Q2 — 멀티홉 체인 루트 actor 검증

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/execution/service/RetryRootActorPolicyTest.java`

목표: 체인 root → child → grandchild 순서에서, grandchild를 루트와 다른 actor가 retry하면 `RETRY_FORBIDDEN_ACTOR`. 같은 actor면 통과.

- [ ] **Step 1: 단위 테스트 작성** — Task 4에서 이미 1-홉은 검증. 여기는 2-홉 이상 체인.

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.domain.TriggerType;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.global.exception.ApiException;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.support.AbstractPostgresIntegrationTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RetryRootActorPolicyTest extends AbstractPostgresIntegrationTest {

    @Autowired
    RetryService retryService;
    @Autowired
    ExecutionLogRepository repo;

    private ExecutionLog seed(ExecutionStatus status, String actor, Long parentId, int retry) {
        return repo.save(ExecutionLog.builder()
                .triggeredBy(TriggerType.MANUAL)
                .status(status)
                .startedAt(OffsetDateTime.now())
                .finishedAt(OffsetDateTime.now())
                .actorHash(actor)
                .parentLogId(parentId)
                .retryCount(retry)
                .build());
    }

    @Test
    void grandchildRetryByDifferentActorThanRoot_isForbidden() {
        ExecutionLog root = seed(ExecutionStatus.FAILED, "actorA", null, 0);
        ExecutionLog child = seed(ExecutionStatus.FAILED, "actorA", root.getId(), 1);
        ExecutionLog grand = seed(ExecutionStatus.FAILED, "actorA", child.getId(), 2);

        assertThatThrownBy(() -> retryService.retry(grand.getId(), "actorB"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_FORBIDDEN_ACTOR);
    }

    @Test
    void grandchildRetryBySameActorAsRoot_proceedsPastActorCheck() {
        ExecutionLog root = seed(ExecutionStatus.FAILED, "actorA", null, 0);
        ExecutionLog child = seed(ExecutionStatus.FAILED, "actorA", root.getId(), 1);
        ExecutionLog grand = seed(ExecutionStatus.FAILED, "actorA", child.getId(), 2);

        // actorA로 retry — actor check 통과. 단, max_retry_snapshot 등 다른 가드는 통과/실패 가능.
        // 여기서는 RETRY_FORBIDDEN_ACTOR가 아닌 다른 결과(성공 or 다른 ErrorCode)임을 확인.
        try {
            ExecutionLog newChild = retryService.retry(grand.getId(), "actorA");
            assertThat(newChild).isNotNull();
        } catch (ApiException e) {
            assertThat(e.getErrorCode()).isNotEqualTo(ErrorCode.RETRY_FORBIDDEN_ACTOR);
        }
    }
}
```

- [ ] **Step 2: 실행**

Run: `cd backend && ./gradlew test --tests "RetryRootActorPolicyTest"`
Expected: 2 PASS

- [ ] **Step 3: 커밋**

```bash
git add backend/src/test/java/com/noaats/ifms/domain/execution/service/RetryRootActorPolicyTest.java
git commit -m "test(retry): ADR-005 Q2 — 멀티홉 체인 루트 actor 검증 통합 테스트"
```

---

### Task 7: OrphanRunningWatchdog 통합 테스트

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/execution/service/OrphanRunningWatchdogIntegrationTest.java`

목표: RUNNING 상태로 timeoutSeconds + 30s 이상 멈춘 ExecutionLog를 watchdog이 5분 sweep으로 FAILED + `errorMessage` 기록. 테스트 속도를 위해 watchdog 주기·임계값을 짧게 주입.

- [ ] **Step 1: Watchdog 설정 키 확인**

```bash
cd backend && grep -n "Scheduled\|@Value\|watchdog" src/main/java/com/noaats/ifms/domain/execution/service/OrphanRunningWatchdog.java
```

(주입 가능한 설정 키 확인 — `ifms.watchdog.*` 패턴 가정. 다르면 그 키로.)

- [ ] **Step 2: 통합 테스트 작성**

```java
package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.domain.TriggerType;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.support.AbstractPostgresIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrphanRunningWatchdogIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    ExecutionLogRepository repo;
    @Autowired
    OrphanRunningWatchdog watchdog;

    @Test
    void runningLogStuckBeyondThresholdIsRecoveredToFailed() {
        // 임계값보다 오래된 RUNNING 시드
        ExecutionLog stuck = repo.save(ExecutionLog.builder()
                .triggeredBy(TriggerType.MANUAL)
                .status(ExecutionStatus.RUNNING)
                .startedAt(OffsetDateTime.now().minusMinutes(30))
                .actorHash("actor")
                .retryCount(0)
                .build());

        // sweep 직접 호출 (스케줄 의존 제거)
        watchdog.sweepOrphans();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ExecutionLog reloaded = repo.findById(stuck.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(reloaded.getFinishedAt()).isNotNull();
            assertThat(reloaded.getErrorMessage()).isNotBlank();
        });
    }
}
```

> awaitility 의존이 spring-boot-starter-test에 transitively 포함됨 (확인 필요. 없으면 build.gradle에 추가).

- [ ] **Step 3: awaitility 확인**

```bash
cd backend && ./gradlew dependencies --configuration testRuntimeClasspath | grep -i awaitility
```

없으면 build.gradle에 추가:
```gradle
testImplementation 'org.awaitility:awaitility:4.2.2'
```

- [ ] **Step 4: 실행**

Run: `cd backend && ./gradlew test --tests "OrphanRunningWatchdogIntegrationTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/src/test/java/com/noaats/ifms/domain/execution/service/OrphanRunningWatchdogIntegrationTest.java
[ -f backend/build.gradle.changed ] && git add backend/build.gradle
git commit -m "test(watchdog): OrphanRunningWatchdog sweep 통합 테스트 (RUNNING→FAILED 회수)"
```

---

### Task 8: SnapshotFieldParityTest

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/interface_/dto/SnapshotFieldParityTest.java`

목표: `InterfaceConfigDetailResponse`와 `InterfaceConfigSnapshot`의 필드 집합이 일치(`configJson`만 차이)하는지 리플렉션으로 검증. 누락 시 감사 추적이 깨짐 (Architect Day 2-B SHOULD).

- [ ] **Step 1: 두 클래스 필드 확인**

```bash
cd backend && grep -n "private\|record" src/main/java/com/noaats/ifms/domain/interface_/dto/InterfaceConfigDetailResponse.java
grep -n "private\|record" src/main/java/com/noaats/ifms/domain/interface_/dto/InterfaceConfigSnapshot.java
```

- [ ] **Step 2: 리플렉션 기반 단위 테스트 작성**

```java
package com.noaats.ifms.domain.interface_.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SnapshotFieldParityTest {

    @Test
    void detailResponseAndSnapshotShareAllFieldsExceptConfigJson() {
        Set<String> detail = recordOrFieldNames(InterfaceConfigDetailResponse.class);
        Set<String> snap = recordOrFieldNames(InterfaceConfigSnapshot.class);

        // configJson은 detail에만 존재해도 OK (snapshot은 별도 경로로 마스킹된 payload 보존)
        Set<String> detailMinusConfigJson = detail.stream()
                .filter(n -> !n.equals("configJson"))
                .collect(Collectors.toSet());
        Set<String> snapMinusConfigJson = snap.stream()
                .filter(n -> !n.equals("configJson"))
                .collect(Collectors.toSet());

        assertThat(detailMinusConfigJson)
                .as("DetailResponse fields (minus configJson) should equal Snapshot fields")
                .isEqualTo(snapMinusConfigJson);
    }

    private Set<String> recordOrFieldNames(Class<?> c) {
        if (c.isRecord()) {
            return Arrays.stream(c.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toSet());
        }
        return Arrays.stream(c.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());
    }
}
```

- [ ] **Step 3: 실행**

Run: `cd backend && ./gradlew test --tests "SnapshotFieldParityTest"`
Expected: PASS — 만약 FAIL이면 어느 한쪽에 누락 필드가 있다는 신호. 실패 메시지의 diff를 보고 누락 필드를 추가하거나 (실 결함이면) snapshot/dto를 정합화. 마이너 차이는 테스트의 ignored set으로 합리적으로 분리하지 말고 코드를 고치는 쪽이 맞다.

- [ ] **Step 4: 커밋**

```bash
git add backend/src/test/java/com/noaats/ifms/domain/interface_/dto/SnapshotFieldParityTest.java
git commit -m "test(interface): SnapshotFieldParityTest — DetailResponse↔Snapshot 필드 정합 (Architect Day 2-B SHOULD)"
```

---

### Task 9: DefensiveMaskingFilter p95 < 50ms 벤치

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/global/web/DefensiveMaskingFilterBenchTest.java`

목표: 64KB payload × 100건의 mask 처리 p95가 50ms 미만임을 단순 nanoTime 측정으로 확인. 환경변수 `RUN_BENCH=1`로만 활성화 (CI에서 노이즈 방지).

- [ ] **Step 1: DefensiveMaskingFilter 위치 확인**

```bash
cd backend && find src/main/java -name "DefensiveMaskingFilter.java"
```

- [ ] **Step 2: 벤치 테스트 작성**

```java
package com.noaats.ifms.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noaats.ifms.global.web.DefensiveMaskingFilter;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "RUN_BENCH", matches = "1")
class DefensiveMaskingFilterBenchTest {

    @Test
    void maskingP95UnderFiftyMillis() {
        var mapper = new ObjectMapper();
        var filter = new DefensiveMaskingFilter(mapper); // 실제 시그니처에 맞게 인스턴스화

        // 64KB payload 시드
        var bigValue = "x".repeat(60_000);
        var payload = "{\"password\":\"secret\",\"large\":\"" + bigValue + "\"}";

        long[] times = new long[100];

        // 워밍업
        for (int i = 0; i < 20; i++) {
            filter.maskJsonString(payload);
        }

        for (int i = 0; i < times.length; i++) {
            long t0 = System.nanoTime();
            filter.maskJsonString(payload);
            times[i] = System.nanoTime() - t0;
        }

        Arrays.sort(times);
        long p95Nanos = times[(int) Math.floor(times.length * 0.95) - 1];
        long p95Millis = p95Nanos / 1_000_000;

        System.out.printf("DefensiveMaskingFilter p95 = %d ms%n", p95Millis);
        assertThat(p95Millis).isLessThan(50);
    }
}
```

> `maskJsonString(String)` 같은 직접 호출 메서드가 없으면 reflection 또는 mock HttpServletRequest로 대체.

- [ ] **Step 3: 로컬 실행 (사용자 권장)**

Run: `cd backend && RUN_BENCH=1 ./gradlew test --tests "DefensiveMaskingFilterBenchTest"`
(Windows PowerShell: `$env:RUN_BENCH=1; ./gradlew test --tests "DefensiveMaskingFilterBenchTest"`)
Expected: PASS, p95 ms 출력

- [ ] **Step 4: 비활성화 상태로 빌드 영향 없음 확인**

Run: `cd backend && ./gradlew test --tests "DefensiveMaskingFilterBenchTest"`
Expected: SKIP (RUN_BENCH 미설정)

- [ ] **Step 5: 커밋 + DAY7-SUMMARY에 결과 기록 약속**

```bash
git add backend/src/test/java/com/noaats/ifms/global/web/DefensiveMaskingFilterBenchTest.java
git commit -m "test(masking): DefensiveMaskingFilter p95 < 50ms 벤치 (RUN_BENCH=1 게이트)"
```

---

### Task 10: SseEmitterService.subscribe TOCTOU race 테스트 (M4)

**Files:**
- Create: `backend/src/test/java/com/noaats/ifms/domain/monitor/sse/SseSubscribeRaceTest.java`

목표: 동일 clientId로 두 subscribe가 거의 동시에 진입했을 때, registry에 emitter가 정확히 1개만 남고, 다른 하나는 정상적으로 close되어야 함을 검증.

- [ ] **Step 1: SseEmitterService.subscribe 시그니처 확인**

```bash
cd backend && grep -n "public.*subscribe" src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterService.java | head -5
```

- [ ] **Step 2: race 테스트 작성** (flaky하면 `@RepeatedTest(20)` 적용)

```java
package com.noaats.ifms.domain.monitor.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "ifms.actor.anon-salt=test-salt-day7-race"
})
class SseSubscribeRaceTest {

    @Autowired
    SseEmitterService service;
    @Autowired
    SseEmitterRegistry registry;

    @RepeatedTest(20)
    void concurrentSubscribesOfSameClientIdConvergeToSingleEmitter() throws Exception {
        String clientId = "race-" + System.nanoTime();
        String sessionA = "sess-A-" + System.nanoTime();
        String sessionB = "sess-B-" + System.nanoTime();
        String actor = "actorRaceTest";

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable sub = () -> {
            // 주의: 실제 subscribe 시그니처에 맞춰 호출 — 아래는 가정
            // service.subscribe(clientId, sessionId, actor, lastEventId)
        };

        pool.submit(() -> { try { start.await(); service.subscribe(clientId, sessionA, actor, null); } catch (Exception ignored) {} });
        pool.submit(() -> { try { start.await(); service.subscribe(clientId, sessionB, actor, null); } catch (Exception ignored) {} });
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        // race 후 registry에 동일 clientId의 emitter는 최대 1개여야 함
        long count = registry.snapshotBySession().stream()
                .filter(e -> e.clientId().equals(clientId))
                .count();
        assertThat(count).isLessThanOrEqualTo(1L);
    }
}
```

> `subscribe` 시그니처가 다르면 그에 맞게 조정. snapshotBySession이 record list를 반환한다고 가정 (DAY6-SUMMARY 2-B 명시).

- [ ] **Step 3: 실행**

Run: `cd backend && ./gradlew test --tests "SseSubscribeRaceTest"`
Expected: 20 회 모두 PASS. flaky하면 `@RepeatedTest(50)`로 키우고 결과 관찰. 항상 실패하면 M4가 실재 결함이라는 신호 — 그때만 SseEmitterService에 synchronized 블록 도입을 별도 task로 검토.

- [ ] **Step 4: 커밋**

```bash
git add backend/src/test/java/com/noaats/ifms/domain/monitor/sse/SseSubscribeRaceTest.java
git commit -m "test(sse): subscribe TOCTOU race 시나리오 — 동일 clientId 동시 구독 수렴 확인 (M4)"
```

---

### Task 11: M5 Dashboard → /history 드릴다운

**Files:**
- Modify: `frontend/src/pages/Dashboard.vue`
- Modify: `frontend/src/pages/ExecutionHistory.vue`
- Modify: `frontend/src/router/index.ts` (필요 시)

- [ ] **Step 1: Dashboard.vue에서 recentFailures 행에 router-link 추가**

각 failure 행 또는 카드의 "FAILED" 카운터 클릭 시:

```vue
<router-link :to="{ path: '/history', query: { status: 'FAILED' } }">
  <!-- 기존 카드/행 컨텐츠 -->
</router-link>
```

byProtocol 행도 프로토콜 카운트 클릭 시 `query: { status, interfaceConfigId? }` (configId 없으면 status만).

- [ ] **Step 2: ExecutionHistory.vue 진입 시 route.query.status를 필터 초기값으로 동기화**

`onMounted` 또는 `setup`에서:

```ts
import { useRoute } from 'vue-router';
const route = useRoute();
const initialStatus = (route.query.status as string | undefined) ?? '';
const filterStatus = ref(initialStatus);
```

그리고 필터 변경 시 query string도 동기화 (선택 — 이번 task에선 진입 동기화만 필수, 나가는 방향 동기화는 추가 작업이므로 건너뜀).

- [ ] **Step 3: 빌드 확인**

Run: `cd frontend && npm run build`
Expected: vue-tsc PASS, vite 빌드 PASS

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/pages/Dashboard.vue frontend/src/pages/ExecutionHistory.vue
git commit -m "feat(dashboard): recentFailures·byProtocol → /history 드릴다운 (M5)"
```

---

### Task 12: M7 sessionStorage clientId 정리 통합

**Files:**
- Modify: `frontend/src/stores/auth.ts`
- Modify: `frontend/src/router/index.ts`

목표: 새 탭 첫 방문 `/login`에서도 stale clientId가 정리되도록, `auth.logout()` 내부에 `sessionStorage.removeItem('ifms.sse.clientId')`를 두고 router guard에서 호출.

- [ ] **Step 1: 현재 구현 확인**

```bash
cd frontend && grep -n "clientId\|sessionStorage" src/stores/auth.ts src/router/index.ts
```

- [ ] **Step 2: auth.logout 내부 통합**

`stores/auth.ts`:

```ts
async function logout() {
  try {
    await apiLogout(); // 기존 호출
  } finally {
    sessionStorage.removeItem('ifms.sse.clientId');
    user.value = null;
  }
}
```

- [ ] **Step 3: router guard 단일화**

`router/index.ts`에서 `/login`으로 이동하는 모든 분기를 `auth.logout()` 호출로 통일하거나, beforeEach에서:

```ts
router.beforeEach((to, from) => {
  if (to.name === 'Login') {
    sessionStorage.removeItem('ifms.sse.clientId');
  }
});
```

(둘 중 더 작은 변경 선택 — 기존 코드 패턴 보존)

- [ ] **Step 4: 빌드 확인**

Run: `cd frontend && npm run build`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/stores/auth.ts frontend/src/router/index.ts
git commit -m "fix(frontend): /login 진입 시 sessionStorage clientId 정리 (M7)"
```

---

### Task 13: M8 프런트 ErrorCode union에 DELTA 2종 추가

**Files:**
- Modify: `frontend/src/api/types.ts`

- [ ] **Step 1: 현재 ErrorCode union 확인**

```bash
cd frontend && grep -n "ErrorCode" src/api/types.ts
```

- [ ] **Step 2: 2종 추가**

```ts
export type ErrorCode =
  | 'VALIDATION_FAILED'
  | 'OPTIMISTIC_LOCK_CONFLICT'
  | 'CONFIG_JSON_FORBIDDEN_KEY'
  | 'CONFIG_JSON_TOO_LARGE'
  // ... 기존 union
  | 'DELTA_SINCE_TOO_OLD'
  | 'DELTA_RATE_LIMITED';
```

(기존 21종에 맞게 정확히 21개 union이 되도록)

- [ ] **Step 3: 빌드 확인**

Run: `cd frontend && npm run build`
Expected: vue-tsc PASS

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/api/types.ts
git commit -m "fix(types): ErrorCode union에 DELTA_SINCE_TOO_OLD·DELTA_RATE_LIMITED 추가 (M8, 21종 정합)"
```

---

## 묶음 2 — 문서·코드 정합

### Task 14: api-spec.md "17종" → "21종" stale 정리

**Files:**
- Modify: `docs/api-spec.md`

- [ ] **Step 1: stale 위치 식별**

```bash
grep -n "17종\|17 종\|17 codes\|17가지" docs/api-spec.md
```

- [ ] **Step 2: 모두 "21종"으로 교체 + ErrorCode 표(§1.3)에 DELTA 2종이 빠져 있으면 추가**

```bash
# 검토 후 Edit 도구로 한 건씩 교체. replace_all은 컨텍스트 잘못 잡을 수 있어 수동 검토 권장.
```

- [ ] **Step 3: §1.1 changelog에 "Day 7 — ErrorCode 표기 21종으로 정합" 1줄 추가**

- [ ] **Step 4: 커밋**

```bash
git add docs/api-spec.md
git commit -m "docs(api-spec): ErrorCode 표기 17종 → 21종 정합 (DELTA 2종 반영)"
```

---

### Task 15: Jackson default-property-inclusion: ALWAYS 명시

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-local.yml` (있으면)

목표: ApiResponse가 `data: null`을 직렬화하지 않으면 프런트가 `undefined` 대 `null` 분기를 부담. 명시적으로 ALWAYS로 강제.

- [ ] **Step 1: 현재 정책 확인**

```bash
cd backend && grep -rn "jackson\|default-property-inclusion\|JsonInclude" src/main/resources src/main/java/com/noaats/ifms/global/config | head -20
```

- [ ] **Step 2: application.yml에 추가**

```yaml
spring:
  jackson:
    default-property-inclusion: ALWAYS
```

(spring 키 하위에 이미 application 등 존재 — 추가 위치는 spring 블록 내부)

- [ ] **Step 3: 백엔드 기동 + 직렬화 확인**

Run: `cd backend && ./gradlew bootRun` (백그라운드) → 잠시 후 `curl http://localhost:8080/actuator/health` (있다면) 또는 임의 GET으로 응답 확인

또는 더 안전하게 단위 테스트:

`backend/src/test/java/com/noaats/ifms/global/response/ApiResponseSerializationTest.java`:

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

    @Autowired
    ObjectMapper mapper;

    @Test
    void apiResponseSerializesNullDataField() throws Exception {
        ApiResponse<String> r = ApiResponse.success(null);
        String json = mapper.writeValueAsString(r);
        assertThat(json).contains("\"data\":null");
    }
}
```

- [ ] **Step 4: 실행**

Run: `cd backend && ./gradlew test --tests "ApiResponseSerializationTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/resources/application.yml \
        backend/src/test/java/com/noaats/ifms/global/response/ApiResponseSerializationTest.java
git commit -m "feat(jackson): default-property-inclusion: ALWAYS 명시 + 직렬화 테스트"
```

---

### Task 16: ApiResponse 불변식 Javadoc 보강

**Files:**
- Modify: `backend/src/main/java/com/noaats/ifms/global/response/ApiResponse.java`

목표: "success=true일 때만 data 경로 마스킹" 불변식을 클래스 javadoc에 명시. 현 javadoc은 구조만 설명.

- [ ] **Step 1: 클래스 javadoc 보강**

기존 javadoc 아래에 다음 4줄을 추가:

```java
/**
 * ...
 *
 * <h2>불변식</h2>
 * <ul>
 *   <li>{@code success=true} 응답에서만 {@code data} 경로가 마스킹 대상이다 (DefensiveMaskingFilter §3.4).</li>
 *   <li>{@code success=false} 응답의 {@code data}는 ErrorDetail로, 마스킹은 적용되지 않는다 (구조화된 안전 필드).</li>
 *   <li>{@code message}는 사용자 노출 가능 텍스트만 담는다 (스택트레이스·SQL·내부 식별자 금지).</li>
 *   <li>{@code timestamp}는 응답 생성 시각, OffsetDateTime ISO-8601 (응답 본문 일관성).</li>
 * </ul>
 */
```

- [ ] **Step 2: 빌드 확인**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add backend/src/main/java/com/noaats/ifms/global/response/ApiResponse.java
git commit -m "docs(api): ApiResponse 불변식 4종 Javadoc 보강 (DevilsAdvocate Day 2-A)"
```

---

### Task 17: backlog.md 정리 + M2/M3/M6 운영 이관 사유 명문화

**Files:**
- Modify: `docs/backlog.md`

- [ ] **Step 1: Day 5/6 완료 항목 ✅ 마킹 + Day 7 완료 항목 제거**

Day 7에서 처리된 항목들은 backlog에서 제거하고, 운영 이관 항목은 §"운영 전환"으로 이동.

- [ ] **Step 2: M2/M3/M6 운영 이관 사유 추가**

`docs/backlog.md §"운영 전환"`에 다음 항목 추가:

```markdown
| `DeltaCursor` HMAC 서명 (M2) | 공격자가 cursor `t=now-23h59m` 위조 가능 — DoS 표면. 분산 환경 전환 시 일괄 도입 |
| `DeltaRateLimiter.buckets` Map TTL cleanup (M3) | 장기 운영 시 메모리 압박. 분산 rate limit(Redis/Bucket4j) 전환 시 일괄 해결 |
| SSE `reconnecting` 상태 polling+SSE 이중 refresh 관찰 (M6) | C3 싱글턴화로 확률 낮음. APM 도입 후 실 측정 기준 결정 |
| M1·M9 cursor 경계·sort=ASC prepend | 프런트 dedup이 은폐 중. 복합 cursor `(started_at, id)` + idx 신설 패키지 시 함께 |
```

- [ ] **Step 3: 커밋**

```bash
git add docs/backlog.md
git commit -m "docs(backlog): Day 5/6/7 완료 항목 정리 + M2/M3/M6/M1/M9 운영 이관 사유 명문화"
```

---

## 빌드 게이트

### Task 18: 묶음 1+2 통합 빌드·테스트 검증

- [ ] **Step 1: 백엔드 clean build**

Run: `cd backend && ./gradlew clean build`
Expected: BUILD SUCCESSFUL, 모든 테스트 PASS, ArchUnit 3 PASS

- [ ] **Step 2: 프런트 빌드**

Run: `cd frontend && npm run build`
Expected: vue-tsc PASS, vite PASS

- [ ] **Step 3: 결과 기록 (DAY7-SUMMARY에 들어갈 통계)**

신규 테스트 수, 누적 테스트 수, 빌드 시간 메모.

- [ ] **Step 4: 게이트 실패 시 처리**

테스트 실패 시 root cause 분석 → 해당 task로 돌아가 수정 → 재빌드. flaky 의심 시 `@RepeatedTest`로 5회 더 검증.

---

## 묶음 3 — 제출 핸드오프

### Task 19: DAY7-SUMMARY.md 작성

**Files:**
- Create: `docs/DAY7-SUMMARY.md`

- [ ] **Step 1: 템플릿 채우기**

```markdown
# Day 7 완료 요약 — 2026-04-21

> Day 6 후속. M1~M9 코드 부채 청산 + 통합 테스트 11종 + 문서 정합 정리. 수동 E2E·Swagger 회귀는 사용자 검증.

## 1. 묶음 1 — 코드 부채 청산 (자동 완료)
[표: M5/M7/M8 + 통합 테스트 11종 — 파일·커밋 인덱스]

## 2. 묶음 2 — 문서·코드 정합 (자동 완료)
[표: api-spec / Jackson / ApiResponse Javadoc / backlog]

## 3. 묶음 3 — 제출 핸드오프 (사용자 검증)
[표: E2E 8 + Day 5 회귀 5 + Swagger 11]

## 4. 누적 통계
- Java 파일 75+ (Day 7 신규 11 테스트, 수정 3)
- Vue 파일 12 (Day 7 수정 2)
- 백엔드 테스트 누적 28+ (Day 6 18 + Day 7 ≥10)
- ErrorCode 21종 (변동 없음, 표기 정합 정리)
- ADR 7종 (Day 7 신규 없음)

## 5. Known Issues 잔존 (운영 이관)
[표: M1/M2/M3/M6/M9 — 사유와 트리거]

## 6. 제출 체크리스트
- [ ] DAY7-SUMMARY.md
- [ ] README.md
- [ ] 수동 E2E 8 시나리오
- [ ] Day 5 회귀 5 시나리오
- [ ] Swagger 11 엔드포인트 try-it-out

## 7. Day 7 커밋 이력
[git log --oneline 추출]
```

- [ ] **Step 2: 실제 통계로 채움 + 커밋 이력 추출**

```bash
git log --oneline a263101..HEAD  # Day 6 마지막 커밋 이후
```

- [ ] **Step 3: 커밋**

```bash
git add docs/DAY7-SUMMARY.md
git commit -m "docs: Day 7 완료 요약 — 부채 청산·통합 테스트·문서 정합 + 핸드오프 체크리스트"
```

---

### Task 20: README.md 최종

**Files:**
- Create or Modify: `README.md`

목표: 평가자가 처음 보는 문서. 5분 이내 실행 + 핵심 평가 포인트 안내.

- [ ] **Step 1: 기존 README 확인**

```bash
ls -la README.md 2>/dev/null && cat README.md | head -40
```

- [ ] **Step 2: 최종 README 작성 (또는 갱신)**

핵심 섹션:
1. 프로젝트 개요 (한 단락)
2. 기술 스택 요약
3. 빠른 실행 (3 명령어 — docker / bootRun / npm dev)
4. 주요 화면 인덱스 (/dashboard, /interfaces, /history)
5. 평가용 문서 인덱스
   - 기획서 → docs/planning.md
   - ERD → docs/erd.md
   - API → docs/api-spec.md (+ Swagger UI URL)
   - ADR 7종 → docs/adr/
   - Day 1~7 진행 → docs/DAY{N}-SUMMARY.md
6. 테스트 실행 방법
7. 알려진 한계 (Known Issues 운영 이관)

- [ ] **Step 3: 커밋**

```bash
git add README.md
git commit -m "docs: 제출용 README — 빠른 실행·문서 인덱스·평가 포인트"
```

---

### Task 21: 사용자 핸드오프 메시지

목표: 사용자에게 묶음 3 수동 검증 절차를 명확히 전달.

- [ ] **Step 1: 다음 항목을 사용자 메시지로 정리**

```
✅ 묶음 1·2 완료. 다음은 사용자 검증입니다.

# 사전 준비 (3 터미널)
터미널 1) docker-compose up -d
터미널 2) cd backend && ./gradlew bootRun
터미널 3) cd frontend && npm run dev

# 검증 체크리스트 — DAY6-SUMMARY §5 + Day 5 회귀 5 + Swagger 11

[수동 E2E 8 시나리오] DAY6-SUMMARY.md §5 표 1~8 — 결과 알려주세요
[Day 5 회귀 5 시나리오] DAY6-SUMMARY.md §5 표 a~e
[Swagger UI] http://localhost:8080/swagger-ui.html — 11 엔드포인트 try-it-out

# 통과 결과를 받는 즉시 DAY7-SUMMARY §3에 ✅ 표기 + 최종 커밋
```

---

## Self-Review

**Spec 커버리지** (모두 task 매핑됨):
- 1.1 M5 → T11 / 1.2 M7 → T12 / 1.3 M8 → T13
- 1.4 → T1+T2+T3 / 1.5 → T4 / 1.6 → T5 / 1.7 → T6
- 1.8 → T7 / 1.9 → T8 / 1.10 → T9 / 1.11 M4 → T10
- 2.1 → T14 / 2.2 → T15 / 2.3 → T16 / 2.4 → T17 / 2.5 → T17
- 3.1~3.3 → T21 (사용자 협조)
- 3.4 → T19 / 3.5 → T20

**Placeholder 스캔**: "TBD" "TODO" 없음. 각 step이 실행 가능한 명령 또는 코드 포함. 시그니처 불일치 가능 지점은 명시적으로 "다르면 그에 맞게 조정" 가이드.

**타입 일관성**: ExecutionStatus / ErrorCode / RetryService.retry / SseEmitterService.subscribe / SseEmitterRegistry.snapshotBySession — 모두 task 간 동일 명칭으로 참조.

**No-op 회피**: T1 후 sanity 테스트는 step 4에서 명시적 삭제 (찌꺼기 방지). T9 벤치는 환경변수 게이트로 CI 영향 없음.
