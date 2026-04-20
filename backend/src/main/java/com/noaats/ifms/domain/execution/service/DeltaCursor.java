package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.global.exception.BusinessException;
import com.noaats.ifms.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;

/** delta API 커서 인코딩 유틸. base64url(ISO-8601 OffsetDateTime) 단일 필드. ADR-007 R1. */
public final class DeltaCursor {
    private DeltaCursor() {}

    public static String encode(OffsetDateTime at) {
        String iso = at.toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iso.getBytes(StandardCharsets.UTF_8));
    }

    public static OffsetDateTime decode(String cursor) {
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "cursor base64 디코딩 실패",
                    Map.of("cursor", cursor));
        }
        String iso = new String(raw, StandardCharsets.UTF_8);
        try {
            return OffsetDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "cursor ISO-8601 파싱 실패",
                    Map.of("cursor", cursor));
        }
    }
}
