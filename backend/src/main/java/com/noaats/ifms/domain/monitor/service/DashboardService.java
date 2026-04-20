package com.noaats.ifms.domain.monitor.service;

import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository.DashboardProtocolProjection;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository.DashboardRecentFailureProjection;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository.DashboardTotalsProjection;
import com.noaats.ifms.domain.monitor.dto.DashboardResponse;
import com.noaats.ifms.domain.monitor.dto.ProtocolStats;
import com.noaats.ifms.domain.monitor.dto.RecentFailure;
import com.noaats.ifms.domain.monitor.dto.TotalStats;
import com.noaats.ifms.domain.monitor.sse.SseEmitterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대시보드 집계 서비스 (api-spec.md §6.2).
 *
 * <h3>since 기본값</h3>
 * 생략 시 Asia/Seoul 당일 00:00. 명시된 경우 OffsetDateTime 그대로 사용하되,
 * DB 쿼리에는 KST LocalDateTime으로 변환해 전달한다.
 */
@Service
public class DashboardService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int RECENT_FAILURE_LIMIT = 10;

    private final ExecutionLogRepository logRepository;
    private final SseEmitterRegistry emitterRegistry;

    public DashboardService(ExecutionLogRepository logRepository,
                            SseEmitterRegistry emitterRegistry) {
        this.logRepository = logRepository;
        this.emitterRegistry = emitterRegistry;
    }

    @Transactional(readOnly = true)
    public DashboardResponse aggregate(OffsetDateTime sinceOpt) {
        OffsetDateTime since = sinceOpt != null
                ? sinceOpt
                : LocalDate.now(KST).atStartOfDay(KST).toOffsetDateTime();
        LocalDateTime sinceLdt = since.atZoneSameInstant(KST).toLocalDateTime();

        DashboardTotalsProjection t = logRepository.aggregateTotals(sinceLdt);
        TotalStats totals = t != null
                ? new TotalStats(t.getSuccess(), t.getFailed(), t.getRunning(), t.getTotal())
                : new TotalStats(0, 0, 0, 0);

        List<ProtocolStats> byProtocol = new ArrayList<>();
        for (DashboardProtocolProjection p : logRepository.aggregateByProtocol(sinceLdt)) {
            byProtocol.add(new ProtocolStats(
                    p.getProtocol(), p.getSuccess(), p.getFailed(), p.getRunning()));
        }

        List<RecentFailure> failures = new ArrayList<>();
        for (DashboardRecentFailureProjection rf : logRepository.findRecentFailures(sinceLdt, RECENT_FAILURE_LIMIT)) {
            failures.add(new RecentFailure(
                    rf.getId(),
                    rf.getInterfaceName(),
                    rf.getErrorCode(),
                    rf.getStartedAt().atZone(KST).toOffsetDateTime()));
        }

        return new DashboardResponse(
                OffsetDateTime.now(KST),
                since,
                totals,
                byProtocol,
                failures,
                emitterRegistry.totalConnectionCount());
    }
}
