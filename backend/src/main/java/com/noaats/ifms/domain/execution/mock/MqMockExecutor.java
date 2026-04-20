package com.noaats.ifms.domain.execution.mock;

import com.noaats.ifms.domain.execution.domain.ExecutionErrorCode;
import com.noaats.ifms.domain.execution.domain.ExecutionResult;
import com.noaats.ifms.domain.execution.domain.PayloadFormat;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * MQ Mock — 100~300ms, 5% 실패율. 메시지 발행 건수를 응답에 포함.
 */
@Component
public class MqMockExecutor implements MockExecutor {

    private static final double FAILURE_RATE = 0.05;
    private static final int    MIN_MS       = 100;
    private static final int    MAX_MS       = 300;

    @Override
    public ExecutionResult execute(InterfaceConfig config) {
        long durationMs = MockExecutorSupport.sleepRandom(MIN_MS, MAX_MS);

        if (MockExecutorSupport.shouldFail(FAILURE_RATE)) {
            return ExecutionResult.failure(durationMs, PayloadFormat.JSON,
                    ExecutionErrorCode.ENDPOINT_UNREACHABLE,
                    "MQ Broker 연결 실패: " + config.getEndpoint() + " (Mock 시뮬레이션)");
        }

        int messages = ThreadLocalRandom.current().nextInt(1, 51);

        Map<String, Object> request = MockExecutorSupport.requestEcho(config.getEndpoint(), null);
        request.put("queue", config.getEndpoint());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "DELIVERED");
        response.put("messagesPublished", messages);
        response.put("brokerAck", true);

        return ExecutionResult.successJson(durationMs, request, response, false);
    }
}
