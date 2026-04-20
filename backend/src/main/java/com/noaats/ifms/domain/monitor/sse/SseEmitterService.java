package com.noaats.ifms.domain.monitor.sse;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 브로드캐스트 본 서비스 (api-spec.md §6.1).
 *
 * <h3>책임</h3>
 * <ul>
 *   <li>{@link #subscribe}: 새 클라이언트 등록 + CONNECTED 이벤트 + Last-Event-ID 재전송</li>
 *   <li>{@link #broadcast}: 링버퍼에 추가 후 모든 활성 emitter로 전송</li>
 *   <li>{@link #heartbeat}: 설정된 주기로 HEARTBEAT 발송 (기본 30초)</li>
 * </ul>
 *
 * <h3>예외 처리</h3>
 * {@link SseEmitter#send} 실패 시 콜백(onError·onCompletion)이 unregister를 호출하므로
 * 여기서는 로그만 남기고 진행한다.
 */
@Slf4j
@Service
public class SseEmitterService {

    private final SseEmitterRegistry registry;
    private final SseRingBuffer ringBuffer;
    private final SseProperties props;

    public SseEmitterService(SseEmitterRegistry registry,
                             SseRingBuffer ringBuffer,
                             SseProperties props) {
        this.registry = registry;
        this.ringBuffer = ringBuffer;
        this.props = props;
    }

    /**
     * 새 클라이언트 구독.
     *
     * <p>Last-Event-ID가 링버퍼 범위에 있으면 누락 이벤트를 재전송, 범위를 벗어났으면
     * RESYNC_REQUIRED를 1건 발송해 클라이언트가 {@code ?since=} 폴백을 사용하도록 유도.</p>
     */
    public SseEmitter subscribe(String sessionId,
                                String actorId,
                                String clientId,
                                Long lastEventId) {
        SseEmitter emitter = new SseEmitter(props.emitterTimeout().toMillis());
        emitter.onCompletion(() -> {
            registry.unregister(sessionId, clientId);
            log.debug("SSE emitter completed sessionId={} clientId={}", sessionId, clientId);
        });
        emitter.onTimeout(() -> {
            registry.unregister(sessionId, clientId);
            emitter.complete();
            log.debug("SSE emitter timeout sessionId={} clientId={}", sessionId, clientId);
        });
        emitter.onError(t -> {
            registry.unregister(sessionId, clientId);
            log.debug("SSE emitter error sessionId={} clientId={} cause={}",
                    sessionId, clientId, t.getMessage());
        });
        registry.register(sessionId, actorId, clientId, emitter);

        Map<String, Object> connectedPayload = new HashMap<>();
        connectedPayload.put("clientId", clientId);
        sendTo(emitter, ringBuffer.append(SseEventType.CONNECTED, connectedPayload));

        if (lastEventId != null && lastEventId > 0) {
            if (!ringBuffer.isKnown(lastEventId)) {
                Map<String, Object> resync = new HashMap<>();
                resync.put("hint", "use ?since= fallback");
                resync.put("lastEventId", lastEventId);
                sendTo(emitter, ringBuffer.append(SseEventType.RESYNC_REQUIRED, resync));
            } else {
                for (SseEvent e : ringBuffer.since(lastEventId)) {
                    sendTo(emitter, e);
                }
            }
        }
        return emitter;
    }

    /** 모든 활성 emitter에게 이벤트를 브로드캐스트하고 링버퍼에도 추가. */
    public void broadcast(SseEventType type, Map<String, Object> payload) {
        SseEvent event = ringBuffer.append(type, payload);
        List<SseEmitter> all = registry.snapshot();
        for (SseEmitter em : all) {
            sendTo(em, event);
        }
    }

    @Scheduled(fixedRateString = "${ifms.sse.heartbeat-interval}")
    public void heartbeat() {
        if (registry.totalConnectionCount() == 0) {
            return;
        }
        broadcast(SseEventType.HEARTBEAT, Map.of());
    }

    private void sendTo(SseEmitter emitter, SseEvent e) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("type", e.type().name());
            data.put("payload", e.payload());
            data.put("timestamp", e.timestamp().toString());
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(e.id()))
                    .name(e.type().name())
                    .data(data));
        } catch (IOException ex) {
            log.debug("SSE send IO failure: {}", ex.getMessage());
        } catch (IllegalStateException ex) {
            log.debug("Emitter already completed: {}", ex.getMessage());
        }
    }
}
