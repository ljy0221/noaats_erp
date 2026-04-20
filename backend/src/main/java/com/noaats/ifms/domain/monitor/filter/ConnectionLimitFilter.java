package com.noaats.ifms.domain.monitor.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noaats.ifms.domain.monitor.sse.SseEmitterRegistry;
import com.noaats.ifms.domain.monitor.sse.SseProperties;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.global.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SSE 엔드포인트 연결 상한 필터 (api-spec.md §6.1).
 *
 * <p>세션당 {@code ifms.sse.session-limit}개, 계정당 {@code ifms.sse.account-limit}개를
 * 초과하면 429 TOO_MANY_CONNECTIONS 응답을 반환한다.</p>
 *
 * <p>{@code /api/monitor/stream} 경로에만 적용.</p>
 */
@Component
@Order(20)
public class ConnectionLimitFilter extends OncePerRequestFilter {

    private static final String STREAM_PATH = "/api/monitor/stream";

    private final SseEmitterRegistry registry;
    private final SseProperties props;
    private final ObjectMapper objectMapper;

    public ConnectionLimitFilter(SseEmitterRegistry registry,
                                 SseProperties props,
                                 ObjectMapper objectMapper) {
        this.registry = registry;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !STREAM_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String sessionId = request.getSession(true).getId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actorId = (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal()))
                ? auth.getName() : null;

        if (registry.sessionConnectionCount(sessionId) >= props.sessionLimit()) {
            writeError(response,
                    "세션당 SSE 연결 상한(" + props.sessionLimit() + ")을 초과했습니다");
            return;
        }
        if (actorId != null
                && registry.accountConnectionCount(actorId) >= props.accountLimit()) {
            writeError(response,
                    "계정당 SSE 연결 상한(" + props.accountLimit() + ")을 초과했습니다");
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<?> body = ApiResponse.error(
                ErrorCode.TOO_MANY_CONNECTIONS, msg, Map.of());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
