package com.noaats.ifms.domain.interface_.controller;

import com.noaats.ifms.domain.execution.domain.TriggerType;
import com.noaats.ifms.domain.execution.dto.ExecutionTriggerResponse;
import com.noaats.ifms.domain.execution.service.AsyncExecutionRunner;
import com.noaats.ifms.domain.execution.service.ExecutionTriggerService;
import com.noaats.ifms.domain.interface_.dto.InterfaceConfigDetailResponse;
import com.noaats.ifms.domain.interface_.dto.InterfaceConfigListView;
import com.noaats.ifms.domain.interface_.dto.InterfaceConfigRequest;
import com.noaats.ifms.domain.interface_.service.InterfaceConfigService;
import com.noaats.ifms.global.response.ApiResponse;
import com.noaats.ifms.global.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인터페이스 관리 REST 컨트롤러 (api-spec.md §4.1~§4.5).
 *
 * <h3>ADR-006 Repository 주입 범위</h3>
 * Controller는 Repository를 직접 주입하지 않는다. 엔티티 lookup은 Service가 담당하며,
 * 비동기 실행은 {@link AsyncExecutionRunner#runAsync(Long, Long)}가 configId만 받아 내부에서 조회.
 */
@RestController
@RequestMapping("/api/interfaces")
@RequiredArgsConstructor
@Tag(name = "Interface", description = "인터페이스 관리 API")
public class InterfaceController {

    private final InterfaceConfigService    service;
    private final ExecutionTriggerService   triggerService;
    private final AsyncExecutionRunner      asyncRunner;
    private final ActorContext              actorContext;

    @GetMapping
    @Operation(summary = "인터페이스 목록 조회")
    public ApiResponse<Page<InterfaceConfigListView>> list(
            @Valid @ModelAttribute InterfaceFilterParams filter,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.success(
                service.getAll(filter.getStatus(), filter.getProtocol(), filter.getName(), pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "인터페이스 단건 조회")
    public ApiResponse<InterfaceConfigDetailResponse> getOne(@PathVariable Long id) {
        return ApiResponse.success(service.getById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "인터페이스 등록")
    public ApiResponse<InterfaceConfigDetailResponse> create(
            @Valid @RequestBody InterfaceConfigRequest req) {
        return ApiResponse.success(service.create(req));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "인터페이스 수정 (낙관적 락)")
    public ApiResponse<InterfaceConfigDetailResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody InterfaceConfigRequest req) {
        return ApiResponse.success(service.update(id, req));
    }

    /**
     * 수동 실행 트리거 (api-spec.md §4.5).
     *
     * <ol>
     *   <li>{@link ExecutionTriggerService#trigger} TX1 — advisory lock + RUNNING INSERT + 커밋</li>
     *   <li>TX1 커밋 후 {@link AsyncExecutionRunner#runAsync} 비동기 트리거 (ADR-001 §6 - 1)</li>
     *   <li>응답: 201 + ExecutionTriggerResponse (logId, status=RUNNING)</li>
     * </ol>
     */
    @PostMapping("/{id}/execute")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "인터페이스 수동 실행 트리거")
    public ApiResponse<ExecutionTriggerResponse> execute(@PathVariable Long id,
                                                         HttpServletRequest request) {
        String actor = actorContext.resolveActor(request);
        String ip    = actorContext.resolveClientIp(request);
        String ua    = actorContext.resolveUserAgent(request);

        ExecutionTriggerResponse response = triggerService.trigger(
                id, TriggerType.MANUAL, actor, ip, ua);

        // TX1 커밋 완료 후 비동기 실행. AsyncRunner가 내부에서 configId로 InterfaceConfig 조회.
        asyncRunner.runAsync(response.logId(), id);

        return ApiResponse.success(response);
    }
}
