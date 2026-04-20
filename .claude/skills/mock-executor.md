# Skill: Mock 실행기 구현

## 목적
실제 외부 연동 없이 프로토콜별 실행을 시뮬레이션한다.
모든 실행은 반드시 `ExecutionLog`에 기록되어야 한다.
실행 결과는 SSE로 실시간 상태를 emit한다.

---

## 실행 흐름

```
Controller.execute(id)
    → ExecutionService.trigger(id)          // @Async 비동기
        → ExecutionLog 생성 (RUNNING)
        → SSE emit (RUNNING)
        → MockExecutorFactory.get(protocol)
        → MockExecutor.execute()            // 시뮬레이션
        → ExecutionLog 업데이트 (SUCCESS/FAILED)
        → SSE emit (SUCCESS/FAILED)
```

---

## MockExecutor 인터페이스

```java
public interface MockExecutor {
    ExecutionResult execute(InterfaceConfig config);
}

@Getter
@Builder
public class ExecutionResult {
    private final boolean success;
    private final String responsePayload;
    private final String errorMessage;
    private final long durationMs;
}
```

---

## MockExecutorFactory

```java
@Component
@RequiredArgsConstructor
public class MockExecutorFactory {

    private final RestMockExecutor restExecutor;
    private final SoapMockExecutor soapExecutor;
    private final MqMockExecutor mqExecutor;
    private final BatchMockExecutor batchExecutor;
    private final SftpMockExecutor sftpExecutor;

    public MockExecutor get(ProtocolType protocol) {
        return switch (protocol) {
            case REST  -> restExecutor;
            case SOAP  -> soapExecutor;
            case MQ    -> mqExecutor;
            case BATCH -> batchExecutor;
            case SFTP  -> sftpExecutor;
        };
    }
}
```

---

## 프로토콜별 Mock 구현

### REST Mock

```java
@Component
public class RestMockExecutor implements MockExecutor {

    private static final double FAILURE_RATE = 0.15;  // 15% 실패율
    private final Random random = new Random();

    @Override
    public ExecutionResult execute(InterfaceConfig config) {
        long start = System.currentTimeMillis();

        // 응답 시간 시뮬레이션: 200~800ms
        sleep(200 + random.nextInt(600));

        long duration = System.currentTimeMillis() - start;

        if (random.nextDouble() < FAILURE_RATE) {
            return ExecutionResult.builder()
                    .success(false)
                    .errorMessage("Connection timeout: " + config.getEndpoint())
                    .durationMs(duration)
                    .build();
        }

        return ExecutionResult.builder()
                .success(true)
                .responsePayload("""
                    {"status": "200", "message": "OK", "endpoint": "%s"}
                    """.formatted(config.getEndpoint()))
                .durationMs(duration)
                .build();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

### SOAP Mock

```java
@Component
public class SoapMockExecutor implements MockExecutor {

