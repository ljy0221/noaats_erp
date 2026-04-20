package com.noaats.ifms.global.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 세션 기반 actor_id 추출 (api-spec.md §2.3).
 *
 * <h3>분기 규칙</h3>
 * <ul>
 *   <li>인증된 세션 + 이메일 username → {@code EMAIL:} + SHA-256(lower(email))</li>
 *   <li>인증된 세션 + 비-이메일 username → {@code EMAIL:} + SHA-256(lower(username))
 *       (프로토타입 단순화. 운영 전환 시 OIDC sub 대응 {@link #SSO_PREFIX} 분기)</li>
 *   <li>미인증 → {@code ANONYMOUS_} + SHA-256(anon-salt + client-ip)[:16]</li>
 *   <li>스케줄러 진입점 → {@link #systemActor()}</li>
 * </ul>
 *
 * <p>평문 이메일은 절대 저장하지 않는다 (개인정보보호법 최소수집).</p>
 */
@Component
public class ActorContext {

    public static final String SYSTEM = "SYSTEM";
    public static final String ANON_PREFIX = "ANONYMOUS_";
    public static final String EMAIL_PREFIX = "EMAIL:";
    public static final String SSO_PREFIX = "SSO:";

    private final String anonSalt;

    public ActorContext(@Value("${ifms.actor.anon-salt:LOCAL_DEV_SALT}") String anonSalt) {
        this.anonSalt = anonSalt;
    }

    public String resolveActor(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            String name = auth.getName();
            if (name == null || name.isBlank()) {
                return EMAIL_PREFIX + sha256Hex("unknown");
            }
            return EMAIL_PREFIX + sha256Hex(name.toLowerCase());
        }
        String ip = resolveClientIp(request);
        String basis = anonSalt + (ip != null ? ip : "unknown");
        return ANON_PREFIX + sha256Hex(basis).substring(0, 16);
    }

    public String systemActor() {
        return SYSTEM;
    }

    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public String resolveUserAgent(HttpServletRequest request) {
        return request != null ? request.getHeader("User-Agent") : null;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
