package com.noaats.ifms.domain.interface_.controller;

import com.noaats.ifms.domain.execution.domain.TriggerType;
import com.noaats.ifms.domain.execution.dto.ExecutionTriggerResponse;
import com.noaats.ifms.domain.execution.service.AsyncExecutionRunner;
import com.noaats.ifms.domain.execution.service.ExecutionTriggerService;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.dto.InterfaceConfigDetailResponse;
import com.noaats.ifms.domain.interface_.dto.InterfaceConfigListView;
import com.noaats.ifms.domain.interface_.dto.InterfaceConfigRequest;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import com.noaats.ifms.domain.interface_.service.InterfaceConfigService;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.global.exception.NotFoundException;
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
 * 엔드포인트 5개:
 * - GET /api/interfaces            목록 조회 (필터 + Pageable)
 * - GET /api/interfaces/{id}        단건 조회
 * - POST /api/interfaces            등록
 * - PATCH /api/interfaces/{id}      수정 (낙관적 락, body.statusChange로 activate/deactivate 통합)
 * - POST /api/interfaces/{id}/execute   수동 실행 (Day 3 ExecutionTriggerService 연동)
 */
@RestController
@RequestMapping("/api/interfaces")
@RequiredArgsConstructor
@Tag(name = "Interface", description = "인터페이스 관리 API")
public class InterfaceController {

    private final InterfaceConfigService    service;
    private final ExecutionTriggerService   triggerService;
    private final AsyncExecutionRunner      asyncRunner;
    private final InterfaceConfigRepository configRepository;
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
     * <h3>흐름</h3>
     * <ol>
     *   <li>{@link ExecutionTriggerService#trigger} TX1 — advisory lock + RUNNING INSERT + 커밋</li>
     *   <li>TX1 커밋 후 {@link AsyncExecutionRunner#runAsync} 비동기 트리거 (ADR-001 §6 - 1)</li>
     *   <li>응답: 201 + ExecutionTriggerResponse (logId, status=RUNNING)</li>
     * </ol>
     *
     * 실 실행 결과는 SSE(Day 4) 또는 GET /api/executions/{logId}로 조회.
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

        // TX1 커밋 완료 후 비동기 실행 (Controller 스레드는 즉시 반환)
        InterfaceConfig config = configRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(ErrorCode.INTERFACE_NOT_FOUND));
        asyncRunner.runAsync(response.logId(), config);

        return ApiResponse.success(response);
    }
}
