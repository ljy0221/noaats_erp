package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.TriggerType;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ScheduleType;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import com.noaats.ifms.global.security.ActorContext;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * CRON 스케줄러 (Day 8). planning.md §3 필수구현 — scheduleType=CRON 인터페이스의 자동 실행.
 *
 * <h3>동작</h3>
 * <ol>
 *   <li>1분 주기 fixedDelay 폴링 (OrphanRunningWatchdog 5분과 충돌 없음 — 본 스케줄러는 advisory lock을 잡지 않는다).</li>
 *   <li>ACTIVE + CRON 인터페이스 전체 조회</li>
 *   <li>각 인터페이스 cronExpression의 next(lastScheduledAt) &lt;= now이면 ExecutionTriggerService.trigger(SCHEDULER) 호출</li>
 *   <li>markScheduled(now)로 lastScheduledAt 갱신 (catch-up 방지)</li>
 * </ol>
 *
 * <h3>설계 결정</h3>
 * <ul>
 *   <li><b>최초 기동(lastScheduledAt=null)</b>: catch-up 안 함. 첫 tick 시점을 기준으로만 다음 발화 계산.</li>
 *   <li><b>동시 실행 방어</b>: ExecutionTriggerService가 이미 advisory lock + uk_log_running 보유 → 본 스케줄러는 가드 없이 호출만.</li>
 *   <li><b>예외 흡수</b>: ConflictException 등은 로그만 남기고 다음 tick으로. 단일 인터페이스 실패가 전체 스케줄러를 죽이지 않음.</li>
 *   <li><b>actor</b>: {@link ActorContext#systemActor()} 사용. SCHEDULER 경로 전용.</li>
 *   <li><b>타임존</b>: cron·now 모두 Asia/Seoul (application.yml hibernate.jdbc.time_zone 정합).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterfaceCronScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final InterfaceConfigRepository configRepository;
    private final ExecutionTriggerService triggerService;
    private final ActorContext actorContext;

    /**
     * 1분 주기 폴링. @Scheduled(zone)은 cron 표현식에만 적용되므로 fixedDelay는 무관.
     * initialDelay로 앱 기동 직후 폭주 방지 (OrphanRunningWatchdog 1분 지연과 동일).
     * <p>
     * @Transactional을 {@code sweep}에 선언해도 Spring AOP는 프록시 경유가 아닌 self-invocation(`tick()`)에
     * 트랜잭션을 적용하지 않으므로(Day 3 project_day3_pitfalls 학습), markScheduled()의 영속화는
     * dirty checking이 아니라 {@link InterfaceConfigRepository#save(Object)} 명시 호출로 보장한다.
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void sweep() {
        tick(LocalDateTime.now(KST));
    }

    /**
     * 테스트용 공개 진입점. @Scheduled 어노테이션 없이 now를 직접 주입.
     */
    public void tick(LocalDateTime now) {
        Specification<InterfaceConfig> activeCron = (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), InterfaceStatus.ACTIVE),
                cb.equal(root.get("scheduleType"), ScheduleType.CRON)
        );
        List<InterfaceConfig> targets = configRepository.findAll(activeCron);
        if (targets.isEmpty()) return;

        log.debug("CRON tick now={} candidates={}", now, targets.size());
        for (InterfaceConfig c : targets) {
            try {
                processOne(c, now);
            } catch (Exception e) {
                log.warn("CRON 스케줄러 단일 인터페이스 처리 실패 id={} name={} cron={} : {}",
                        c.getId(), c.getName(), c.getCronExpression(), e.toString());
            }
        }
    }

    private void processOne(InterfaceConfig c, LocalDateTime now) {
        CronExpression cron;
        try {
            cron = CronExpression.parse(c.getCronExpression());
        } catch (IllegalArgumentException bad) {
            log.warn("잘못된 cron 표현식 id={} cron='{}' : {}", c.getId(), c.getCronExpression(), bad.getMessage());
            return;  // 표현식 수정 시 즉시 재평가 가능하도록 lastScheduledAt 갱신 안 함
        }

        LocalDateTime last = c.getLastScheduledAt();
        if (last == null) {
            // 최초 기동: catch-up 안 함. 첫 tick을 기준점으로만 기록.
            c.markScheduled(now);
            configRepository.save(c);
            return;
        }

        // cron.next(last) — last 이후 가장 가까운 발화 시각
        ZonedDateTime nextZ = cron.next(last.atZone(KST));
        if (nextZ == null) {
            // 더 이상 발화 없음 (예: 특정 연도 한정). 갱신 안 함.
            log.debug("cron 더 이상 발화 없음 id={} cron='{}'", c.getId(), c.getCronExpression());
            return;
        }
        LocalDateTime next = nextZ.toLocalDateTime();
        if (next.isAfter(now)) {
            return;  // 아직 발화 안 됨
        }

        // 발화 시점 경과 → 트리거 호출
        try {
            triggerService.trigger(c.getId(), TriggerType.SCHEDULER, actorContext.systemActor(), null, null);
            log.info("CRON 트리거 id={} name={} cron='{}' last={} now={}",
                    c.getId(), c.getName(), c.getCronExpression(), last, now);
        } catch (RuntimeException e) {
            // DUPLICATE_RUNNING 등은 정상 경합 — 이미 RUNNING이면 이번 tick은 스킵하고 lastScheduledAt만 갱신.
            log.info("CRON 트리거 스킵 id={} cron='{}' 사유={}",
                    c.getId(), c.getCronExpression(), e.getClass().getSimpleName());
        }
        c.markScheduled(now);
        configRepository.save(c);
    }
}
