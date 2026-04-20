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
 * BATCH Mock — 1~3초 슬립 (배치 특성), 3% 실패율.
 * 응답에 처리 건수·성공 건수·실패 건수를 포함.
 */
@Component
public class BatchMockExecutor implements MockExecutor {

    private static final double FAILURE_RATE = 0.03;
    private static final int    MIN_MS       = 1000;
    private static final int    MAX_MS       = 3000;

    @Override
    public ExecutionResult execute(InterfaceConfig config) {
        long durationMs = MockExecutorSupport.sleepRandom(MIN_MS, MAX_MS);

        if (MockExecutorSupport.shouldFail(FAILURE_RATE)) {
            return ExecutionResult.failure(durationMs, PayloadFormat.JSON,
                    ExecutionErrorCode.MOCK_INJECTED_FAILURE,
                    "배치 처리 중단: " + config.getEndpoint() + " (Mock 시뮬레이션)");
        }

        int total      = ThreadLocalRandom.current().nextInt(100, 10_001);
        int successful = (int) (total * 0.98);
        int failed     = total - successful;

        Map<String, Object> request = MockExecutorSupport.requestEcho(config.getEndpoint(), null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "COMPLETED");
        response.put("totalCount", total);
        response.put("successCount", successful);
        response.put("failCount", failed);

        return ExecutionResult.successJson(durationMs, request, response, false);
    }
}
