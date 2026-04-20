package com.noaats.ifms.global.config;

import com.noaats.ifms.domain.monitor.filter.ConnectionLimitFilter;
import com.noaats.ifms.global.web.TraceIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Day 4 Security 완전판 (api-spec.md §2).
 *
 * <h3>필터 순서</h3>
 * <ol>
 *   <li>TraceIdFilter (@Order 10) — MDC·헤더</li>
 *   <li>ConnectionLimitFilter (@Order 20) — /api/monitor/stream 전용</li>
 *   <li>Spring Security (UsernamePasswordAuthenticationFilter 등)</li>
 * </ol>
 *
 * <h3>CSRF</h3>
 * {@link CookieCsrfTokenRepository#withHttpOnlyFalse()} — SPA가 XSRF-TOKEN 쿠키를 읽어
 * X-XSRF-TOKEN 헤더로 동봉한다. SPA CSRF 규약. /login·/logout은 세션 확립 전 호출이므로 제외.
 *
 * <h3>permitAll 범위</h3>
 * <ul>
 *   <li>POST /login, POST /logout — 폼 인증</li>
 *   <li>/swagger-ui/**, /v3/api-docs/** — 개발 편의 (운영 전환 시 제거)</li>
 *   <li>/actuator/health — 기동 확인 (/actuator/** 전체는 차단)</li>
 * </ul>
 *
 * <h3>인증 실패 응답</h3>
 * {@link org.springframework.security.web.AuthenticationEntryPoint} 커스텀 — 401만 반환하고
 * HTML 리다이렉트 금지 (SPA 전용).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           TraceIdFilter traceIdFilter,
                                           ConnectionLimitFilter connectionLimitFilter) throws Exception {

        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers("/login", "/logout"))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(form -> form
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler((req, res, auth) -> res.setStatus(200))
                        .failureHandler((req, res, ex) -> res.setStatus(401)))
                .logout(lo -> lo
                        .logoutUrl("/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(200))
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) -> res.setStatus(401))
                        .accessDeniedHandler((req, res, ex) -> res.setStatus(403)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/logout",
                                "/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().authenticated())
                .addFilterBefore(traceIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(connectionLimitFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
