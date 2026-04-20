package com.noaats.ifms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IFMS Spring Boot 엔트리포인트.
 *
 * - @EnableAsync           : ADR-001 §6 비동기 실행 풀 (AsyncConfig)
 * - @EnableScheduling      : OrphanRunningWatchdog 5분 주기 (erd §8.3, Day 3)
 * - @ConfigurationPropertiesScan : AdvisoryLockProperties 등 record 기반 설정 자동 등록
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.noaats.ifms")
public class IfmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(IfmsApplication.class, args);
    }
}