    @Override
    public ExecutionResult execute(InterfaceConfig config) {
        long start = System.currentTimeMillis();
        sleep(300 + new Random().nextInt(400));

        return ExecutionResult.builder()
                .success(true)
                .responsePayload("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                        <soap:Body>
                            <Response><Status>SUCCESS</Status></Response>
                        </soap:Body>
                    </soap:Envelope>
                    """)
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

### MQ Mock

```java
@Component
public class MqMockExecutor implements MockExecutor {

    @Override
    public ExecutionResult execute(InterfaceConfig config) {
        long start = System.currentTimeMillis();
        sleep(100 + new Random().nextInt(200));

        int messageCount = new Random().nextInt(50) + 1;

        return ExecutionResult.builder()
                .success(true)
                .responsePayload("""
                    {"queue": "%s", "messagesPublished": %d, "status": "DELIVERED"}
                    """.formatted(config.getEndpoint(), messageCount))
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

### BATCH Mock

```java
@Component
public class BatchMockExecutor implements MockExecutor {

    @Override
    public ExecutionResult execute(InterfaceConfig config) {
        long start = System.currentTimeMillis();
        // 배치는 처리 시간이 더 김: 1~3초
        sleep(1000 + new Random().nextInt(2000));

        int totalCount = new Random().nextInt(10000) + 100;
        int successCount = (int)(totalCount * 0.98);  // 98% 성공률

        return ExecutionResult.builder()
                .success(true)
                .responsePayload("""
                    {"totalCount": %d, "successCount": %d, "failCount": %d, "status": "COMPLETED"}
                    """.formatted(totalCount, successCount, totalCount - successCount))
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

### SFTP Mock

```java
@Component
public class SftpMockExecutor implements MockExecutor {

    @Override
    public ExecutionResult execute(InterfaceConfig config) {
        long start = System.currentTimeMillis();
        sleep(500 + new Random().nextInt(1000));

        return ExecutionResult.builder()
                .success(true)
                .responsePayload("""
                    {"host": "%s", "filesTransferred": 1, "bytesTransferred": 204800, "status": "TRANSFER_COMPLETE"}
                    """.formatted(config.getEndpoint()))
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

---

## ExecutionService 핵심 구현

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class ExecutionService {

    private final ExecutionLogRepository logRepository;
    private final InterfaceConfigRepository configRepository;
    private final MockExecutorFactory executorFactory;
    private final SseEmitterService sseEmitterService;

    // 트리거: 즉시 반환, 실제 실행은 비동기
    @Transactional
    public ExecutionLogResponse trigger(Long interfaceId, TriggerType triggerType) {
        InterfaceConfig config = configRepository.findById(interfaceId)
                .orElseThrow(() -> new EntityNotFoundException("Interface not found: " + interfaceId));

        ExecutionLog log = ExecutionLog.builder()
                .interfaceConfig(config)
                .triggeredBy(triggerType)
                .status(ExecutionStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .retryCount(0)
                .build();

        ExecutionLog saved = logRepository.save(log);
        executeAsync(saved.getId(), config);  // 비동기 실행
        return ExecutionLogResponse.from(saved);
    }

    @Async("executorPool")
    @Transactional
    public void executeAsync(Long logId, InterfaceConfig config) {
        ExecutionLog log = logRepository.findById(logId).orElseThrow();

        // SSE: 실행 시작 알림
        sseEmitterService.emit(SseEvent.builder()
                .type("EXECUTION_STARTED")
                .logId(logId)
                .interfaceId(config.getId())
                .status("RUNNING")
                .build());

        try {
            MockExecutor executor = executorFactory.get(config.getProtocol());
            ExecutionResult result = executor.execute(config);

            log.complete(result);  // Entity 내 메서드로 상태 변경
            logRepository.save(log);

            // SSE: 실행 완료 알림
            sseEmitterService.emit(SseEvent.builder()
                    .type(result.isSuccess() ? "EXECUTION_SUCCESS" : "EXECUTION_FAILED")
                    .logId(logId)
                    .interfaceId(config.getId())
                    .status(result.isSuccess() ? "SUCCESS" : "FAILED")
                    .durationMs(result.getDurationMs())
                    .build());

        } catch (Exception e) {
            log.fail(e.getMessage());
            logRepository.save(log);
            sseEmitterService.emit(SseEvent.builder()
                    .type("EXECUTION_FAILED")
                    .logId(logId)
                    .interfaceId(config.getId())
                    .status("FAILED")
                    .errorMessage(e.getMessage())
                    .build());
            log.warn("Execution failed for log {}: {}", logId, e.getMessage());
        }
    }

    // 재처리
    @Transactional
    public ExecutionLogResponse retry(Long logId) {
        ExecutionLog original = logRepository.findById(logId)
                .orElseThrow(() -> new EntityNotFoundException("Log not found: " + logId));

        if (original.getStatus() != ExecutionStatus.FAILED) {
            throw new IllegalStateException("Only FAILED executions can be retried");
        }

        InterfaceConfig config = original.getInterfaceConfig();
        ExecutionLog retryLog = ExecutionLog.builder()
                .interfaceConfig(config)
                .triggeredBy(TriggerType.RETRY)
                .status(ExecutionStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .retryCount(original.getRetryCount() + 1)
                .build();

        ExecutionLog saved = logRepository.save(retryLog);
        executeAsync(saved.getId(), config);
        return ExecutionLogResponse.from(saved);
    }
}
```

---

## AsyncConfig (@Async 스레드 풀)

```java
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "executorPool")
    public Executor executorPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mock-exec-");
        executor.initialize();
        return executor;
    }
}
```

---

## 체크리스트

- [ ] 모든 실행은 `ExecutionLog` 저장 (RUNNING → SUCCESS/FAILED)
- [ ] `@Async` 메서드는 별도 스레드 풀 사용
- [ ] Mock 실행 중 예외 발생 시 FAILED로 기록 (try-catch 필수)
- [ ] SSE emit: RUNNING → SUCCESS/FAILED 순서 보장
- [ ] 재처리는 FAILED 상태에서만 가능 (상태 검증)
- [ ] `retryCount` 증가 추적
