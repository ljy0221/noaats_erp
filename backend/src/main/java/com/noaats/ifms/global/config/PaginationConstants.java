package com.noaats.ifms.global.config;

/**
 * 페이지네이션 공통 상수 (api-spec.md §3.1).
 *
 * 참조 지점:
 * - {@link WebMvcConfig}: `PageableHandlerMethodArgumentResolverCustomizer.setMaxPageSize`
 * - `DefensiveMaskingFilter`: raw query param `size` 비교 후 `X-Size-Clamped` 헤더 주입
 */
public final class PaginationConstants {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final String X_SIZE_CLAMPED_HEADER = "X-Size-Clamped";

    private PaginationConstants() {
    }
}
