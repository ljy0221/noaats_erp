package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.PayloadFormat;
import com.noaats.ifms.domain.interface_.domain.ProtocolType;

/**
 * 프로토콜별 기본 payload 포맷 매핑.
 * SOAP만 XML, 나머지는 JSON. 본 매핑은 ck_log_payload_xor와 정합.
 */
final class PayloadFormatResolver {

    private PayloadFormatResolver() {}

    static PayloadFormat defaultFor(ProtocolType protocol) {
        return protocol == ProtocolType.SOAP ? PayloadFormat.XML : PayloadFormat.JSON;
    }
}
