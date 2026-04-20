package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DeltaRateLimiterTest {

    @Test
    void allows_up_to_10_in_window_then_rejects_11th() {
        TestClock clock = new TestClock(Instant.parse("2026-04-20T10:00:00Z"));
        DeltaRateLimiter limiter = new DeltaRateLimiter(clock, Duration.ofSeconds(60), 10);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("actor-A")).isTrue();
        }
        assertThat(limiter.tryAcquire("actor-A")).isFalse();
    }

    @Test
    void different_actors_have_independent_buckets() {
        TestClock clock = new TestClock(Instant.parse("2026-04-20T10:00:00Z"));
        DeltaRateLimiter limiter = new DeltaRateLimiter(clock, Duration.ofSeconds(60), 10);
        for (int i = 0; i < 10; i++) limiter.tryAcquire("A");
        assertThat(limiter.tryAcquire("B")).isTrue();
    }

    @Test
    void window_sliding_frees_oldest_after_60s() {
        TestClock clock = new TestClock(Instant.parse("2026-04-20T10:00:00Z"));
        DeltaRateLimiter limiter = new DeltaRateLimiter(clock, Duration.ofSeconds(60), 10);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("A");
        }
        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire("A")).isTrue();
    }

    static class TestClock extends Clock {
        private Instant now;
        TestClock(Instant start) { this.now = start; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }
}
