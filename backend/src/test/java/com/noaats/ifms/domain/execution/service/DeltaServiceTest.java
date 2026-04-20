package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.domain.TriggerType;
import com.noaats.ifms.domain.execution.dto.DeltaResponse;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import com.noaats.ifms.global.exception.ApiException;
import com.noaats.ifms.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * DeltaService 단위 테스트 — ADR-007 R1·R2.
 *
 * <h3>검증 포인트</h3>
 * <ul>
 *   <li>since 하한(24h) 초과 시 400 DELTA_SINCE_TOO_OLD</li>
 *   <li>rate limit 초과 시 429 DELTA_RATE_LIMITED</li>
 *   <li>limit+1 반환 시 truncated=true + nextCursor 발급</li>
 *   <li>limit 이하 시 truncated=false + nextCursor=null</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DeltaServiceTest {

    @Mock ExecutionLogRepository logRepo;
    @Mock InterfaceConfigRepository configRepo;
    @Mock DeltaRateLimiter limiter;

    Clock clock;
    DeltaService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-20T10:00:00Z"), ZoneId.of("UTC"));
        service = new DeltaService(logRepo, configRepo, limiter, clock,
                Duration.ofHours(24), 500, 1000);
    }

    @Test
    void rejects_since_older_than_24h() {
        when(limiter.tryAcquire(any())).thenReturn(true);
        OffsetDateTime since = OffsetDateTime.parse("2026-04-18T00:00:00+00:00");
        assertThatThrownBy(() -> service.query("actor-A", since, null, 500))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.DELTA_SINCE_TOO_OLD);
    }

    @Test
    void rejects_when_rate_limited() {
        when(limiter.tryAcquire("actor-A")).thenReturn(false);
        OffsetDateTime since = OffsetDateTime.parse("2026-04-20T09:00:00+00:00");
        assertThatThrownBy(() -> service.query("actor-A", since, null, 500))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.DELTA_RATE_LIMITED);
    }

    @Test
    void returns_truncated_when_size_exceeds_limit() {
        when(limiter.tryAcquire(any())).thenReturn(true);
        List<ExecutionLog> fixture = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fixture.add(fakeLog((long) i, LocalDateTime.of(2026, 4, 20, 9, 0, i)));
        }
        when(logRepo.findDeltaSince(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(fixture);
        when(configRepo.findAllById(any())).thenReturn(Collections.emptyList());

        OffsetDateTime since = OffsetDateTime.parse("2026-04-20T09:00:00+00:00");
        DeltaResponse res = service.query("actor-A", since, null, 5);

        assertThat(res.items()).hasSize(5);
        assertThat(res.truncated()).isTrue();
        assertThat(res.nextCursor()).isNotNull();
    }

    @Test
    void truncated_false_when_size_not_exceed_limit() {
        when(limiter.tryAcquire(any())).thenReturn(true);
        List<ExecutionLog> fixture = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            fixture.add(fakeLog((long) i, LocalDateTime.of(2026, 4, 20, 9, 0, i)));
        }
        when(logRepo.findDeltaSince(any(), any())).thenReturn(fixture);
        when(configRepo.findAllById(any())).thenReturn(Collections.emptyList());

        OffsetDateTime since = OffsetDateTime.parse("2026-04-20T09:00:00+00:00");
        DeltaResponse res = service.query("actor-A", since, null, 5);

        assertThat(res.items()).hasSize(3);
        assertThat(res.truncated()).isFalse();
        assertThat(res.nextCursor()).isNull();
    }

    private ExecutionLog fakeLog(Long id, LocalDateTime started) {
        try {
            var ctor = ExecutionLog.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ExecutionLog e = ctor.newInstance();
            setField(e, "id", id);
            setField(e, "status", ExecutionStatus.SUCCESS);
            setField(e, "triggeredBy", TriggerType.MANUAL);
            setField(e, "startedAt", started);
            setField(e, "retryCount", 0);
            return e;
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void setField(Object target, String field, Object value) {
        try {
            var f = findField(target.getClass(), field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
