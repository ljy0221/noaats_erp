package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noaats.ifms.global.exception.BusinessException;
import com.noaats.ifms.global.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DeltaCursorTest {

    @Test
    void roundtrip_preserves_timestamp() {
        OffsetDateTime now = OffsetDateTime.of(2026, 4, 20, 10, 30, 45, 123_000_000, ZoneOffset.ofHours(9));
        String encoded = DeltaCursor.encode(now);
        OffsetDateTime decoded = DeltaCursor.decode(encoded);
        assertThat(decoded).isEqualTo(now);
    }

    @Test
    void encoded_is_base64_url_safe() {
        OffsetDateTime t = OffsetDateTime.of(2026, 4, 20, 10, 0, 0, 0, ZoneOffset.ofHours(9));
        String encoded = DeltaCursor.encode(t);
        assertThat(encoded).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    void decode_invalid_base64_throws_validation_failed() {
        assertThatThrownBy(() -> DeltaCursor.decode("!!!not-base64!!!"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void decode_invalid_iso_throws_validation_failed() {
        String bad = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-a-date".getBytes());
        assertThatThrownBy(() -> DeltaCursor.decode(bad))
                .isInstanceOf(BusinessException.class);
    }
}
