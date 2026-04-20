package com.noaats.ifms.domain.interface_.service;

import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ProtocolType;
import com.noaats.ifms.domain.interface_.dto.InterfaceConfigDetailResponse;
import com.noaats.ifms.domain.interface_.dto.InterfaceConfigListView;
import com.noaats.ifms.domain.interface_.dto.InterfaceConfigRequest;
import com.noaats.ifms.domain.interface_.dto.InterfaceConfigSnapshot;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import com.noaats.ifms.global.exception.BusinessException;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.global.exception.NotFoundException;
import com.noaats.ifms.global.masking.MaskingRule;
import com.noaats.ifms.global.validation.ConfigJsonValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인터페이스 관리 Service (api-spec.md §4.1~§4.5).
 *
 * 규약 (ADR-006 · spring-domain.md):
 * - 클래스 레벨 `@Transactional(readOnly=true)` 기본 + 쓰기 메서드만 오버라이드
 * - `findByIdOrThrow` 헬퍼로 404 중앙화
 * - `ConfigJsonValidator` 호출은 본 Service에서만 (EntityListener 미사용)
 *     저장본=마스킹본 원칙: Validator → MaskingRule → save 순
 * - Repository 직접 주입은 본 Service가 유일 — ArchUnit 정적 차단 (Day 2-B 테스트 단계)
 *
 * **주의**: 본 Service는 `GlobalExceptionHandler`를 절대 역참조하지 않는다 (단방향 의존 ADR-006).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class InterfaceConfigService {

    private final InterfaceConfigRepository repository;
    private final ConfigJsonValidator configJsonValidator;
    private final MaskingRule maskingRule;

    /* ───────────────────────────── 조회 ───────────────────────────── */

    public Page<InterfaceConfigListView> getAll(
            InterfaceStatus status, ProtocolType protocol, String name, Pageable pageable) {
        Specification<InterfaceConfig> spec =
                InterfaceConfigSpecification.filter(status, protocol, name);
        return repository.findAll(spec, pageable).map(InterfaceConfigListView::from);
    }

    public InterfaceConfigDetailResponse getById(Long id) {
        return InterfaceConfigDetailResponse.from(findByIdOrThrow(id));
    }

    /**
     * 낙관적 락 충돌 시 GlobalExceptionHandler가 호출하는 경량 스냅샷 조회.
     * 원 TX는 이미 롤백된 상태이므로 새 readOnly TX로 최신 값 조회.
     */
    public InterfaceConfigSnapshot findSnapshot(Long id) {
        return repository.findById(id).map(InterfaceConfigSnapshot::from).orElse(null);
    }

    /* ───────────────────────────── 등록 ───────────────────────────── */

    @Transactional
    public InterfaceConfigDetailResponse create(InterfaceConfigRequest req) {
        validateRequiredForCreate(req);
        configJsonValidator.validate(req.getConfigJson());
        Map<String, Object> safeConfig = maskToMap(req.getConfigJson());

        InterfaceConfig entity = InterfaceConfig.builder()
                .name(req.getName())
                .description(req.getDescription())
                .protocol(req.getProtocol())
                .endpoint(req.getEndpoint())
                .httpMethod(req.getHttpMethod())
                .configJson(safeConfig)
                .scheduleType(req.getScheduleType())
                .cronExpression(req.getCronExpression())
                .timeoutSeconds(req.getTimeoutSeconds())
                .maxRetryCount(req.getMaxRetryCount())
                .status(InterfaceStatus.ACTIVE)
                .build();

        // saveAndFlush: uk_ifc_name 제약 위반이 commit까지 지연되지 않고
        // 즉시 DataIntegrityViolationException으로 발생 → GlobalExceptionHandler가 DUPLICATE_NAME 변환
        InterfaceConfig saved = repository.saveAndFlush(entity);
        return InterfaceConfigDetailResponse.from(saved);
    }

    /* ───────────────────────────── 수정 ───────────────────────────── */

    @Transactional
    public InterfaceConfigDetailResponse update(Long id, InterfaceConfigRequest req) {
        if (req.getVersion() == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    Map.of("fieldErrors", List.of(
                            fieldErrorMap("version", null, "PATCH 요청에 version 필수"))));
        }

        InterfaceConfig entity = findByIdOrThrow(id);

        // 명시 version 대조 — flush 지연 없이 조기 실패. JPA @Version 자동 체크와 에러 코드 통일.
        // OptimisticLockingFailureException은 GlobalExceptionHandler에서 409 OPTIMISTIC_LOCK_CONFLICT로 매핑.
        if (!entity.getVersion().equals(req.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "version mismatch: submitted=" + req.getVersion()
                            + " current=" + entity.getVersion());
        }

        // PATCH 시 configJson 처리 규약:
        // - null 또는 빈 Map은 "미변경"으로 해석해 Entity.update()에 null 전달 (기존 저장본 유지).
        //   빈 Map을 그대로 넘기면 Entity.update()가 기존 configJson을 빈 Map으로 덮어쓰는 버그 방지 (Security 지적).
        // - 명시적 "모든 설정 비우기"는 별도 엔드포인트로 처리 (현 범위 밖).
        Map<String, Object> safeConfig = null;
        if (req.getConfigJson() != null && !req.getConfigJson().isEmpty()) {
            configJsonValidator.validate(req.getConfigJson());
            safeConfig = maskToMap(req.getConfigJson());
        }

        entity.update(
                req.getDescription(),
                req.getEndpoint(),
                req.getHttpMethod(),
                safeConfig,
                req.getScheduleType(),
                req.getCronExpression(),
                req.getTimeoutSeconds(),
                req.getMaxRetryCount());

        // PATCH body.statusChange로 activate/deactivate 통합 (api-spec.md §4.4).
        if (req.getStatusChange() != null) {
            switch (req.getStatusChange()) {
                case ACTIVE -> entity.activate();
                case INACTIVE -> entity.deactivate();
            }
        }

        return InterfaceConfigDetailResponse.from(entity);
    }

    /* ───────────────────────────── 내부 헬퍼 ───────────────────────────── */

    private InterfaceConfig findByIdOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.INTERFACE_NOT_FOUND,
                        Map.of("id", id)));
    }

    /**
     * POST 등록 시 필수 4필드 명시 검증 (DevilsAdvocate 지적).
     * DTO 레벨 `@NotNull` 제거 결정(POST/PATCH 공용 DTO)에 따라 Service에서 대신 수행.
     */
    private void validateRequiredForCreate(InterfaceConfigRequest req) {
        List<Map<String, Object>> errors = new ArrayList<>();
        if (req.getName() == null || req.getName().isBlank()) {
            errors.add(fieldErrorMap("name", req.getName(), "필수 입력 항목"));
        }
        if (req.getProtocol() == null) {
            errors.add(fieldErrorMap("protocol", null, "필수 입력 항목"));
        }
        if (req.getEndpoint() == null || req.getEndpoint().isBlank()) {
            errors.add(fieldErrorMap("endpoint", req.getEndpoint(), "필수 입력 항목"));
        }
        if (req.getScheduleType() == null) {
            errors.add(fieldErrorMap("scheduleType", null, "필수 입력 항목"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    Map.of("fieldErrors", errors));
        }
    }

    private Map<String, Object> fieldErrorMap(String field, Object rejectedValue, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("rejectedValue", rejectedValue);
        m.put("message", message);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> maskToMap(Map<String, Object> source) {
        if (source == null) return null;
        return (Map<String, Object>) maskingRule.mask(source);
    }
}
