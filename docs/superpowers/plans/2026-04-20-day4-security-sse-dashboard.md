# Day 4 — Security · SSE · Dashboard · ArchUnit · traceId 구현 계획

> **For agentic workers:** 이 플랜은 인라인 실행(`superpowers:executing-plans`) 전제로 작성됐다. 단계별 체크박스(`- [ ]`)로 진행 상태를 추적한다. 모든 구현은 기 확정된 `api-spec.md` v0.7·`erd.md` v0.10·ADR-001/004/005와 Day 3 stub 시그니처에 정합해야 한다. 테스트는 Day 7로 이월(Testcontainers 부담) — 본 계획의 "완료 기준"은 **컴파일 + `bootRun` 정상 기동 + 실 엔드포인트 검증 + ArchUnit 규칙 통과**다.

**Goal:** Day 3에서 정의된 SSE stub·Security stub·ActorContext stub을 본 구현으로 교체하고, 대시보드 집계 API·ArchUnit 3종·traceId 필터를 신규 도입해 Day 5~6 프런트엔드 연동 준비를 마친다.

**Architecture:**
- **Security 완전판**: 세션 기반 인증(`formLogin`) + CSRF(`CookieCsrfTokenRepository.withHttpOnlyFalse()`) + `SecurityFilterChain` 내 필터 순서(`CorsFilter → TraceIdFilter → ConnectionLimitFilter → Spring Security`) + 로그인/로그아웃은 `SecurityConfig`의 `UserDetailsService` in-memory 1명(`operator/operator1234`).
- **SSE 본 구현**: `SseEmitterService`(링버퍼 1,000건/5분 + `Last-Event-ID` 재전송 + `HEARTBEAT` 30초 + 세션·계정 연결 상한 추적), `MonitorController`에서 `GET /api/monitor/stream`·`GET /api/monitor/dashboard` 노출. `ExecutionEventPublisher`는 내부적으로 `SseEmitterService.broadcast`를 호출하도록 stub 교체(호출 지점 무변경).
- **대시보드 집계**: `ExecutionLogRepository`에 네이티브 집계 쿼리 3개 추가(totals·byProtocol·recentFailures) → `DashboardService` → `DashboardResponse` DTO.
- **ArchUnit**: `Repository` 주입 범위 제한, `EntityManager.merge` 금지, `@Modifying` 분리 클래스 전용 규칙 3종(ADR-006 후속).
- **traceId**: `OncePerRequestFilter`로 모든 요청 최상단에서 UUID 발급 + MDC 주입 + `X-Trace-Id` 응답 헤더 + SSE/Security 필터보다 먼저 위치.

**Tech Stack:** Spring Boot 3.3.5 · Spring Security 6.x · Spring MVC · Hibernate 6 · PostgreSQL 16 · ArchUnit 1.3 · SLF4J MDC · Jackson

---

## File Structure

### 신규 파일

| 경로 | 책임 |
|---|---|
| `backend/src/main/java/com/noaats/ifms/global/security/SecurityUserDetailsService.java` | in-memory `UserDetailsService` (`operator`·`admin` 2명 + `PasswordEncoder`) |
| `backend/src/main/java/com/noaats/ifms/global/web/TraceIdFilter.java` | `OncePerRequestFilter` — UUID 발급·MDC·응답 헤더 |
| `backend/src/main/java/com/noaats/ifms/global/config/LogbackConfig.java` | (불필요 — `logback-spring.xml`로 대체) |
| `backend/src/main/resources/logback-spring.xml` | MDC `%X{traceId}` 패턴 콘솔 appender |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEvent.java` | SSE 이벤트 record(id·type·payload·timestamp) |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEventType.java` | enum(CONNECTED/EXECUTION_STARTED/EXECUTION_SUCCESS/EXECUTION_FAILED/EXECUTION_RECOVERED/HEARTBEAT/UNAUTHORIZED/RESYNC_REQUIRED) |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseRingBuffer.java` | 1,000건/5분 링버퍼(thread-safe) |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterRegistry.java` | `(sessionId, clientId) → SseEmitter` 매핑 + 세션·계정 카운트 |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterService.java` | SSE 본 서비스 — 구독·브로드캐스트·재전송·HEARTBEAT |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseProperties.java` | `@ConfigurationProperties` (ringBufferSize, ringBufferTtl, heartbeatInterval, sessionLimit, accountLimit, emitterTimeout) |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/filter/ConnectionLimitFilter.java` | 세션 3 / 계정 10 상한 강제 + 429 응답 |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/controller/MonitorController.java` | `GET /api/monitor/stream`·`GET /api/monitor/dashboard` |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/service/DashboardService.java` | 대시보드 집계 오케스트레이션 |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/dto/DashboardResponse.java` | 응답 DTO(totals·byProtocol·recentFailures·sseConnections) |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/dto/ProtocolStats.java` | 프로토콜별 통계 record |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/dto/TotalStats.java` | totals record |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/dto/RecentFailure.java` | 최근 실패 record |
| `backend/src/test/java/com/noaats/ifms/archunit/ArchitectureTest.java` | ArchUnit 3종 규칙 |

### 수정 파일

| 경로 | 변경 내용 |
|---|---|
| `backend/src/main/java/com/noaats/ifms/global/config/SecurityConfig.java` | permitAll 제거 → 세션 기반 + CSRF + `EventSource` 대응 + FilterChain 순서 + `PasswordEncoder` 빈 |
| `backend/src/main/java/com/noaats/ifms/global/security/ActorContext.java` | `resolveActor` 세션 기반 실 구현(EMAIL/SSO/SYSTEM/ANONYMOUS) |
| `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/ExecutionEventPublisher.java` | stub 로그 유지하되 내부에서 `SseEmitterService.broadcast()` 호출 |
| `backend/src/main/java/com/noaats/ifms/domain/execution/repository/ExecutionLogRepository.java` | 대시보드용 네이티브 쿼리 3개 추가(totals, byProtocol, recentFailures) |
| `backend/build.gradle` | `com.tngtech.archunit:archunit-junit5:1.3.0` 의존성 + `test.dependsOn(archTest)` 없음(같은 task 내 통합) |
| `backend/src/main/resources/application.yml` | `ifms.sse.*`·`ifms.actor.anon-salt` 주입, `logging.pattern.console` 적용 |
| `backend/src/main/java/com/noaats/ifms/IfmsApplication.java` | `@ConfigurationPropertiesScan` 이미 존재 → 변경 없음(확인만) |

---

## Task 1 — ArchUnit 의존성 추가

**Files:** Modify `backend/build.gradle`

- [ ] **Step 1**: `build.gradle` `dependencies` 블록에 아래 추가

```groovy
    // ArchUnit — 아키텍처 경계 규칙 (ADR-006 후속)
    testImplementation 'com.tngtech.archunit:archunit-junit5:1.3.0'
