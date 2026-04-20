package com.noaats.ifms.domain.interface_.repository;

import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * InterfaceConfig Repository.
 *
 * - 동적 필터(status / protocol / name LIKE)는 Specification으로 구성 (다음 묶음 Service에서).
 * - findByName 같은 사전 중복 체크 메서드는 의도적으로 두지 않는다.
 *   UNIQUE 위반을 DataIntegrityViolationException → 409 DUPLICATE_NAME으로 변환
 *   (TOCTOU 레이스 제거, api-spec.md §4.3).
 */
public interface InterfaceConfigRepository
        extends JpaRepository<InterfaceConfig, Long>,
                JpaSpecificationExecutor<InterfaceConfig> {
}
