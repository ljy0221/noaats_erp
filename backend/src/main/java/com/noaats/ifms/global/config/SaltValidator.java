package com.noaats.ifms.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 운영 프로파일 기동 시 {@code ifms.actor.anon-salt} 누락·기본값 탐지 후 기동 거부 (api-spec.md §2.3).
 *
 * `prod` 프로파일 활성 시에만 빈 생성. `prod,dev` 혼합이면 `!dev`로 제외 — 로컬 개발 마찰 제거.
 * 값이 비거나 {@code REQUIRED_SET_ME_} 접두사이면 `IllegalStateException`으로 ApplicationContext
 * 초기화 실패 유발.
 */
@Component
@Profile("prod & !dev")
public class SaltValidator {

    private static final String REJECT_PREFIX = "REQUIRED_SET_ME_";

    @Value("${ifms.actor.anon-salt:}")
    private String salt;

    @PostConstruct
    void assertSalt() {
        if (salt == null || salt.isBlank() || salt.startsWith(REJECT_PREFIX)) {
            throw new IllegalStateException(
                    "ifms.actor.anon-salt must be set to a production value. "
                    + "Value starting with '" + REJECT_PREFIX + "' or blank is rejected in prod profile.");
        }
    }
}
