package com.noaats.ifms.domain.execution.controller;

import com.noaats.ifms.domain.execution.dto.ExecutionTriggerResponse;
import com.noaats.ifms.domain.execution.service.AsyncExecutionRunner;
import com.noaats.ifms.domain.execution.service.RetryService;
import com.noaats.ifms.global.response.ApiResponse;
import com.noaats.ifms.global.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
