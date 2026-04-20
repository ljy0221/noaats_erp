package com.noaats.ifms.domain.interface_.controller;

import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ProtocolType;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * `GET /api/interfaces` 쿼리 파라미터 바인딩 전용 DTO (api-spec.md §4.1).
 *
 * Spring MVC가 `@ModelAttribute` 암묵 바인딩으로 쿼리스트링을 이 객체에 채운다.
 * Enum 역직렬화 실패 시 `HttpMessageNotReadableException` 또는
 * `MethodArgumentTypeMismatchException`이 발생하며, GlobalExceptionHandler가 sanitize 처리.
 */
@Getter
@Setter
@NoArgsConstructor
public class InterfaceFilterParams {

    private InterfaceStatus status;
    private ProtocolType protocol;

    @Size(max = 100)
    private String name;
}
