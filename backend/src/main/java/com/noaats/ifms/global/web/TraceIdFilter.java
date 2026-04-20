package com.noaats.ifms.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 요청에 traceId를 부여하고 MDC·응답 헤더로 전파한다.
 *
 * <p>게이트웨이가 {@code X-Trace-Id} 헤더로 주입하면 그대로 재사용하고, 없으면 UUID 16-hex를
 * 생성한다. logback 패턴({@code %X{traceId}})이 자동 인식한다.</p>
 *
 * <p>Security 필터 체인보다 먼저 실행되어야 인증 실패 로그에도 traceId가 기록된다.</p>
 */
@Component
@Order(10)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        try {
            MDC.put(MDC_KEY, traceId);
            response.setHeader(HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
