package com.noaats.ifms.domain.execution.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * actor 기준 슬라이딩 윈도우 rate limiter (in-memory). ADR-007 R2.
 * <p>분산 rate limit은 운영 이관 대상, 프로토타입은 단일 인스턴스 전제.</p>
 */
@Component
public class DeltaRateLimiter {

    private final Clock clock;
    private final Duration window;
    private final int capacity;
    private final Map<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    public DeltaRateLimiter(Clock clock,
                            @Value("${ifms.delta.rate-window:PT60S}") Duration window,
                            @Value("${ifms.delta.rate-capacity:10}") int capacity) {
        this.clock = clock;
        this.window = window;
        this.capacity = capacity;
    }

    public boolean tryAcquire(String actorKey) {
        Instant now = clock.instant();
        Deque<Instant> dq = buckets.computeIfAbsent(actorKey, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && dq.peekFirst().isBefore(now.minus(window))) {
                dq.pollFirst();
            }
            if (dq.size() >= capacity) return false;
            dq.addLast(now);
            return true;
        }
    }
}
