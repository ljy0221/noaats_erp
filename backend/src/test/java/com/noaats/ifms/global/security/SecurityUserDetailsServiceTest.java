package com.noaats.ifms.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Day 7 §4.2 회귀 방지 — {@link SecurityUserDetailsService#loadUserByUsername}은
 * 매 호출마다 독립된 {@link UserDetails} 인스턴스를 반환해야 한다.
 *
 * <p>이전 구현은 {@code User.withUsername(...)...build()}로 생성한 인스턴스를
 * 서비스 필드에 싱글턴으로 보관했다. Spring Security는 첫 인증 성공 후
 * {@code AbstractAuthenticationToken.eraseCredentials()}를 호출해 principal의
 * password 필드를 null로 지우는데, 싱글턴 인스턴스는 이 변경이 누적되어
 * 두 번째 요청부터 {@code BCryptPasswordEncoder}가 "Empty encoded password" WARN과
 * 함께 401로 실패했다(T21-E2E-HANDOFF §8.5).
 */
class SecurityUserDetailsServiceTest {

    private SecurityUserDetailsService service;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder();
        service = new SecurityUserDetailsService(encoder);
    }

    @Test
    void loadReturnsFreshInstanceEachCall() {
        UserDetails first = service.loadUserByUsername("operator@ifms.local");
        UserDetails second = service.loadUserByUsername("operator@ifms.local");
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void eraseCredentialsOnOneCopyDoesNotAffectNext() {
        UserDetails first = service.loadUserByUsername("operator@ifms.local");
        ((org.springframework.security.core.CredentialsContainer) first).eraseCredentials();
        assertThat(first.getPassword()).isNull();

        UserDetails second = service.loadUserByUsername("operator@ifms.local");
        assertThat(second.getPassword()).isNotNull().isNotEmpty();
        assertThat(encoder.matches("operator1234", second.getPassword())).isTrue();
    }

    @Test
    void operatorRolesAreSet() {
        UserDetails u = service.loadUserByUsername("operator@ifms.local");
        assertThat(u.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OPERATOR");
    }

    @Test
    void adminRolesAreSet() {
        UserDetails u = service.loadUserByUsername("admin@ifms.local");
        assertThat(u.getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_OPERATOR");
    }

    @Test
    void usernameLookupIsCaseInsensitive() {
        UserDetails u = service.loadUserByUsername("OPERATOR@IFMS.LOCAL");
        assertThat(u.getUsername()).isEqualTo("operator@ifms.local");
    }

    @Test
    void unknownUserThrows() {
        assertThatThrownBy(() -> service.loadUserByUsername("ghost@ifms.local"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
