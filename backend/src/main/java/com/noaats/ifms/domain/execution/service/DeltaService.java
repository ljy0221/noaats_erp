package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.dto.DeltaResponse;
import com.noaats.ifms.domain.execution.dto.ExecutionLogResponse;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import com.noaats.ifms.global.exception.BusinessException;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.global.exception.RateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * delta 조회 서비스 — ADR-007 R1·R2.
 *
 * <h3>동작 규칙</h3>
 * <ul>
 *   <li>R2 rate limit: actor 기준 초과 시 429 DELTA_RATE_LIMITED</li>
 *   <li>R1 since 하한: 서버 현재 시각 기준 24h 이전은 400 DELTA_SINCE_TOO_OLD</li>
 *   <li>R1 커서: 요청에 cursor가 있으면 since보다 우선 (서버 decode 후 효과적 since로 사용)</li>
 *   <li>R1 limit+1 조합: repo 호출 시 limit+1 건 요청 → size>limit면 truncated=true + nextCursor 발급</li>
 *   <li>감사 로그: actor, effectiveSince, 반환 수, truncated, limit을 info 로그로 기록</li>
 * </ul>
 */
@Slf4j
@Service
public class DeltaService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ExecutionLogRepository logRepo;
    private final InterfaceConfigRepository configRepo;
    private final DeltaRateLimiter limiter;
    private final Clock clock;
    private final Duration sinceLowerBound;
    private final int defaultLimit;
    private final int maxLimit;

    public DeltaService(ExecutionLogRepository logRepo,
                        InterfaceConfigRepository configRepo,
                        DeltaRateLimiter limiter,
                        Clock clock,
                        @Value("${ifms.delta.since-lower-bound:PT24H}") Duration sinceLowerBound,
                        @Value("${ifms.delta.default-limit:500}") int defaultLimit,
                        @Value("${ifms.delta.max-limit:1000}") int maxLimit) {
        this.logRepo = logRepo;
        this.configRepo = configRepo;
        this.limiter = limiter;
        this.clock = clock;
        this.sinceLowerBound = sinceLowerBound;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
    }

    @Transactional(readOnly = true)
    public DeltaResponse query(String actor, OffsetDateTime sinceParam, String cursor, Integer limit) {
        String actorHash = hashActor(actor);
        OffsetDateTime effectiveSince = null;
        int effective = (limit == null) ? defaultLimit : Math.min(limit, maxLimit);

        // 실패 경로도 감사 로그 1줄을 남기기 위한 try/finally 패턴 (ADR-007 R2).
        String denyReason = null;
        int returnedCount = 0;
        boolean truncated = false;
        try {
            if (!limiter.tryAcquire(actor)) {
                denyReason = "RATE_LIMITED";
                throw new RateLimitException(ErrorCode.DELTA_RATE_LIMITED);
            }
            effectiveSince = (cursor != null && !cursor.isBlank())
                    ? DeltaCursor.decode(cursor)
                    : sinceParam;
            if (effectiveSince == null) {
                denyReason = "MISSING_SINCE";
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "since 또는 cursor 파라미터가 필요합니다", Map.of());
            }
            OffsetDateTime lower = OffsetDateTime.now(clock).minus(sinceLowerBound);
            if (effectiveSince.isBefore(lower)) {
                denyReason = "SINCE_TOO_OLD";
                throw new BusinessException(
                        ErrorCode.DELTA_SINCE_TOO_OLD,
                        "since는 %s 이후여야 합니다".formatted(lower),
                        Map.of("since", effectiveSince.toString(), "lowerBound", lower.toString()));
            }
            LocalDateTime sinceLocal = effectiveSince.atZoneSameInstant(KST).toLocalDateTime();

            List<ExecutionLog> rows = logRepo.findDeltaSince(sinceLocal, PageRequest.of(0, effective + 1));
            truncated = rows.size() > effective;
            List<ExecutionLog> kept = truncated ? rows.subList(0, effective) : rows;

            // null 가드: interfaceConfig가 null인 엔티티는 id 매핑에서 제외 (방어적 처리).
            List<Long> ids = kept.stream()
                    .map(ExecutionLog::getInterfaceConfig)
                    .filter(Objects::nonNull)
                    .map(InterfaceConfig::getId)
                    .distinct()
                    .toList();
            Map<Long, String> nameMap = configRepo.findAllById(ids).stream()
                    .collect(Collectors.toMap(InterfaceConfig::getId, InterfaceConfig::getName));

            List<ExecutionLogResponse> items = kept.stream()
                    .map(e -> {
                        Long cid = e.getInterfaceConfig() != null ? e.getInterfaceConfig().getId() : null;
                        String name = cid == null ? "(삭제됨)" : nameMap.getOrDefault(cid, "(삭제됨)");
                        return ExecutionLogResponse.of(e, name);
                    })
                    .toList();

            String nextCursor = truncated
                    ? DeltaCursor.encode(
                            kept.get(kept.size() - 1).getStartedAt().atZone(KST).toOffsetDateTime())
                    : null;

            returnedCount = items.size();
            return new DeltaResponse(items, truncated, nextCursor);
        } finally {
            // ADR-007 R2: 성공/실패 모두 감사 로그 1줄.
            String denied = denyReason == null ? "NONE" : denyReason;
            log.info("delta actor={} since={} requested_limit={} effective_limit={} returned_count={} truncated={} denied={}",
                    actorHash, effectiveSince, limit, effective, returnedCount, truncated, denied);
        }
    }

    /** actor 식별자 추가 해시 — SseEmitterService와 동일 방식(경량 hex hash)로 감사 로그 일관성 확보. */
    private static String hashActor(String actor) {
        if (actor == null) return "null";
        return Integer.toHexString(actor.hashCode());
    }
}
