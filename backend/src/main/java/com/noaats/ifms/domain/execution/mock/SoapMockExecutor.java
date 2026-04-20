package com.noaats.ifms.domain.execution.mock;

import com.noaats.ifms.domain.execution.domain.ExecutionErrorCode;
import com.noaats.ifms.domain.execution.domain.ExecutionResult;
import com.noaats.ifms.domain.execution.domain.PayloadFormat;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import org.springframework.stereotype.Component;

/**
 * SOAP Mock — 300~700ms, 8% 실패율, XML 원문 보존.
 * payload_format=XML 분기로 ExecutionLog의 *_xml 컬럼에 저장된다.
 */
@Component
public class SoapMockExecutor implements MockExecutor {

    private static final double FAILURE_RATE = 0.08;
    private static final int    MIN_MS       = 300;
    private static final int    MAX_MS       = 700;

    private static final String REQUEST_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <fss:reportDailySummary xmlns:fss="http://fss.or.kr/schemas">
                  <endpoint>%s</endpoint>
                </fss:reportDailySummary>
              </soap:Body>
            </soap:Envelope>
            """;

    private static final String RESPONSE_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <fss:reportDailySummaryResponse xmlns:fss="http://fss.or.kr/schemas">
                  <result>SUCCESS</result>
                  <transactionId>SOAP-%s</transactionId>
                </fss:reportDailySummaryResponse>
              </soap:Body>
            </soap:Envelope>
            """;

    @Override
    public ExecutionResult execute(InterfaceConfig config) {
        long durationMs = MockExecutorSupport.sleepRandom(MIN_MS, MAX_MS);

        if (MockExecutorSupport.shouldFail(FAILURE_RATE)) {
            return ExecutionResult.failure(durationMs, PayloadFormat.XML,
                    ExecutionErrorCode.REMOTE_SERVER_ERROR,
                    "SOAP Fault: " + config.getEndpoint() + " (Mock 시뮬레이션)");
        }

        String requestXml  = REQUEST_TEMPLATE.formatted(config.getEndpoint());
        String responseXml = RESPONSE_TEMPLATE.formatted(System.nanoTime());

        return ExecutionResult.successXml(durationMs, requestXml, responseXml, false);
    }
}
