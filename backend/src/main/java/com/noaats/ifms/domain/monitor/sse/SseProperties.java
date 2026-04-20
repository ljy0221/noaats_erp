package com.noaats.ifms.domain.monitor.sse;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE 링버퍼 · 연결 상한 · HEARTBEAT 주기 설정 (api-spec.md §6.1).
 *
 * <p>기본값은 프로토타입 범위. 운영 전환 시 application.yml에서 오버라이드.</p>
 *
 * <ul>
 *   <li>ringBufferSize: 링버퍼 최대 항목 수 (기본 1,000)</li>
 *   <li>ringBufferTtl: 링버퍼 보관 시간 (기본 5분)</li>
 *   <li>heartbeatInterval: HEARTBEAT emit 주기 (기본 30초)</li>
 *   <li>emitterTimeout: SseEmitter 타임아웃 (기본 3분, 브라우저 재연결 유도)</li>
 *   <li>sessionLimit: 세션당 연결 상한 (기본 3)</li>
 *   <li>accountLimit: 계정당 연결 상한 (기본 10)</li>
 * </ul>
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
