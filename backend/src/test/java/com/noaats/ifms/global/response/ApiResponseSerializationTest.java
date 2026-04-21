package com.noaats.ifms.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Day 7: spring.jackson.default-property-inclusion=ALWAYS 정책 회귀 테스트.
 *
 * <p>이 정책이 빠지면 프런트의 ApiResponse&lt;T&gt;가 {@code data === undefined} vs
 * {@code data === null} 분기 부담을 짊어지게 된다.
 */
@SpringBootTest
@TestPropertySource(properties = {"ifms.actor.anon-salt=test-salt-jackson"})
class ApiResponseSerializationTest {

    @Autowired ObjectMapper mapper;

    @Test
    void apiResponseSerializesNullDataField() throws Exception {
        ApiResponse<String> r = ApiResponse.success(null);
        String json = mapper.writeValueAsString(r);
        assertThat(json).contains("\"data\":null");
    }

    @Test
    void apiResponseSerializesNullMessageField() throws Exception {
        ApiResponse<String> r = ApiResponse.success("ok");
        String json = mapper.writeValueAsString(r);
        assertThat(json).contains("\"message\":null");
    }
}
