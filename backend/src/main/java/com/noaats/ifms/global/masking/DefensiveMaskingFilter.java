package com.noaats.ifms.global.masking;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.noaats.ifms.global.config.PaginationConstants;
import com.noaats.ifms.global.response.ApiResponse;
import com.noaats.ifms.global.response.ErrorDetail;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Defense-in-Depth 2차 방어 (api-spec.md §3.4).
 *
 * 적용 대상: {@link ApiResponse} 래핑 응답만. SSE, /actuator, /error, 정적 리소스 미적용.
 * (Day 4 SecurityConfig 완전판에서 /actuator 차단 + whitelabel=false 병행)
 *
 * 규약:
 * - `body.data instanceof ErrorDetail`이면 skip — 에러 메시지 왜곡 방지
 * - `Page<T>.content[]` 재귀 순회
 * - `MaskingRule.MAX_DEPTH=10` / 노드 10,000 가드 상속
 * - 동일 advice에서 raw query param `size`를 비교해 `X-Size-Clamped: true` 헤더 주입
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class DefensiveMaskingFilter implements ResponseBodyAdvice<ApiResponse<?>> {

    private final MaskingRule maskingRule;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converterType) {
        return ApiResponse.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public ApiResponse<?> beforeBodyWrite(
            ApiResponse<?> body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {

        injectSizeClampedHeader(request, response);

        if (body == null || body.getData() == null) return body;

        // ErrorDetail skip — 에러 메시지 왜곡(예: "API 키가 만료됨" → "API ***MASKED***가 만료됨") 방지
        if (body.getData() instanceof ErrorDetail) return body;

        Object maskedData = maskPayload(body.getData());
        return rewrapData(body, maskedData);
    }

    private Object maskPayload(Object data) {
        // Page.content[] 재귀: Spring Page는 record 아니고 일반 객체라 별도 처리
        if (data instanceof Page<?> page) {
            // Page는 불변. MaskingRule이 Map/List/String만 다루므로 page.getContent() 개별 마스킹
            // 단, Page 자체는 Jackson 직렬화 시 content 필드로 펼쳐지므로 원본 유지 + 내부 마스킹은 불가능.
            // → Service가 Page<MaskedDto>를 반환하도록 규약. 여기서는 content 내부만 정규식 스캔.
            return page.map(item -> maskingRule.mask(item));
        }
        if (data instanceof Map<?, ?> map) {
            return maskingRule.mask(map);
        }
        if (data instanceof java.util.List<?> list) {
            return maskingRule.mask(list);
        }
        // DTO 객체: 내부 Map 필드(configJson 등)를 리플렉션으로 찾아 마스킹
        return maskDtoFields(data);
    }

    /**
     * DTO의 {@code Map<String,Object>} / {@code String} / {@code List} 필드에 MaskingRule 적용.
     * 간단한 리플렉션 기반 — 정상 경로에서는 Service가 이미 마스킹했으므로 no-op.
     */
    private Object maskDtoFields(Object dto) {
        Class<?> clazz = dto.getClass();
        // java.* / jakarta.* 기본 타입은 건너뜀
        if (clazz.getName().startsWith("java.") || clazz.getName().startsWith("jakarta.")) {
            return dto;
        }
        try {
            for (Field field : clazz.getDeclaredFields()) {
                int mods = field.getModifiers();
                // static / transient / final / @JsonIgnore는 건드리지 않음 (DevilsAdvocate 가드 추가).
                // final은 record·불변 DTO에서 JDK 17+ InaccessibleObjectException 유발.
                if (Modifier.isStatic(mods) || Modifier.isTransient(mods) || Modifier.isFinal(mods)) continue;
                if (field.isAnnotationPresent(JsonIgnore.class)) continue;

                field.setAccessible(true);
                Object value = field.get(dto);
                if (value == null) continue;
                Object masked = null;
                if (value instanceof Map<?, ?> || value instanceof java.util.List<?>) {
                    masked = maskingRule.mask(value);
                } else if (value instanceof String s) {
                    masked = maskingRule.maskString(s);
                }
                if (masked != null && masked != value) {
                    field.set(dto, masked);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // RuntimeException에 SecurityException·InaccessibleObjectException(JDK 17+) 포함.
            log.debug("DefensiveMaskingFilter reflection skip: {}", e.getMessage());
        }
        return dto;
    }

    /**
     * {@link ApiResponse}는 불변이므로 data만 교체 불가. 원본 반환 (data는 이미 같은 참조에서 마스킹됨).
     * DTO 필드 변경 또는 Map/List/Page 교체본을 그대로 반환하면 Jackson이 새 data로 직렬화한다.
     */
    private ApiResponse<?> rewrapData(ApiResponse<?> body, Object maskedData) {
        // 참조 동일이면 원본 유지 (DTO 필드 수정 케이스)
        if (maskedData == body.getData()) return body;
        // Map/List/Page 교체본은 success()로 재래핑
        return ApiResponse.success(maskedData);
    }

    /**
     * 원본 request의 query string에서 {@code size}를 파싱해 `MAX_PAGE_SIZE` 초과 시 헤더 주입.
     * Spring은 이미 클램프된 `Pageable`을 컨트롤러에 주입하므로, 원본 의도를 알려면 raw 값 확인 필요.
     */
    private void injectSizeClampedHeader(ServerHttpRequest request, ServerHttpResponse response) {
        URI uri = request.getURI();
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) return;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq).toLowerCase(Locale.ROOT);
            if (!"size".equals(key)) continue;
            String rawValue = pair.substring(eq + 1);
            try {
                int requested = Integer.parseInt(rawValue);
                if (requested > PaginationConstants.MAX_PAGE_SIZE) {
                    response.getHeaders().add(PaginationConstants.X_SIZE_CLAMPED_HEADER, "true");
                }
            } catch (NumberFormatException ignore) {
                // 숫자 아닌 size는 Pageable resolver가 400으로 처리 — 여기서는 무시
            }
            return;
        }
    }

}
