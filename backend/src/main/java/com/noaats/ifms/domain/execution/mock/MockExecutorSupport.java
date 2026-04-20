package com.noaats.ifms.domain.execution.mock;

import com.noaats.ifms.domain.execution.domain.ExecutionErrorCode;
import com.noaats.ifms.domain.execution.domain.ExecutionResult;
import com.noaats.ifms.domain.execution.domain.PayloadFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock 실행기 공통 헬퍼 — 슬립·실패 주입·요청 메타 구성.
 * 5개 구현체에서 중복되는 Random/sleep 보일러플레이트를 제거한다.
 */
final class MockExecutorSupport {

    private MockExecutorSupport() {}

    /**
     * 의도적 슬립 (Thread.sleep). 인터럽트 시 플래그 복원만 하고 즉시 반환한다.
     * @return 실제 경과한 ms (인터럽트로 단축됐을 수 있음)
     */
    static long sleepRandom(int minMs, int maxMs) {
        long start = System.currentTimeMillis();
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(minMs, maxMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return System.currentTimeMillis() - start;
    }

    /** failureRate 확률로 true. 0.0 = 항상 false, 1.0 = 항상 true. */
    static boolean shouldFail(double failureRate) {
        return ThreadLocalRandom.current().nextDouble() < failureRate;
    }

    /**
     * timeout 시뮬레이션: 인터페이스 timeoutSeconds * 1000ms를 초과한 슬립이 발생하면
     * TIMEOUT_EXCEEDED FAILED를 반환. (실제로는 구현체가 timeout 이내로 슬립하므로 거의 발생 안 함)
     */
    static ExecutionResult timeoutFailure(long durationMs, PayloadFormat fmt) {
        return ExecutionResult.failure(durationMs, fmt,
                ExecutionErrorCode.TIMEOUT_EXCEEDED,
                "외부 응답 시간 초과 (Mock 시뮬레이션)");
    }

    /** 요청 메타를 일관 형식으로 구성. JSON 응답에 함께 노출. */
    static Map<String, Object> requestEcho(String endpoint, String httpMethod) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("endpoint", endpoint);
        if (httpMethod != null) {
            req.put("httpMethod", httpMethod);
        }
        return req;
    }
}
