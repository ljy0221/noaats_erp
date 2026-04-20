package com.noaats.ifms.domain.execution.mock;

import com.noaats.ifms.domain.interface_.domain.ProtocolType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 프로토콜 → Mock 실행기 라우터 (mock-executor 스킬 §3).
 *
 * 5개 Mock 구현체를 enum 분기로 선택. ProtocolType이 늘어나면
 * switch가 컴파일러에 의해 미커버 case를 강제 노출 (Java 17 sealed-like 효과).
 */
@Component
@RequiredArgsConstructor
public class MockExecutorFactory {

    private final RestMockExecutor  restExecutor;
    private final SoapMockExecutor  soapExecutor;
    private final MqMockExecutor    mqExecutor;
    private final BatchMockExecutor batchExecutor;
    private final SftpMockExecutor  sftpExecutor;

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
