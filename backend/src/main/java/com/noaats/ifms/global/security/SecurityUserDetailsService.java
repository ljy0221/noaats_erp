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
 */
@Service
public class SecurityUserDetailsService implements UserDetailsService {

    private final UserDetails operator;
    private final UserDetails admin;

    public SecurityUserDetailsService(PasswordEncoder encoder) {
        this.operator = User.withUsername("operator@ifms.local")
                .password(encoder.encode("operator1234"))
                .roles("OPERATOR")
                .build();
        this.admin = User.withUsername("admin@ifms.local")
                .password(encoder.encode("admin1234"))
                .roles("ADMIN", "OPERATOR")
                .build();
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (operator.getUsername().equalsIgnoreCase(username)) return operator;
        if (admin.getUsername().equalsIgnoreCase(username)) return admin;
        throw new UsernameNotFoundException("no such user: " + username);
    }
}
