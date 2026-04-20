package com.noaats.ifms.domain.execution.mock;

import com.noaats.ifms.domain.execution.domain.ExecutionErrorCode;
import com.noaats.ifms.domain.execution.domain.ExecutionResult;
import com.noaats.ifms.domain.execution.domain.PayloadFormat;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * REST Mock — 200~800ms 슬립, 15% 실패율.
 * 응답에 가상의 응답 헤더·바디를 JSON으로 구성한다.
 */
@Component
public class RestMockExecutor implements MockExecutor {

    private static final double FAILURE_RATE = 0.15;
    private static final int    MIN_MS       = 200;
    private static final int    MAX_MS       = 800;

    @Override
    public ExecutionResult execute(InterfaceConfig config) {
        long durationMs = MockExecutorSupport.sleepRandom(MIN_MS, MAX_MS);

        if (MockExecutorSupport.shouldFail(FAILURE_RATE)) {
            return ExecutionResult.failure(durationMs, PayloadFormat.JSON,
                    ExecutionErrorCode.REMOTE_SERVER_ERROR,
                    "REST 호출 실패: " + config.getEndpoint() + " (Mock 시뮬레이션)");
        }

        Map<String, Object> request = MockExecutorSupport.requestEcho(
                config.getEndpoint(), config.getHttpMethod());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("httpStatus", 200);
        response.put("contentType", "application/json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("transactionId", "TX-" + System.nanoTime());
        body.put("processedAt", System.currentTimeMillis());
        response.put("body", body);

        return ExecutionResult.successJson(durationMs, request, response, false);
    }
}
