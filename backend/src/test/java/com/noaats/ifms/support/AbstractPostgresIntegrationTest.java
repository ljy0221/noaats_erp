package com.noaats.ifms.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Day 7 통합 테스트용 Testcontainers PostgreSQL 16 베이스.
 *
 * <p>JSONB 컨테인먼트(@>)·advisory lock 등 H2 미지원 기능을 검증하는 테스트만 본 클래스를 상속.
 * 일반 단위 테스트는 H2 기반 @SpringBootTest 유지.
 *
 * <p>컨테이너는 static 초기화로 JVM lifetime 동안 1회 부팅·재사용.
 * Spring Boot가 schema.sql을 자동 적용하도록 spring.sql.init.mode=always.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractPostgresIntegrationTest {

    @SuppressWarnings("resource") // JVM lifetime 동안 유지 (Testcontainers 권장 패턴)
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ifms")
                    .withUsername("ifms")
                    .withPassword("ifms1234")
                    .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.continue-on-error", () -> "true");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        // anon-salt 미주입 시 ApplicationContext 부팅 실패 (Day 4 Bug A)
        registry.add("ifms.actor.anon-salt", () -> "test-salt-day7-integration");
    }
}
