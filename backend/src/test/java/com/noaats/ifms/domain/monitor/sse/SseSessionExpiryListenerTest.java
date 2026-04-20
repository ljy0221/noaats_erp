package com.noaats.ifms.domain.monitor.sse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.Test;

class SseSessionExpiryListenerTest {

    @Test
    void on_session_destroyed_calls_publishUnauthorizedAndClose() {
        SseEmitterService svc = mock(SseEmitterService.class);
        SseSessionExpiryListener listener = new SseSessionExpiryListener(svc);

        HttpSession session = mock(HttpSession.class);
        when(session.getId()).thenReturn("sess-123");
        HttpSessionEvent event = new HttpSessionEvent(session);

        listener.sessionDestroyed(event);

        verify(svc).publishUnauthorizedAndClose("sess-123");
    }
}
