package com.noaats.ifms.global.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비동기 실행 풀 (planning.md §5.5, ADR-001 §6 구현 제약 2).
 *
 * <h3>파라미터 확정값 (ADR-001 §6 - 2)</h3>
 * <ul>
 *   <li>corePoolSize = 4</li>
 *   <li>maxPoolSize = 8</li>
 *   <li>queueCapacity = 50</li>
 *   <li>RejectedExecutionHandler = CallerRunsPolicy
 *       — 큐 포화 시 호출 스레드(=Controller 스레드)가 직접 Mock 실행.
 *       요청 유실 방지가 감사 추적보다 우선 (ADR-001 §3 근거 1)</li>
 * </ul>
 *
 * 큐 포화 알림 기준은 `queue.size() > 40` (운영 모니터링 — 본 프로토타입에서는 로그만).
 */
@Slf4j
@Configuration
public class AsyncConfig {

    public static final String EXECUTION_POOL = "executionPool";

    @Bean(name = EXECUTION_POOL)
    public Executor executionPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("exec-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("AsyncConfig EXECUTION_POOL initialized: core=4 max=8 queue=50 policy=CallerRuns");
        return executor;
    }
}
