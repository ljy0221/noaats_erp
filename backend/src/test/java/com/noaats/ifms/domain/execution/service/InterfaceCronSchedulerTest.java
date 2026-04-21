package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noaats.ifms.domain.execution.domain.TriggerType;
import com.noaats.ifms.domain.execution.dto.ExecutionTriggerResponse;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.global.security.ActorContext;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ProtocolType;
import com.noaats.ifms.domain.interface_.domain.ScheduleType;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Day 8: CRON 스케줄러 단위 테스트.
 * ExecutionTriggerService는 mock으로, InterfaceConfig는 직접 인스턴스화.
 * cron 평가와 트리거 위임만 검증. Spring 컨텍스트·DB 우회.
 */
@ExtendWith(MockitoExtension.class)
class InterfaceCronSchedulerTest {

    @Mock InterfaceConfigRepository configRepository;
    @Mock ExecutionTriggerService triggerService;
    @Mock ActorContext actorContext;

    @InjectMocks InterfaceCronScheduler scheduler;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 테스트 헬퍼: 최소 필드로 CRON 타입 InterfaceConfig 생성 */
    private InterfaceConfig cronConfig(Long id, String cron, LocalDateTime lastScheduledAt) {
        InterfaceConfig c = InterfaceConfig.builder()
                .name("test-" + id)
                .protocol(ProtocolType.REST)
                .endpoint("http://mock/test")
                .httpMethod("GET")
                .scheduleType(ScheduleType.CRON)
                .cronExpression(cron)
                .status(InterfaceStatus.ACTIVE)
                .timeoutSeconds(30)
                .maxRetryCount(3)
                .build();
        // 리플렉션으로 id·lastScheduledAt 주입 (테스트 한정)
        try {
            var idField = InterfaceConfig.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);
            if (lastScheduledAt != null) {
                c.markScheduled(lastScheduledAt);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    @Test
    void tick_firesTrigger_whenCronElapsedSinceLastScheduled() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 22, 12, 5, 30);   // 12:05:30 KST
        LocalDateTime last = LocalDateTime.of(2026, 4, 22, 12, 0, 0);   // 직전 12:00

        // cron "0 * * * * *" = 매 분 0초 → 12:01, 12:02, 12:03, 12:04, 12:05 발화 예정
        InterfaceConfig c = cronConfig(10L, "0 * * * * *", last);
        when(actorContext.systemActor()).thenReturn("SYSTEM");
        when(configRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(c));
        when(triggerService.trigger(eq(10L), eq(TriggerType.SCHEDULER), eq("SYSTEM"), isNull(), isNull()))
                .thenReturn(mockTriggerResponse(10L, 999L));

        scheduler.tick(now);

        verify(triggerService, times(1)).trigger(eq(10L), eq(TriggerType.SCHEDULER), eq("SYSTEM"), isNull(), isNull());
        // markScheduled(now) 호출 → lastScheduledAt == now
        assertThat(c.getLastScheduledAt()).isEqualTo(now);
    }

    @Test
    void tick_doesNotFire_whenCronNotYetElapsed() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 22, 12, 0, 30);    // 12:00:30
        LocalDateTime last = LocalDateTime.of(2026, 4, 22, 12, 0, 0);    // 직전 12:00
        // cron "0 * * * * *" 다음 발화는 12:01:00 → 아직 아님
        InterfaceConfig c = cronConfig(11L, "0 * * * * *", last);
        when(configRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(c));

        scheduler.tick(now);

        verify(triggerService, never()).trigger(any(), any(), any(), any(), any());
        assertThat(c.getLastScheduledAt()).isEqualTo(last);
    }

    @Test
    void tick_initializesLastScheduled_whenNullOnFirstTick() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 22, 12, 0, 30);
        InterfaceConfig c = cronConfig(12L, "0 * * * * *", null);   // 최초 기동
        when(configRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(c));

        scheduler.tick(now);

        // 첫 tick은 catch-up 안 함 → trigger 호출 0, lastScheduledAt=now로 초기화
        verify(triggerService, never()).trigger(any(), any(), any(), any(), any());
        assertThat(c.getLastScheduledAt()).isEqualTo(now);
    }

    @Test
    void tick_skipsInterface_whenTriggerThrowsConflict() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 22, 12, 5, 30);
        LocalDateTime last = LocalDateTime.of(2026, 4, 22, 12, 0, 0);
        InterfaceConfig c = cronConfig(13L, "0 * * * * *", last);
        when(actorContext.systemActor()).thenReturn("SYSTEM");
        when(configRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(c));
        when(triggerService.trigger(any(), any(), any(), any(), any()))
                .thenThrow(new com.noaats.ifms.global.exception.ConflictException(
                        com.noaats.ifms.global.exception.ErrorCode.DUPLICATE_RUNNING));

        // 예외는 흡수되어야 함 (스케줄러가 죽으면 안 됨)
        scheduler.tick(now);

        // ConflictException이어도 lastScheduledAt은 갱신 (다음 tick에 또 재시도하면 무한 DUP 로그)
        assertThat(c.getLastScheduledAt()).isEqualTo(now);
    }

    @Test
    void tick_invalidCronExpression_isLoggedAndSkipped() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 22, 12, 5, 30);
        LocalDateTime last = LocalDateTime.of(2026, 4, 22, 12, 0, 0);
        InterfaceConfig c = cronConfig(14L, "this is not a cron", last);
        when(configRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(c));

        scheduler.tick(now);  // 예외 전파 안 됨

        verify(triggerService, never()).trigger(any(), any(), any(), any(), any());
        // 잘못된 cron은 lastScheduledAt도 갱신 안 함 (관리자가 고치면 즉시 재평가 가능)
        assertThat(c.getLastScheduledAt()).isEqualTo(last);
    }

    private static ExecutionTriggerResponse mockTriggerResponse(Long configId, Long logId) {
        // ExecutionTriggerResponse record: (logId, interfaceId, parentLogId, status, triggeredBy, retryCount, startedAt)
        return new ExecutionTriggerResponse(
                logId, configId, null,
                com.noaats.ifms.domain.execution.domain.ExecutionStatus.RUNNING,
                TriggerType.SCHEDULER,
                0,
                java.time.OffsetDateTime.now(KST)
        );
    }
}
