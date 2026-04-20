package com.noaats.ifms.domain.monitor.controller;

import com.noaats.ifms.domain.monitor.dto.DashboardResponse;
import com.noaats.ifms.domain.monitor.service.DashboardService;
import com.noaats.ifms.domain.monitor.sse.SseEmitterService;
import com.noaats.ifms.global.exception.BusinessException;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 모니터링 엔드포인트 (api-spec.md §6).
 *
 * <ul>
 *   <li>{@code GET /api/monitor/stream}    — SSE 실시간 스트림</li>
 *   <li>{@code GET /api/monitor/dashboard} — 집계 (totals·byProtocol·recentFailures)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/monitor")
@Tag(name = "Monitor", description = "모니터링 API (SSE + 대시보드)")
public class MonitorController {

    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final SseEmitterService sseService;
    private final DashboardService dashboardService;

    public MonitorController(SseEmitterService sseService, DashboardService dashboardService) {
        this.sseService = sseService;
        this.dashboardService = dashboardService;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "SSE 실시간 실행 이벤트 스트림")
    public SseEmitter stream(
            @RequestParam(name = "clientId", required = false) String clientId,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId,
            HttpServletRequest request,
            Authentication auth) {
        String sessionId = request.getSession(true).getId();
        String actorId = (auth != null && auth.isAuthenticated()) ? auth.getName() : "anonymous";

        String cid = (clientId == null || clientId.isBlank())
                ? UUID.randomUUID().toString()
                : clientId;
        if (!UUID_V4.matcher(cid.toLowerCase()).matches()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "clientId는 UUID v4 포맷이어야 합니다",
                    Map.of("clientId", cid));
        }
        return sseService.subscribe(sessionId, actorId, cid, lastEventId);
    }

    @GetMapping("/dashboard")
    @Operation(summary = "대시보드 집계 조회")
    public ApiResponse<DashboardResponse> dashboard(
            @RequestParam(name = "since", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since) {
        return ApiResponse.success(dashboardService.aggregate(since));
    }
}