```

- [ ] **Step 2**: `./gradlew dependencies --configuration testRuntimeClasspath | grep archunit` (또는 `./gradlew build --refresh-dependencies -x test`)로 의존성 해결 확인

- [ ] **Step 3**: 커밋

```bash
git add backend/build.gradle
git commit -m "build: add ArchUnit 1.3 for architecture rule tests"
```

---

## Task 2 — `SseProperties` 구성 프로퍼티

**Files:** Create `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseProperties.java`

- [ ] **Step 1**: 레코드로 생성

```java
package com.noaats.ifms.domain.monitor.sse;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE 링버퍼 · 연결 상한 · HEARTBEAT 주기 (api-spec.md §6.1).
 *
 * <p>기본값은 프로토타입 범위. 운영 전환 시 application.yml에서 오버라이드.</p>
 */
@ConfigurationProperties(prefix = "ifms.sse")
public record SseProperties(
        int ringBufferSize,
        Duration ringBufferTtl,
        Duration heartbeatInterval,
        Duration emitterTimeout,
        int sessionLimit,
        int accountLimit) {

    public SseProperties {
        if (ringBufferSize <= 0) ringBufferSize = 1_000;
        if (ringBufferTtl == null) ringBufferTtl = Duration.ofMinutes(5);
        if (heartbeatInterval == null) heartbeatInterval = Duration.ofSeconds(30);
        if (emitterTimeout == null) emitterTimeout = Duration.ofMinutes(3);
        if (sessionLimit <= 0) sessionLimit = 3;
        if (accountLimit <= 0) accountLimit = 10;
    }
}
```

- [ ] **Step 2**: `application.yml`에 기본값 추가

```yaml
ifms:
  sse:
    ring-buffer-size: 1000
    ring-buffer-ttl: PT5M
    heartbeat-interval: PT30S
    emitter-timeout: PT3M
    session-limit: 3
    account-limit: 10
  actor:
    anon-salt: "REQUIRED_SET_ME_DEV"
```

(이미 `ifms.actor.anon-salt`가 있으면 중복 키 제거. `@ConfigurationPropertiesScan`은 Day 3 `IfmsApplication`에 이미 존재 — 추가 설정 불필요)

- [ ] **Step 3**: 커밋 생략(Task 3과 함께).

---

## Task 3 — `SseEvent`·`SseEventType`

**Files:** Create `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEvent.java`, `SseEventType.java`

- [ ] **Step 1**: `SseEventType.java`

```java
package com.noaats.ifms.domain.monitor.sse;

/**
 * SSE 이벤트 타입 (api-spec.md §6.1).
 */
public enum SseEventType {
    CONNECTED,
    EXECUTION_STARTED,
    EXECUTION_SUCCESS,
    EXECUTION_FAILED,
    EXECUTION_RECOVERED,
    HEARTBEAT,
    UNAUTHORIZED,
    RESYNC_REQUIRED
}
```

- [ ] **Step 2**: `SseEvent.java`

```java
package com.noaats.ifms.domain.monitor.sse;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * SSE 단일 이벤트 레코드 (api-spec.md §6.1 이벤트 페이로드).
 *
 * <p>{@code id}는 링버퍼 키로 사용되는 단조 증가 시퀀스.</p>
 */
public record SseEvent(
        long id,
        SseEventType type,
        Map<String, Object> payload,
        OffsetDateTime timestamp) {
}
```

- [ ] **Step 3**: 커밋 생략(Task 2/3/4 묶음)

---

## Task 4 — `SseRingBuffer` 구현

**Files:** Create `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseRingBuffer.java`

- [ ] **Step 1**: 구현

```java
package com.noaats.ifms.domain.monitor.sse;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * SSE 링버퍼 — 1,000건/5분 중 먼저 도달한 조건이 선입 이벤트를 축출 (api-spec.md §6.1).
 *
 * <h3>스레드 안전</h3>
 * 내부 {@link ArrayDeque}는 단일 mutator(외부 호출 전 synchronized 블록)로 보호한다.
 * 이벤트 수는 초당 수천 건을 넘지 않아(Mock 실행 풀 = 8 동시), 단일 lock으로 충분.
 */
@Component
public class SseRingBuffer {

    private final Deque<SseEvent> buffer = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong(0);
    private final int maxSize;
    private final Duration ttl;

    public SseRingBuffer(SseProperties props) {
        this.maxSize = props.ringBufferSize();
        this.ttl = props.ringBufferTtl();
    }

    /** 단조 증가 시퀀스 발급 + 버퍼에 추가. */
    public synchronized SseEvent append(SseEventType type,
                                        java.util.Map<String, Object> payload) {
        evictExpired();
        SseEvent event = new SseEvent(
                sequence.incrementAndGet(),
                type,
                payload,
                OffsetDateTime.now());
        buffer.addLast(event);
        while (buffer.size() > maxSize) {
            buffer.pollFirst();
        }
        return event;
    }

