package com.noaats.ifms.domain.execution.controller;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.dto.DeltaResponse;
import com.noaats.ifms.domain.execution.dto.ExecutionListParams;
import com.noaats.ifms.domain.execution.dto.ExecutionLogResponse;
import com.noaats.ifms.domain.execution.dto.ExecutionTriggerResponse;
import com.noaats.ifms.domain.execution.service.AsyncExecutionRunner;
import com.noaats.ifms.domain.execution.service.DeltaService;
import com.noaats.ifms.domain.execution.service.ExecutionQueryService;
import com.noaats.ifms.domain.execution.service.RetryService;
import com.noaats.ifms.global.response.ApiResponse;
import com.noaats.ifms.global.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실행 이력 관련 REST 컨트롤러 (api-spec.md §5).
 *
 * <h3>ADR-006 Repository 주입 범위</h3>
 * Controller는 Repository를 직접 주입하지 않는다. 엔티티 lookup은 Service가 담당하며,
 * 비동기 실행은 {@link AsyncExecutionRunner#runAsync(Long, Long)}가 configId만 받아 내부에서 조회.
 */
@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
@Tag(name = "Execution", description = "실행 이력 API")
public class ExecutionController {

    private final RetryService            retryService;
    private final AsyncExecutionRunner    asyncRunner;
    private final ActorContext            actorContext;
    private final ExecutionQueryService   queryService;
    private final DeltaService            deltaService;

    /**
     * 실패 로그 재처리 (api-spec §5.3, ADR-005).
     *
     * TX1(RetryService)이 검증·lock·INSERT를 담당하고, 커밋 후 AsyncExecutionRunner가
     * 동일 Mock 파이프라인으로 TX2를 처리한다.
     */
    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "실패 로그 재처리 (체인 루트 actor 기준 권한 검증)")
    public ApiResponse<ExecutionTriggerResponse> retry(@PathVariable Long id,
                                                       HttpServletRequest request) {
        String actor = actorContext.resolveActor(request);
        String ip    = actorContext.resolveClientIp(request);
        String ua    = actorContext.resolveUserAgent(request);

        ExecutionTriggerResponse response = retryService.retry(id, actor, ip, ua);

        // TX1 커밋 후 비동기 실행. 응답의 interfaceId를 AsyncRunner에 전달.
        asyncRunner.runAsync(response.logId(), response.interfaceId());

        return ApiResponse.success(response);
    }

    @GetMapping
    @Operation(summary = "실행 이력 리스트 조회 (페이지네이션+필터)")
    public ApiResponse<org.springframework.data.domain.Page<ExecutionLogResponse>> list(
            @RequestParam(required = false) ExecutionStatus status,
            @RequestParam(required = false) Long interfaceConfigId,
            @PageableDefault(size = 20, sort = "startedAt",
                    direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(
                queryService.list(new ExecutionListParams(status, interfaceConfigId, pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "실행 로그 단건 조회")
    public ApiResponse<ExecutionLogResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(queryService.detail(id));
    }

    @GetMapping("/delta")
    @Operation(summary = "실행 로그 델타 조회 (RESYNC 폴백, ADR-007)")
    public ApiResponse<DeltaResponse> delta(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        String actor = actorContext.resolveActor(request);
        return ApiResponse.success(deltaService.query(actor, since, cursor, limit));
    }
}
