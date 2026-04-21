package com.noaats.ifms.global.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 프로토타입용 in-memory UserDetailsService (api-spec.md §2.1, §2.4).
 *
 * <p>운영 전환 시 DB 기반 {@code User} 테이블로 교체 (backlog).</p>
 *
 * <ul>
 *   <li>{@code operator@ifms.local / operator1234} — Role OPERATOR</li>
 *   <li>{@code admin@ifms.local / admin1234}       — Role ADMIN + OPERATOR</li>
 * </ul>
 *
 * <p><b>구현 주의</b>: {@link UserDetails}를 서비스 필드에 싱글턴으로 보관하면
 * 첫 인증 성공 후 {@code AbstractAuthenticationToken.eraseCredentials()}가 호출되어
 * principal의 password 필드가 null로 지워진다. 이후 요청은
 * {@link org.springframework.security.crypto.password.PasswordEncoder#matches}가
 * "Empty encoded password" 경고를 내고 401로 실패한다. 따라서 {@link #loadUserByUsername}은
 * 매 호출마다 불변 템플릿에서 복사본(새 {@code UserDetails} 인스턴스)을 만들어 반환해야 한다.</p>
 */
@Service
public class SecurityUserDetailsService implements UserDetailsService {

    private final String operatorEncodedPassword;
    private final String adminEncodedPassword;

    public SecurityUserDetailsService(PasswordEncoder encoder) {
        this.operatorEncodedPassword = encoder.encode("operator1234");
        this.adminEncodedPassword = encoder.encode("admin1234");
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if ("operator@ifms.local".equalsIgnoreCase(username)) {
            return User.withUsername("operator@ifms.local")
                    .password(operatorEncodedPassword)
                    .roles("OPERATOR")
                    .build();
        }
        if ("admin@ifms.local".equalsIgnoreCase(username)) {
            return User.withUsername("admin@ifms.local")
                    .password(adminEncodedPassword)
                    .roles("ADMIN", "OPERATOR")
                    .build();
        }
        throw new UsernameNotFoundException("no such user: " + username);
    }
}