    /**
     * {@code lastEventId} 이후의 이벤트 목록을 반환. 링버퍼에 없으면 빈 리스트 +
     * 호출자(SseEmitterService)가 RESYNC_REQUIRED 발송 결정.
     */
    public synchronized List<SseEvent> since(long lastEventId) {
        evictExpired();
        List<SseEvent> out = new ArrayList<>();
        for (SseEvent e : buffer) {
            if (e.id() > lastEventId) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * 링버퍼에 {@code lastEventId}가 "알려진" 범위인지 확인.
     * 현재 최소 ID보다 작으면 이미 축출된 것 → RESYNC 필요.
     */
    public synchronized boolean isKnown(long lastEventId) {
        evictExpired();
        if (buffer.isEmpty()) return lastEventId == 0L;
        long minId = buffer.peekFirst().id();
        return lastEventId >= minId - 1; // minId-1 = 직전 emit, 재연결 정상
    }

    public synchronized int size() {
        return buffer.size();
    }

    private void evictExpired() {
        OffsetDateTime threshold = OffsetDateTime.now().minus(ttl);
        while (!buffer.isEmpty() && buffer.peekFirst().timestamp().isBefore(threshold)) {
            buffer.pollFirst();
        }
    }
}
```

- [ ] **Step 2**: 커밋 생략.

---

## Task 5 — `SseEmitterRegistry` 구현

**Files:** Create `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterRegistry.java`

- [ ] **Step 1**: 구현

```java
package com.noaats.ifms.domain.monitor.sse;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code (sessionId, clientId)} 키로 SseEmitter를 보관하고 세션·계정 연결 수를 추적한다
 * (api-spec.md §6.1, §2.2).
 *
 * <h3>스레드 안전</h3>
 * {@link ConcurrentHashMap} 사용. 세션별·계정별 카운트는 {@link Set#size()}로 조회.
 */
@Component
public class SseEmitterRegistry {

    /** sessionId → (clientId → emitter) */
    private final ConcurrentMap<String, ConcurrentMap<String, SseEmitter>> bySession = new ConcurrentHashMap<>();

    /** sessionId → actorId */
    private final ConcurrentMap<String, String> sessionActor = new ConcurrentHashMap<>();

    public int sessionConnectionCount(String sessionId) {
        ConcurrentMap<String, SseEmitter> m = bySession.get(sessionId);
        return m == null ? 0 : m.size();
    }

    public int accountConnectionCount(String actorId) {
        if (actorId == null) return 0;
        int total = 0;
        for (Map.Entry<String, String> e : sessionActor.entrySet()) {
            if (actorId.equals(e.getValue())) {
                total += sessionConnectionCount(e.getKey());
            }
        }
        return total;
    }

    public boolean clientIdBoundToOtherSession(String sessionId, String clientId) {
        for (Map.Entry<String, ConcurrentMap<String, SseEmitter>> e : bySession.entrySet()) {
            if (!e.getKey().equals(sessionId) && e.getValue().containsKey(clientId)) {
                return true;
            }
        }
        return false;
    }

    public void register(String sessionId, String actorId, String clientId, SseEmitter emitter) {
        bySession.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(clientId, emitter);
        sessionActor.put(sessionId, actorId);
    }

    public void unregister(String sessionId, String clientId) {
        ConcurrentMap<String, SseEmitter> m = bySession.get(sessionId);
        if (m != null) {
            m.remove(clientId);
            if (m.isEmpty()) {
                bySession.remove(sessionId);
                sessionActor.remove(sessionId);
            }
        }
    }

    /** 전체 활성 연결 수(대시보드 sseConnections). */
    public int totalConnectionCount() {
        int total = 0;
        for (ConcurrentMap<String, SseEmitter> m : bySession.values()) {
            total += m.size();
        }
        return total;
    }

    /** 브로드캐스트용 스냅샷. 호출 시점 모든 emitter 리스트 반환. */
    public java.util.List<SseEmitter> snapshot() {
        java.util.List<SseEmitter> out = new java.util.ArrayList<>();
        for (ConcurrentMap<String, SseEmitter> m : bySession.values()) {
            out.addAll(m.values());
        }
        return out;
    }
}
```

- [ ] **Step 2**: 커밋 생략.

---

## Task 6 — `SseEmitterService` 구현

**Files:** Create `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterService.java`

- [ ] **Step 1**: 구현

```java
package com.noaats.ifms.domain.monitor.sse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 브로드캐스트 본 서비스 (api-spec.md §6.1).
 *
 * <h3>책임</h3>
 * <ul>
 *   <li>{@link #subscribe}: 새 클라이언트 등록 + CONNECTED 이벤트 + Last-Event-ID 재전송</li>
 *   <li>{@link #broadcast}: 링버퍼에 추가 후 모든 활성 emitter로 전송</li>
 *   <li>{@link #heartbeat}: 30초 주기 HEARTBEAT (Scheduled)</li>
 * </ul>
 */
@Slf4j
@Service
public class SseEmitterService {

    private final SseEmitterRegistry registry;
    private final SseRingBuffer ringBuffer;
    private final SseProperties props;

    public SseEmitterService(SseEmitterRegistry registry,
                             SseRingBuffer ringBuffer,
                             SseProperties props) {
        this.registry = registry;
        this.ringBuffer = ringBuffer;
        this.props = props;
    }

    public SseEmitter subscribe(String sessionId,
                                String actorId,
                                String clientId,
                                Long lastEventId) {
        SseEmitter emitter = new SseEmitter(props.emitterTimeout().toMillis());
        emitter.onCompletion(() -> registry.unregister(sessionId, clientId));
        emitter.onTimeout(() -> registry.unregister(sessionId, clientId));
        emitter.onError(t -> registry.unregister(sessionId, clientId));
        registry.register(sessionId, actorId, clientId, emitter);

        sendTo(emitter, ringBuffer.append(SseEventType.CONNECTED, Map.of("clientId", clientId)));

        if (lastEventId != null && lastEventId > 0) {
            if (!ringBuffer.isKnown(lastEventId)) {
                sendTo(emitter, ringBuffer.append(SseEventType.RESYNC_REQUIRED,
                        Map.of("hint", "use ?since= fallback", "lastEventId", lastEventId)));
            } else {
                for (SseEvent e : ringBuffer.since(lastEventId)) {
                    sendTo(emitter, e);
                }
            }
        }
        return emitter;
    }

    public void broadcast(SseEventType type, Map<String, Object> payload) {
        SseEvent event = ringBuffer.append(type, payload);
        List<SseEmitter> all = registry.snapshot();
        for (SseEmitter em : all) {
            sendTo(em, event);
        }
    }

    @Scheduled(fixedRateString = "#{@sseProperties.heartbeatInterval.toMillis()}")
    public void heartbeat() {
        broadcast(SseEventType.HEARTBEAT, Map.of());
    }

    private void sendTo(SseEmitter emitter, SseEvent e) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(e.id()))
                    .name(e.type().name())
                    .data(Map.of(
                            "type", e.type().name(),
                            "payload", e.payload(),
                            "timestamp", e.timestamp().toString())));
        } catch (IOException ex) {
            log.debug("SSE send failed, will be unregistered on callback: {}", ex.getMessage());
        } catch (IllegalStateException ex) {
            log.debug("Emitter already completed: {}", ex.getMessage());
        }
    }
}
```

- [ ] **Step 2**: 커밋

```bash
git add backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseProperties.java \
        backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEvent.java \
        backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEventType.java \
        backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseRingBuffer.java \
        backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterRegistry.java \
        backend/src/main/java/com/noaats/ifms/domain/monitor/sse/SseEmitterService.java \
        backend/src/main/resources/application.yml
git commit -m "feat(sse): add SseEmitterService with ring buffer and heartbeat (api-spec §6.1)"
```

---

## Task 7 — `ExecutionEventPublisher` stub 교체

**Files:** Modify `backend/src/main/java/com/noaats/ifms/domain/monitor/sse/ExecutionEventPublisher.java`

- [ ] **Step 1**: stub의 4개 메서드가 `SseEmitterService.broadcast`를 호출하도록 교체. 호출자(ExecutionTriggerService·ExecutionResultPersister·RetryService·OrphanRunningWatchdog) 시그니처 무변경.

```java
package com.noaats.ifms.domain.monitor.sse;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ExecutionEventPublisher {

    private final SseEmitterService sseService;

    public ExecutionEventPublisher(SseEmitterService sseService) {
        this.sseService = sseService;
    }

    public void publishStarted(ExecutionLog entry) {
        sseService.broadcast(SseEventType.EXECUTION_STARTED, payload(entry));
    }

    public void publishSucceeded(ExecutionLog entry) {
        sseService.broadcast(SseEventType.EXECUTION_SUCCESS, payload(entry));
    }

    public void publishFailed(ExecutionLog entry) {
        sseService.broadcast(SseEventType.EXECUTION_FAILED, payload(entry));
    }

    public void publishRecovered(ExecutionLog entry) {
        sseService.broadcast(SseEventType.EXECUTION_RECOVERED, payload(entry));
    }

    private Map<String, Object> payload(ExecutionLog entry) {
        Map<String, Object> map = new HashMap<>();
        map.put("logId", entry.getId());
        map.put("interfaceId", entry.getInterfaceConfig().getId());
        map.put("interfaceName", entry.getInterfaceConfig().getName());
        map.put("status", entry.getStatus().name());
        map.put("triggeredBy", entry.getTriggeredBy().name());
        map.put("durationMs", entry.getDurationMs());
        map.put("errorCode", entry.getErrorCode() != null ? entry.getErrorCode().name() : null);
        return map;
    }
}
```

- [ ] **Step 2**: 커밋

```bash
git add backend/src/main/java/com/noaats/ifms/domain/monitor/sse/ExecutionEventPublisher.java
git commit -m "refactor(sse): replace stub with SseEmitterService delegate"
```

---

## Task 8 — `ConnectionLimitFilter` 구현

**Files:** Create `backend/src/main/java/com/noaats/ifms/domain/monitor/filter/ConnectionLimitFilter.java`

- [ ] **Step 1**: 구현

```java
package com.noaats.ifms.domain.monitor.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noaats.ifms.domain.monitor.sse.SseEmitterRegistry;
import com.noaats.ifms.domain.monitor.sse.SseProperties;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.global.response.ApiResponse;
import com.noaats.ifms.global.response.ErrorDetail;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SSE 엔드포인트 연결 상한 필터 (api-spec.md §6.1).
 * 세션당 3개, 계정당 10개를 초과하면 429 TOO_MANY_CONNECTIONS.
 */
@Component
@Order(20)
public class ConnectionLimitFilter extends OncePerRequestFilter {

