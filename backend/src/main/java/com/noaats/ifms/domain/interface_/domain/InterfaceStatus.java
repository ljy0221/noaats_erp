package com.noaats.ifms.domain.interface_.domain;

/**
 * 인터페이스 활성 상태.
 * 삭제 정책은 소프트 딜리트(INACTIVE). 하드 삭제 금지 — 이력 FK RESTRICT.
 */
public enum InterfaceStatus {
    ACTIVE,
    INACTIVE
}
