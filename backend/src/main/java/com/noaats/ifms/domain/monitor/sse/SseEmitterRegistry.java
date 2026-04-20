package com.noaats.ifms.domain.monitor.sse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code (sessionId, clientId)} 키로 SseEmitter를 보관하고 세션·계정 연결 수를 추적한다
 * (api-spec.md §6.1, §2.2).
 *
 * <h3>스레드 안전</h3>
 * 외부 {@link ConcurrentHashMap} + 내부 {@link ConcurrentHashMap} 2단 구조. 세션별·계정별
 * 카운트는 size() 호출로 조회한다. 등록·해제 race는 재시도로 수렴.
 *
 * <h3>clientId 세션 바인딩</h3>
 * 동일 {@code clientId}를 다른 세션이 재사용하면 이벤트 스푸핑 위험. 등록 시 검사.
 */
@Component
public class SseEmitterRegistry {

    /** sessionId → (clientId → emitter) */
    private final ConcurrentMap<String, ConcurrentMap<String, SseEmitter>> bySession =
            new ConcurrentHashMap<>();

    /** sessionId → actorId */
    private final ConcurrentMap<String, String> sessionActor = new ConcurrentHashMap<>();

    public int sessionConnectionCount(String sessionId) {
        ConcurrentMap<String, SseEmitter> m = bySession.get(sessionId);
        return m == null ? 0 : m.size();
    }

    public int accountConnectionCount(String actorId) {
        if (actorId == null) return 0;
        int total = 0;
        for (Map.Entry<String, String> e : sessionActor.entrySet()) {
            if (actorId.equals(e.getValue())) {
                total += sessionConnectionCount(e.getKey());
            }
        }
        return total;
    }

    /** 다른 세션이 동일 clientId로 이미 등록되어 있는지 확인 (스푸핑 방지). */
    public boolean clientIdBoundToOtherSession(String sessionId, String clientId) {
        for (Map.Entry<String, ConcurrentMap<String, SseEmitter>> e : bySession.entrySet()) {
            if (!e.getKey().equals(sessionId) && e.getValue().containsKey(clientId)) {
                return true;
            }
        }
        return false;
    }

    public void register(String sessionId, String actorId, String clientId, SseEmitter emitter) {
        bySession.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(clientId, emitter);
        sessionActor.put(sessionId, actorId);
    }

    public void unregister(String sessionId, String clientId) {
        ConcurrentMap<String, SseEmitter> m = bySession.get(sessionId);
        if (m != null) {
            m.remove(clientId);
            if (m.isEmpty()) {
                bySession.remove(sessionId);
                sessionActor.remove(sessionId);
            }
        }
    }

    /** 전체 활성 연결 수(대시보드 sseConnections 필드). */
    public int totalConnectionCount() {
        int total = 0;
        for (ConcurrentMap<String, SseEmitter> m : bySession.values()) {
            total += m.size();
        }
        return total;
    }

    /** 브로드캐스트용 스냅샷 — 호출 시점의 모든 emitter 리스트를 반환한다. */
    public List<SseEmitter> snapshot() {
        List<SseEmitter> out = new ArrayList<>();
        for (ConcurrentMap<String, SseEmitter> m : bySession.values()) {
            out.addAll(m.values());
        }
        return out;
    }
}
