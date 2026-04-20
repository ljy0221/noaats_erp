package com.noaats.ifms.domain.execution.domain;

/**
 * Payload 직렬화 포맷 (erd.md §3.2 ck_log_payload_format / ck_log_payload_xor).
 *
 * - JSON: request_payload / response_payload (JSONB)
 * - XML : request_payload_xml / response_payload_xml (TEXT) — SOAP 등 원문 보존용
 *
 * 두 컬럼군은 ck_log_payload_xor로 배타 사용이 강제된다.
 */
public enum PayloadFormat {
    JSON,
    XML
}
