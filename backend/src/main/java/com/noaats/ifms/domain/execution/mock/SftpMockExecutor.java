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
 * SFTP Mock — 500~1500ms, 10% 실패율. 파일 전송 메타를 응답에 포함.
 */
@Component
public class SftpMockExecutor implements MockExecutor {

    private static final double FAILURE_RATE = 0.10;
    private static final int    MIN_MS       = 500;
    private static final int    MAX_MS       = 1500;

    @Override
    public ExecutionResult execute(InterfaceConfig config) {
        long durationMs = MockExecutorSupport.sleepRandom(MIN_MS, MAX_MS);

        if (MockExecutorSupport.shouldFail(FAILURE_RATE)) {
            return ExecutionResult.failure(durationMs, PayloadFormat.JSON,
                    ExecutionErrorCode.ENDPOINT_UNREACHABLE,
                    "SFTP 호스트 연결 실패: " + config.getEndpoint() + " (Mock 시뮬레이션)");
        }

        long bytes = ThreadLocalRandom.current().nextLong(10_000L, 1_000_001L);

        Map<String, Object> request = MockExecutorSupport.requestEcho(config.getEndpoint(), null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "TRANSFER_COMPLETE");
        response.put("filesTransferred", 1);
        response.put("bytesTransferred", bytes);

        return ExecutionResult.successJson(durationMs, request, response, false);
    }
}