    private static final String STREAM_PATH = "/api/monitor/stream";

    private final SseEmitterRegistry registry;
    private final SseProperties props;
    private final ObjectMapper objectMapper;

    public ConnectionLimitFilter(SseEmitterRegistry registry,
                                 SseProperties props,
                                 ObjectMapper objectMapper) {
        this.registry = registry;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !STREAM_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String sessionId = request.getSession(true).getId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actorId = auth != null ? auth.getName() : null;

        if (registry.sessionConnectionCount(sessionId) >= props.sessionLimit()) {
            writeError(response, "세션당 SSE 연결 상한(" + props.sessionLimit() + ")을 초과했습니다");
            return;
        }
        if (actorId != null && registry.accountConnectionCount(actorId) >= props.accountLimit()) {
            writeError(response, "계정당 SSE 연결 상한(" + props.accountLimit() + ")을 초과했습니다");
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<ErrorDetail> body = ApiResponse.error(
                ErrorCode.TOO_MANY_CONNECTIONS, msg, ErrorDetail.of(ErrorCode.TOO_MANY_CONNECTIONS));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
```

- [ ] **Step 2**: `ApiResponse.error` / `ErrorDetail.of` 시그니처 확인. 없으면 `ApiResponse.fail(...)` 또는 현존 팩토리 사용. 아래 Step 2-a에서 실제 시그니처 확인 후 조정.

- [ ] **Step 2-a**: `ApiResponse.java`, `ErrorDetail.java` 읽어 팩토리 메서드 이름을 확인하고 위 코드 매칭. 예상 수정:
  - `ApiResponse.error(...)`가 없으면 `new ApiResponse<>(false, data, msg, OffsetDateTime.now())` 직접 생성
  - `ErrorDetail.of(ErrorCode)`가 없으면 적절한 생성자 사용

- [ ] **Step 3**: 커밋

```bash
git add backend/src/main/java/com/noaats/ifms/domain/monitor/filter/ConnectionLimitFilter.java
git commit -m "feat(sse): add ConnectionLimitFilter (session 3 / account 10)"
```

---

## Task 9 — `ActorContext` 본 구현

**Files:** Modify `backend/src/main/java/com/noaats/ifms/global/security/ActorContext.java`

- [ ] **Step 1**: 세션 기반 실 구현으로 교체. `application.yml`의 `ifms.actor.anon-salt`를 `@Value` 주입.

```java
package com.noaats.ifms.global.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 세션 기반 actor_id 추출 (api-spec.md §2.3).
 */
@Component
public class ActorContext {

    public static final String SYSTEM = "SYSTEM";
    public static final String ANON_PREFIX = "ANONYMOUS_";
    public static final String EMAIL_PREFIX = "EMAIL:";
    public static final String SSO_PREFIX = "SSO:";

    private final String anonSalt;

    public ActorContext(@Value("${ifms.actor.anon-salt}") String anonSalt) {
        this.anonSalt = anonSalt;
    }

    public String resolveActor(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            String name = auth.getName();
            if (name != null && name.contains("@")) {
                return EMAIL_PREFIX + sha256Hex(name.toLowerCase());
            }
            return EMAIL_PREFIX + sha256Hex(name != null ? name.toLowerCase() : "unknown");
        }
        String ip = resolveClientIp(request);
        return ANON_PREFIX + sha256Hex(anonSalt + (ip != null ? ip : "unknown")).substring(0, 16);
    }

    public String systemActor() {
        return SYSTEM;
    }

    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public String resolveUserAgent(HttpServletRequest request) {
        return request != null ? request.getHeader("User-Agent") : null;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 2**: 기존 `ANONYMOUS_LOCAL` 상수 참조가 있으면 컴파일 에러 → 그 참조를 `resolveActor(null)` 호출로 대체하거나 상수 유지(호환 레이어). 먼저 grep:

```bash
grep -rn "ANONYMOUS_LOCAL" backend/src/main/java
```

- [ ] **Step 3**: 참조 파일 수정(예상: `ExecutionController`, `RetryService`). 이미 `resolveActor(request)`를 호출하는 지점은 무변경.

- [ ] **Step 4**: 커밋

```bash
git add backend/src/main/java/com/noaats/ifms/global/security/ActorContext.java
git commit -m "feat(security): implement ActorContext with session-based actor_id (api-spec §2.3)"
```

---

## Task 10 — `SecurityUserDetailsService` 및 `SecurityConfig` 완전판

**Files:**
- Create `backend/src/main/java/com/noaats/ifms/global/security/SecurityUserDetailsService.java`
- Modify `backend/src/main/java/com/noaats/ifms/global/config/SecurityConfig.java`

- [ ] **Step 1**: `SecurityUserDetailsService.java`

```java
package com.noaats.ifms.global.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 프로토타입용 in-memory UserDetailsService. 2명 하드코딩.
 *
 * <p>운영 전환 시 DB 기반 {@code User} 테이블로 교체 (backlog).</p>
 */
@Service
public class SecurityUserDetailsService implements UserDetailsService {

    private final UserDetails operator;
    private final UserDetails admin;

    public SecurityUserDetailsService(PasswordEncoder encoder) {
        this.operator = User.withUsername("operator@ifms.local")
                .password(encoder.encode("operator1234"))
                .roles("OPERATOR")
                .build();
        this.admin = User.withUsername("admin@ifms.local")
                .password(encoder.encode("admin1234"))
                .roles("ADMIN", "OPERATOR")
                .build();
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (operator.getUsername().equalsIgnoreCase(username)) return operator;
        if (admin.getUsername().equalsIgnoreCase(username)) return admin;
        throw new UsernameNotFoundException("no such user: " + username);
    }
}
```

- [ ] **Step 2**: `SecurityConfig.java` 전체 교체

```java
package com.noaats.ifms.global.config;

import com.noaats.ifms.domain.monitor.filter.ConnectionLimitFilter;
import com.noaats.ifms.global.web.TraceIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Day 4 Security 완전판 (api-spec.md §2).
 *
 * <h3>필터 순서</h3>
 * <ol>
 *   <li>TraceIdFilter (@Order 10) — MDC·헤더</li>
 *   <li>ConnectionLimitFilter (@Order 20) — /api/monitor/stream 전용</li>
 *   <li>Spring Security (UsernamePasswordAuthenticationFilter 등)</li>
 * </ol>
 *
 * <h3>CSRF</h3>
 * CookieCsrfTokenRepository.withHttpOnlyFalse() — SPA가 XSRF-TOKEN 쿠키 읽어 X-XSRF-TOKEN 헤더 동봉.
 *
 * <h3>permitAll 범위</h3>
 * <ul>
 *   <li>POST /login, POST /logout — 폼 인증</li>
 *   <li>/swagger-ui/**, /v3/api-docs/** — 개발 편의(운영 전환 시 제거)</li>
 *   <li>/actuator/health — 기동 확인 (/actuator/** 전체는 차단)</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           TraceIdFilter traceIdFilter,
                                           ConnectionLimitFilter connectionLimitFilter) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers("/login", "/logout"))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(form -> form
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler((req, res, auth) -> res.setStatus(200))
                        .failureHandler((req, res, ex) -> res.setStatus(401)))
                .logout(lo -> lo
                        .logoutUrl("/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(200))
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) -> res.setStatus(401))
                        .accessDeniedHandler((req, res, ex) -> res.setStatus(403)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/logout",
                                "/swagger-ui/**", "/v3/api-docs/**",
                                "/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().authenticated())
                .addFilterBefore(traceIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(connectionLimitFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 3**: `./gradlew compileJava` — 컴파일만 먼저 확인.

- [ ] **Step 4**: 커밋

```bash
git add backend/src/main/java/com/noaats/ifms/global/security/SecurityUserDetailsService.java \
        backend/src/main/java/com/noaats/ifms/global/config/SecurityConfig.java
git commit -m "feat(security): session-based auth + CSRF + operator/admin users (api-spec §2.1)"
```

---

## Task 11 — `TraceIdFilter` 및 logback 패턴

**Files:**
- Create `backend/src/main/java/com/noaats/ifms/global/web/TraceIdFilter.java`
- Create `backend/src/main/resources/logback-spring.xml`

- [ ] **Step 1**: `TraceIdFilter.java`

```java
package com.noaats.ifms.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 요청에 traceId를 부여하고 MDC·응답 헤더로 전파한다.
 *
 * <p>우선순위: 헤더 {@code X-Trace-Id}가 이미 있으면(gateway 주입) 그대로 재사용, 없으면 UUID 생성.
 * 로그 패턴(logback-spring.xml) {@code %X{traceId}}가 자동 인식.</p>
 */
@Component
@Order(10)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        try {
            MDC.put(MDC_KEY, traceId);
            response.setHeader(HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
```

- [ ] **Step 2**: `logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <property name="CONSOLE_LOG_PATTERN"
              value="%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{traceId:-no-trace}] %logger{36} - %msg%n"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${CONSOLE_LOG_PATTERN}</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>

    <logger name="com.noaats.ifms" level="DEBUG"/>
    <logger name="org.hibernate.SQL" level="DEBUG"/>
</configuration>
```

- [ ] **Step 3**: 커밋

```bash
git add backend/src/main/java/com/noaats/ifms/global/web/TraceIdFilter.java \
        backend/src/main/resources/logback-spring.xml
git commit -m "feat(web): add TraceIdFilter with MDC and X-Trace-Id header"
```

---

## Task 12 — 대시보드 집계 쿼리 추가

**Files:** Modify `backend/src/main/java/com/noaats/ifms/domain/execution/repository/ExecutionLogRepository.java`

- [ ] **Step 1**: 기존 interface에 native 쿼리 3개 + projection interface 2개 추가. 파일 상단 import + 메서드 append.

```java
    // ========== Day 4 대시보드 집계 (api-spec.md §6.2) ==========

    /** 총 실행 건수 · 상태별 집계. since 이후 started_at 기준. */
    @Query(value = """
        SELECT
          COUNT(*) FILTER (WHERE status = 'SUCCESS') AS success,
          COUNT(*) FILTER (WHERE status = 'FAILED')  AS failed,
          COUNT(*) FILTER (WHERE status = 'RUNNING') AS running,
          COUNT(*)                                   AS total
        FROM   execution_log
        WHERE  started_at >= :since
        """, nativeQuery = true)
    DashboardTotalsProjection aggregateTotals(@Param("since") LocalDateTime since);

    /** 프로토콜별 상태 집계. */
    @Query(value = """
        SELECT
          ic.protocol                                   AS protocol,
          COUNT(*) FILTER (WHERE el.status = 'SUCCESS') AS success,
          COUNT(*) FILTER (WHERE el.status = 'FAILED')  AS failed,
          COUNT(*) FILTER (WHERE el.status = 'RUNNING') AS running
        FROM   execution_log    el
        JOIN   interface_config ic ON ic.id = el.interface_config_id
        WHERE  el.started_at >= :since
        GROUP  BY ic.protocol
        ORDER  BY ic.protocol
        """, nativeQuery = true)
    List<DashboardProtocolProjection> aggregateByProtocol(@Param("since") LocalDateTime since);

    /** 최근 실패 N건 (interfaceName JOIN). */
    @Query(value = """
        SELECT
          el.id                   AS id,
          ic.name                 AS interfaceName,
          el.error_code           AS errorCode,
          el.started_at           AS startedAt
        FROM   execution_log    el
        JOIN   interface_config ic ON ic.id = el.interface_config_id
        WHERE  el.status     = 'FAILED'
          AND  el.started_at >= :since
        ORDER  BY el.started_at DESC
        LIMIT  :limit
        """, nativeQuery = true)
    List<DashboardRecentFailureProjection> findRecentFailures(
            @Param("since") LocalDateTime since, @Param("limit") int limit);

    interface DashboardTotalsProjection {
        long getSuccess(); long getFailed(); long getRunning(); long getTotal();
    }
    interface DashboardProtocolProjection {
        String getProtocol(); long getSuccess(); long getFailed(); long getRunning();
    }
    interface DashboardRecentFailureProjection {
        Long getId();
        String getInterfaceName();
        String getErrorCode();
        LocalDateTime getStartedAt();
    }
```

- [ ] **Step 2**: 컴파일 확인 `./gradlew compileJava`.

- [ ] **Step 3**: 커밋

```bash
git add backend/src/main/java/com/noaats/ifms/domain/execution/repository/ExecutionLogRepository.java
git commit -m "feat(monitor): add dashboard aggregation queries"
```

---

## Task 13 — 대시보드 DTO 3종

**Files:**
- Create `backend/src/main/java/com/noaats/ifms/domain/monitor/dto/TotalStats.java`
- Create `backend/src/main/java/com/noaats/ifms/domain/monitor/dto/ProtocolStats.java`
- Create `backend/src/main/java/com/noaats/ifms/domain/monitor/dto/RecentFailure.java`
- Create `backend/src/main/java/com/noaats/ifms/domain/monitor/dto/DashboardResponse.java`

- [ ] **Step 1**: 레코드 4종

```java
// TotalStats.java
package com.noaats.ifms.domain.monitor.dto;
public record TotalStats(long success, long failed, long running, long total) {}
```

```java
// ProtocolStats.java
package com.noaats.ifms.domain.monitor.dto;
public record ProtocolStats(String protocol, long success, long failed, long running) {}
```

```java
// RecentFailure.java
package com.noaats.ifms.domain.monitor.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;

public record RecentFailure(
        long id,
        String interfaceName,
        String errorCode,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        OffsetDateTime startedAt) {}
```

```java
// DashboardResponse.java
package com.noaats.ifms.domain.monitor.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;
import java.util.List;

public record DashboardResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        OffsetDateTime generatedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        OffsetDateTime since,
        TotalStats totals,
        List<ProtocolStats> byProtocol,
        List<RecentFailure> recentFailures,
        int sseConnections) {}
```

- [ ] **Step 2**: 커밋 생략(Task 14와 함께).

---

## Task 14 — `DashboardService` 구현

**Files:** Create `backend/src/main/java/com/noaats/ifms/domain/monitor/service/DashboardService.java`

- [ ] **Step 1**: 구현

```java
package com.noaats.ifms.domain.monitor.service;

import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository.DashboardProtocolProjection;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository.DashboardRecentFailureProjection;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository.DashboardTotalsProjection;
import com.noaats.ifms.domain.monitor.dto.DashboardResponse;
import com.noaats.ifms.domain.monitor.dto.ProtocolStats;
import com.noaats.ifms.domain.monitor.dto.RecentFailure;
import com.noaats.ifms.domain.monitor.dto.TotalStats;
import com.noaats.ifms.domain.monitor.sse.SseEmitterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대시보드 집계 서비스 (api-spec.md §6.2).
 */
@Service
public class DashboardService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int RECENT_FAILURE_LIMIT = 10;

    private final ExecutionLogRepository logRepository;
    private final SseEmitterRegistry emitterRegistry;

    public DashboardService(ExecutionLogRepository logRepository,
                            SseEmitterRegistry emitterRegistry) {
        this.logRepository = logRepository;
        this.emitterRegistry = emitterRegistry;
    }

    @Transactional(readOnly = true)
    public DashboardResponse aggregate(OffsetDateTime sinceOpt) {
        OffsetDateTime since = sinceOpt != null
                ? sinceOpt
                : LocalDate.now(KST).atStartOfDay(KST).toOffsetDateTime();
        LocalDateTime sinceLdt = since.atZoneSameInstant(KST).toLocalDateTime();

        DashboardTotalsProjection t = logRepository.aggregateTotals(sinceLdt);
        TotalStats totals = new TotalStats(
                t != null ? t.getSuccess() : 0,
                t != null ? t.getFailed() : 0,
                t != null ? t.getRunning() : 0,
                t != null ? t.getTotal() : 0);

        List<ProtocolStats> byProtocol = new ArrayList<>();
        for (DashboardProtocolProjection p : logRepository.aggregateByProtocol(sinceLdt)) {
            byProtocol.add(new ProtocolStats(p.getProtocol(), p.getSuccess(), p.getFailed(), p.getRunning()));
        }

        List<RecentFailure> failures = new ArrayList<>();
        for (DashboardRecentFailureProjection rf : logRepository.findRecentFailures(sinceLdt, RECENT_FAILURE_LIMIT)) {
            failures.add(new RecentFailure(
                    rf.getId(),
                    rf.getInterfaceName(),
                    rf.getErrorCode(),
                    rf.getStartedAt().atZone(KST).toOffsetDateTime()));
        }

        return new DashboardResponse(
                OffsetDateTime.now(KST),
                since,
                totals,
                byProtocol,
                failures,
                emitterRegistry.totalConnectionCount());
    }
}
```

- [ ] **Step 2**: 커밋 생략(Task 15와 함께).

---

## Task 15 — `MonitorController` 구현

**Files:** Create `backend/src/main/java/com/noaats/ifms/domain/monitor/controller/MonitorController.java`

- [ ] **Step 1**: 구현

```java
package com.noaats.ifms.domain.monitor.controller;

import com.noaats.ifms.domain.monitor.dto.DashboardResponse;
import com.noaats.ifms.domain.monitor.service.DashboardService;
import com.noaats.ifms.domain.monitor.sse.SseEmitterService;
import com.noaats.ifms.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 모니터링 엔드포인트 (api-spec.md §6).
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final SseEmitterService sseService;
    private final DashboardService dashboardService;

    public MonitorController(SseEmitterService sseService, DashboardService dashboardService) {
        this.sseService = sseService;
        this.dashboardService = dashboardService;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(name = "clientId", required = false) String clientId,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId,
            HttpServletRequest request,
            Authentication auth) {
        String sessionId = request.getSession(true).getId();
        String actorId = auth != null ? auth.getName() : "anonymous";

        String cid = (clientId == null || clientId.isBlank())
                ? UUID.randomUUID().toString()
                : clientId;
        if (!UUID_V4.matcher(cid.toLowerCase()).matches()) {
            throw new com.noaats.ifms.global.exception.BusinessException(
                    com.noaats.ifms.global.exception.ErrorCode.VALIDATION_FAILED,
                    "clientId는 UUID v4 포맷이어야 합니다");
        }
        return sseService.subscribe(sessionId, actorId, cid, lastEventId);
    }

    @GetMapping("/dashboard")
    public ApiResponse<DashboardResponse> dashboard(
            @RequestParam(name = "since", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since) {
        return ApiResponse.ok(dashboardService.aggregate(since));
    }
}
```

- [ ] **Step 2**: `ApiResponse.ok(...)` / `BusinessException(ErrorCode, String)` 시그니처 확인. 기존 코드 그대로 호출 가능해야 함(`InterfaceController` 패턴 참고). 불일치 시 즉시 수정.

- [ ] **Step 3**: 커밋

```bash
git add backend/src/main/java/com/noaats/ifms/domain/monitor/dto/ \
        backend/src/main/java/com/noaats/ifms/domain/monitor/service/ \
        backend/src/main/java/com/noaats/ifms/domain/monitor/controller/
git commit -m "feat(monitor): add MonitorController with /stream SSE and /dashboard aggregation"
```

---

## Task 16 — ArchUnit 3종 규칙

**Files:** Create `backend/src/test/java/com/noaats/ifms/archunit/ArchitectureTest.java`

- [ ] **Step 1**: 테스트 클래스 작성

```java
package com.noaats.ifms.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ADR-006 후속 아키텍처 규칙 3종.
 *
 * <ol>
 *   <li><b>Repository 주입 범위</b>: Repository는 {@code ..service..} 또는 {@code ..repository..}
 *       패키지에서만 주입 가능. Controller 직접 주입 금지.</li>
 *   <li><b>EntityManager.merge 금지</b>: 새 Entity persist 경로 일관성을 위해 {@code merge} 호출 금지.</li>
 *   <li><b>@Modifying 사용 범위</b>: {@code @Modifying} 쿼리는 Repository 인터페이스에서만 선언 가능.</li>
 * </ol>
 */
@AnalyzeClasses(
        packages = "com.noaats.ifms",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule repositoriesOnlyInjectedInServiceOrRepository =
            noClasses()
                    .that().resideOutsideOfPackages("..service..", "..repository..", "..config..")
                    .should().dependOnClassesThat().areAssignableTo(JpaRepository.class)
                    .because("Repository는 Service/Repository 레이어에서만 주입되어야 한다 (ADR-006)");

    @ArchTest
    static final ArchRule noEntityManagerMerge =
            noClasses()
                    .should().callMethod(EntityManager.class, "merge", Object.class)
                    .because("EntityManager.merge는 ID 재할당 위험으로 금지 (ADR-006 §4)");

    @ArchTest
    static final ArchRule modifyingOnlyOnRepositories =
            noClasses()
                    .that().resideOutsideOfPackage("..repository..")
                    .should().beAnnotatedWith(Modifying.class)
                    .orShould().containAnyMethodsThat(org.springframework.data.jpa.repository.Modifying.class)
                    .because("@Modifying은 Repository 인터페이스 메서드에만 허용 (ADR-006)");
}
```

- [ ] **Step 1-a**: 3번 규칙의 ArchUnit DSL이 실제로 `containAnyMethodsThat(Class<? extends Annotation>)` 시그니처를 제공하지 않으면 `methods().that().areAnnotatedWith(Modifying.class)` 방식으로 재작성. 실제 ArchUnit 1.3 API 확인 후 아래 대체안 사용:

```java
    @ArchTest
    static final ArchRule modifyingOnlyOnRepositories =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods()
                    .that().areAnnotatedWith(Modifying.class)
                    .should().beDeclaredInClassesThat().resideInAPackage("..repository..")
                    .because("@Modifying은 Repository 인터페이스 메서드에만 허용 (ADR-006)");
```

- [ ] **Step 2**: `./gradlew test --tests ArchitectureTest` 실행 → 3개 모두 PASS.

- [ ] **Step 3**: 규칙 위반이 발견되면 위반 파일 수정(예상: 없음 — Day 3까지 이미 패턴 준수).

- [ ] **Step 4**: 커밋

```bash
git add backend/src/test/java/com/noaats/ifms/archunit/ArchitectureTest.java
git commit -m "test(archunit): add 3 architecture rules (ADR-006 follow-up)"
```

---

## Task 17 — 빌드 + 실 기동 검증

- [ ] **Step 1**: 전체 빌드

```bash
cd c:/project/erp/backend && ./gradlew clean build
```

Expected: `BUILD SUCCESSFUL`. 실패 시 로그의 컴파일 에러·ArchUnit 실패를 해당 Task로 되돌아가 수정.

- [ ] **Step 2**: PostgreSQL 기동 확인

```bash
cd c:/project/erp && docker compose up -d
```

- [ ] **Step 3**: `bootRun` 실행

```bash
cd c:/project/erp/backend && ./gradlew bootRun
```

Expected 로그:
- `AsyncConfig EXECUTION_POOL initialized: core=4 max=8 queue=50 policy=CallerRuns`
- `ApplicationReadyEvent: 잔재 RUNNING 없음`
- `Started IfmsApplication in ...`
- 이후 HEARTBEAT 30초마다 DEBUG 로그 (활성 구독자 0이면 조용할 수 있음)

- [ ] **Step 4**: 로그인 + CSRF 확보

```bash
# 로그인 (JSESSIONID 쿠키 + XSRF-TOKEN 쿠키 획득)
curl -v -c cookies.txt -b cookies.txt \
  -X POST "http://localhost:8080/login" \
  -d "username=operator@ifms.local&password=operator1234"
# Expected: 200 OK
```

- [ ] **Step 5**: 대시보드 호출

```bash
curl -s -b cookies.txt "http://localhost:8080/api/monitor/dashboard" | jq .
```

Expected: `success=true`, `data.totals`, `data.byProtocol=[]` 또는 Day 3 실행 데이터, `data.sseConnections=0`

- [ ] **Step 6**: SSE 구독(별도 터미널)

```bash
curl -N -b cookies.txt "http://localhost:8080/api/monitor/stream?clientId=7f3e9a8b-c2d4-4f6e-8a1b-2c3d4e5f6789"
```

Expected: `event: CONNECTED` 즉시 수신 → 30초 후 `event: HEARTBEAT`

- [ ] **Step 7**: 실행 트리거 → SSE로 STARTED/SUCCESS/FAILED 수신 확인

```bash
# 별도 터미널. Day 3에서 생성된 인터페이스 ID 사용
XSRF=$(grep XSRF-TOKEN cookies.txt | awk '{print $7}')
curl -s -b cookies.txt -H "X-XSRF-TOKEN: $XSRF" \
  -X POST "http://localhost:8080/api/interfaces/1/execute"
```

Expected: SSE 터미널에 `EXECUTION_STARTED` → 200~800ms 후 `EXECUTION_SUCCESS` 또는 `_FAILED`

- [ ] **Step 8**: 연결 상한 검증(429)

```bash
# 같은 세션에서 SSE 4개 연속 연결 시도
for i in 1 2 3 4; do
  curl -s -o /dev/null -w "%{http_code}\n" -b cookies.txt \
    "http://localhost:8080/api/monitor/stream?clientId=$(uuidgen | tr A-Z a-z)" &
done
wait
```

Expected: 3개 200, 4번째 429

- [ ] **Step 9**: traceId 헤더 확인

```bash
curl -v -b cookies.txt "http://localhost:8080/api/monitor/dashboard" 2>&1 | grep -i x-trace-id
```

Expected: `X-Trace-Id: <16-hex>` 응답 헤더

- [ ] **Step 10**: 검증 결과를 DAY4-SUMMARY에 기록할 것. `bootRun` 종료.

---

## Task 18 — DAY4-SUMMARY.md 작성 + backlog 갱신

**Files:**
- Create `docs/DAY4-SUMMARY.md`
- Modify `docs/backlog.md`

- [ ] **Step 1**: `DAY3-SUMMARY.md` 포맷을 참고하여 `DAY4-SUMMARY.md` 작성. 섹션:
  1. 문서 산출물(Day 4는 대부분 코드)
  2. 코드 산출물(22개 내외 파일)
  3. 빌드 + 실 기동 검증
  4. Day 4 디버깅 기록(발견 버그)
  5. 주요 설계 결정
  6. 남은 검증
  7. Day 5 착수 준비

- [ ] **Step 2**: `backlog.md` Day 4 섹션을 완료 체크로 표시하고 Day 5~6 프런트엔드로 이동.

- [ ] **Step 3**: 커밋

```bash
git add docs/DAY4-SUMMARY.md docs/backlog.md
git commit -m "docs: add DAY4-SUMMARY and update backlog after Day 4 completion"
```

---

## Self-Review

**Spec coverage 체크**:

| 사양 | Task |
|---|---|
| api-spec §2.1 세션 인증 + CSRF | Task 10 |
| api-spec §2.2 SSE 필터 순서 | Task 10 (FilterChain) |
| api-spec §2.3 actor_id 규칙 | Task 9 |
| api-spec §3.3 Last-Event-ID 재전송 | Task 6 (subscribe) |
| api-spec §6.1 SSE 스트림 | Task 4·5·6·15 |
| api-spec §6.1 링버퍼 1,000/5분 | Task 4 |
| api-spec §6.1 HEARTBEAT 30초 | Task 6 |
| api-spec §6.1 세션 3/계정 10 | Task 2·5·8 |
| api-spec §6.1 clientId UUID v4 검증 | Task 15 |
| api-spec §6.2 대시보드 | Task 12·13·14·15 |
| ADR-006 ArchUnit 3종 | Task 16 |
| backlog traceId 필터 | Task 11 |

**Placeholder scan**: `TBD`·`TODO`·`implement later` 없음. 모든 Task에 실제 코드 또는 시그니처 명시.

**Type consistency**:
- `SseEmitterRegistry.totalConnectionCount()` → Task 5에서 정의, Task 14에서 호출 ✓
- `ExecutionLogRepository.DashboardTotalsProjection` → Task 12에서 정의, Task 14에서 import ✓
- `ExecutionEventPublisher` 4개 메서드 시그니처 → Task 7은 기존 호출자 무변경 ✓

**이월/비포함**:
- 통합 테스트(Testcontainers) → Day 7 유지
- `SaltValidator` prod 모드 검증 → backlog 유지
- `RetryGuard` Role=ADMIN 분기 → backlog 유지
- `DefensiveMaskingFilter` XML StAX → backlog 유지
- UNAUTHORIZED 세션 만료 emit 자동 감지 → Day 7 이월(감사 로그 엔트리 포함)
- SSE since 폴백 쿼리(`GET /api/executions?since=`) → Day 5~6 프런트 작업 시 함께

---

## Execution Handoff

플랜 완료, `docs/superpowers/plans/2026-04-20-day4-security-sse-dashboard.md`에 저장.

사용자 피드백(`feedback_batch_size`: 한번에 큰 묶음 선호)에 따라 **Inline Execution**(`superpowers:executing-plans`)으로 진행 예정 — 한 세션에서 Task 1~18 전체를 체크포인트 없이 연속 실행.
