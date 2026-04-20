package com.noaats.ifms.domain.interface_.domain;

/**
 * 인터페이스 프로토콜 유형.
 * DB 컬럼 제약: ck_ifc_protocol (erd.md §3.1)
 */
public enum ProtocolType {
    REST,
    SOAP,
    MQ,
    BATCH,
    SFTP
}
