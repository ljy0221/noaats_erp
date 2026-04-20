package com.noaats.ifms.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/**
 * Spring MVC 공통 설정.
 *
 * - Pageable 기본값·최댓값 강제 (api-spec.md §3.1).
 *   `size > MAX_PAGE_SIZE`면 `PageableHandlerMethodArgumentResolver`가 자동으로 클램프.
 *   `X-Size-Clamped` 헤더 주입은 `DefensiveMaskingFilter`에서 raw 요청 파라미터를 보고 처리한다.
 */
@Configuration
public class WebMvcConfig {

    @Bean
    public PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer() {
        return resolver -> {
            resolver.setFallbackPageable(PageRequest.of(0, PaginationConstants.DEFAULT_PAGE_SIZE));
            resolver.setMaxPageSize(PaginationConstants.MAX_PAGE_SIZE);
        };
    }
}
