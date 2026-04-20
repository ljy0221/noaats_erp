package com.noaats.ifms.domain.monitor.sse;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.stereotype.Component;

/** 세션 만료 시 해당 세션의 모든 SSE emitter에 UNAUTHORIZED 이벤트 송출. ADR-007 R5. */
@Component
public class SseSessionExpiryListener implements HttpSessionListener {

    private final SseEmitterService service;

    public SseSessionExpiryListener(SseEmitterService service) {
        this.service = service;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        service.publishUnauthorizedAndClose(se.getSession().getId());
    }
}
