package com.noaats.ifms.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * advisory lock 네임스페이스 주입 (api-spec.md §9.1, ADR-005 §5.3).
 *
 * - namespace        : 일반 실행 lock (key2 = interface_config_id)
 * - retryNamespace   : 재처리 체인 lock (key2 = parent_log_id) — ADR-005 Q4 2-도메인 분리
 *
 * 기본값:
 * - 0x49464D53 = 'IFMS' ASCII (일반 실행)
 * - 0x49464D54 = 'IFMT' ASCII (재처리)
 *
 * 운영 DB 공유 환경에서는 두 값 모두 재할당 필수.
 */
@ConfigurationProperties(prefix = "ifms.advisory-lock")
public record AdvisoryLockProperties(
        int namespace,
        int retryNamespace
) {
    public AdvisoryLockProperties {
        if (namespace == retryNamespace) {
            throw new IllegalArgumentException(
                    "ifms.advisory-lock.namespace and retry-namespace must differ (ADR-005 Q4)");
        }
    }
}
