package com.noaats.ifms.global.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.noaats.ifms.domain.interface_.dto.InterfaceConfigSnapshot;
import com.noaats.ifms.domain.interface_.service.InterfaceConfigService;
import com.noaats.ifms.global.masking.MaskingRule;
import com.noaats.ifms.global.response.ApiResponse;
import com.noaats.ifms.global.response.ErrorDetail;
import com.noaats.ifms.global.validation.SensitiveKeyRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 전역 예외 → ErrorCode 매핑 핸들러 (api-spec.md §7.1 정본).
 *
 * <h2>예외 ↔ ErrorCode 매트릭스</h2>
 * <pre>
 * ┌─────────────────────────────────────────────┬────────────────────────────────────────┐
 * │ Exception                                    │ ErrorCode                              │
 * ├─────────────────────────────────────────────┼────────────────────────────────────────┤
 * │ ApiException (sealed)                        │ e.getErrorCode() — 17종 전수 커버      │
 * │ MethodArgumentNotValidException              │ VALIDATION_FAILED + fieldErrors REDACTED│
 * │ ConstraintViolationException                 │ VALIDATION_FAILED + fieldErrors REDACTED│
 * │ HttpMessageNotReadableException              │ VALIDATION_FAILED + enum sanitize      │
 * │ MissingServletRequestParameterException      │ VALIDATION_FAILED                      │
 * │ MethodArgumentTypeMismatchException          │ VALIDATION_FAILED                      │
 * │ DataIntegrityViolationException (23505 +    │ DUPLICATE_NAME (uk_ifc_name) 또는      │
 * │   constraint name)                          │ INTERNAL_ERROR (그 외)                 │
 * │ OptimisticLockingFailureException            │ OPTIMISTIC_LOCK_CONFLICT +             │
 * │                                              │   serverSnapshot(ObjectProvider 재조회)│
 * │ MaxUploadSizeExceededException               │ PAYLOAD_TOO_LARGE                      │
 * │ AccessDeniedException                        │ FORBIDDEN                              │
 * │ AuthenticationException                      │ UNAUTHENTICATED                        │
 * │ Exception (fallback)                         │ INTERNAL_ERROR + traceId=UUID + MDC    │
 * └─────────────────────────────────────────────┴────────────────────────────────────────┘
 * </pre>
 *
 * Javadoc 매트릭스와 @ExceptionHandler 메서드 수가 일치해야 함(12개). drift 방지는 코드 리뷰.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String REDACTED = "***REDACTED***";
    private static final String POSTGRES_UNIQUE_VIOLATION = "23505";
    private static final String UK_IFC_NAME = "uk_ifc_name";

    /**
     * ObjectProvider로 lazy 주입해 순환 참조·초기화 순서 이슈 방지 (DBA 요구).
     * Service 경유로 레이어 경계 정합 (ADR-006: Handler → Service → Repository 단방향).
     */
    private final ObjectProvider<InterfaceConfigService> interfaceServiceProvider;

    /** serverSnapshot 1차 마스킹 + rejectedValue 값 마스킹에 재사용. */
    private final MaskingRule maskingRule;

    /* ─────────────────────────────────────── ApiException 계열 (17종 전수) ─────────────────────── */

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> handleApiException(ApiException e) {
        log.warn("ApiException: code={} message={} extra={}",
                e.getErrorCode(), e.getMessage(), e.getExtra());
        return build(e.getErrorCode(), e.getMessage(), e.getExtra());
    }

    /* ─────────────────────────────────────── 검증 계열 (400) ────────────────────────────────────── */

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e) {
        List<Map<String, Object>> fieldErrors = new ArrayList<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fieldErrors.add(toFieldErrorMap(fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()));
        }
        return build(ErrorCode.VALIDATION_FAILED,
                composeMessage(fieldErrors),
                Map.of("fieldErrors", fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> handleConstraintViolation(
            ConstraintViolationException e) {
        List<Map<String, Object>> fieldErrors = new ArrayList<>();
        for (ConstraintViolation<?> v : e.getConstraintViolations()) {
            String path = v.getPropertyPath().toString();
            fieldErrors.add(toFieldErrorMap(path, v.getInvalidValue(), v.getMessage()));
        }
        return build(ErrorCode.VALIDATION_FAILED,
                composeMessage(fieldErrors),
                Map.of("fieldErrors", fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e) {
        String message = "요청 본문을 읽을 수 없습니다";
        Throwable cause = e.getCause();
        if (cause instanceof InvalidFormatException ife && ife.getTargetType() != null
                && ife.getTargetType().isEnum()) {
            // enum 값 목록 노출 차단
            message = "유효하지 않은 값입니다";
        }
        return build(ErrorCode.VALIDATION_FAILED, message, Map.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> handleMissingParam(
            MissingServletRequestParameterException e) {
        return build(ErrorCode.VALIDATION_FAILED,
                "필수 파라미터 누락: " + e.getParameterName(),
                Map.of("parameter", e.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> handleTypeMismatch(
            MethodArgumentTypeMismatchException e) {
        return build(ErrorCode.VALIDATION_FAILED,
                "파라미터 타입 불일치: " + e.getName(),
                Map.of("parameter", e.getName()));
    }

    /* ─────────────────────────────────────── DB 제약 (409) ──────────────────────────────────────── */

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> handleDataIntegrityViolation(
            DataIntegrityViolationException e) {
        SqlDiagnostic diag = extractSqlDiagnostic(e);
        log.warn("DataIntegrityViolation: sqlState={} constraint={}", diag.sqlState, diag.constraint);

        // H2 호환: sqlState가 null이거나 23505여도 constraint명이 uk_ifc_name과 일치하면 DUPLICATE_NAME.
        // PostgreSQL은 sqlState=23505 + constraint="uk_ifc_name", H2는 sqlState=null + 메시지에서 추출된 constraint.
        boolean isPgUnique = POSTGRES_UNIQUE_VIOLATION.equals(diag.sqlState);
        boolean constraintMatches = UK_IFC_NAME.equalsIgnoreCase(diag.constraint);
        if ((isPgUnique && constraintMatches) || (diag.sqlState == null && constraintMatches)) {
            return build(ErrorCode.DUPLICATE_NAME,
                    ErrorCode.DUPLICATE_NAME.getDefaultMessage(),
                    Map.of("field", "name"));   // 제약명(uk_ifc_name) 대신 논리 필드명
        }
        return fallbackInternalError(e);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> handleOptimisticLock(
            OptimisticLockingFailureException e, HttpServletRequest req) {
        log.warn("OptimisticLockingFailure: {}", e.getMessage());
        Long id = extractInterfaceIdFromPath(req);
        Map<String, Object> extra = new LinkedHashMap<>();
        Object snapshot = lookupSnapshot(id);
        extra.put("serverSnapshot", snapshot);
        // snapshot 조회 실패 시 프론트가 null vs 미존재를 구분할 수 있도록 명시 플래그 (DevilsAdvocate 지적).
        // 기동 직후 Service 빈 초기화 전 첫 409, 또는 2차 예외로 snapshot=null 시 구분.
        if (snapshot == null) {
            extra.put("snapshotUnavailable", true);
            log.warn("OptimisticLock snapshot unavailable: id={}", id);
        }
        return build(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                ErrorCode.OPTIMISTIC_LOCK_CONFLICT.getDefaultMessage(), extra);
    }

    /* ─────────────────────────────────────── 기타 (413 / 401 / 403 / 500) ──────────────────────── */

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> handleMaxUpload(MaxUploadSizeExceededException e) {
        return build(ErrorCode.PAYLOAD_TOO_LARGE,
                ErrorCode.PAYLOAD_TOO_LARGE.getDefaultMessage(), Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> handleAccessDenied(AccessDeniedException e) {
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getDefaultMessage(), Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> handleAuthentication(AuthenticationException e) {
        return build(ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.getDefaultMessage(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ErrorDetail>> handleFallback(Exception e) {
        return fallbackInternalError(e);
    }

    /* ─────────────────────────────────────── 내부 헬퍼 ───────────────────────────────────────── */

    private ResponseEntity<ApiResponse<ErrorDetail>> fallbackInternalError(Exception e) {
        String traceId = UUID.randomUUID().toString();
        MDC.put(TRACE_ID_KEY, traceId);
        try {
            log.error("INTERNAL_ERROR traceId={}", traceId, e);
            return build(ErrorCode.INTERNAL_ERROR,
                    ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                    Map.of("traceId", traceId));
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }

    private ResponseEntity<ApiResponse<ErrorDetail>> build(
            ErrorCode code, String message, Map<String, Object> extra) {
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.error(code, message, extra));
    }

    /** rejectedValue가 민감 키/값이면 REDACTED 고정 (api-spec.md §7.1). */
    private Map<String, Object> toFieldErrorMap(String field, Object rejectedValue, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("rejectedValue", sanitizeRejectedValue(field, rejectedValue));
        m.put("message", message);
        return m;
    }

    /**
     * 키가 민감 키 블랙리스트에 매치되면 REDACTED.
     * 값이 문자열이면 {@link MaskingRule} 정규식(RRN/카드/계좌/휴대폰/이메일/JWT)으로 부분 마스킹 —
     * JWT 선두만 검사하던 이전 로직을 확장 (Security MUST).
     */
    private Object sanitizeRejectedValue(String field, Object value) {
        if (field != null && SensitiveKeyRegistry.matches(extractLeafKey(field))) {
            return REDACTED;
        }
        if (value instanceof String s) {
            return maskingRule.maskString(s);
        }
        return value;
    }

    private String extractLeafKey(String field) {
        int dot = field.lastIndexOf('.');
        int br = field.lastIndexOf('[');
        int cut = Math.max(dot, br);
        return cut >= 0 && cut + 1 < field.length() ? field.substring(cut + 1).replace("]", "") : field;
    }

    private String composeMessage(List<Map<String, Object>> fieldErrors) {
        if (fieldErrors.isEmpty()) return "요청 검증에 실패했습니다";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fieldErrors.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(fieldErrors.get(i).get("field"))
              .append(" ")
              .append(fieldErrors.get(i).get("message"));
        }
        return sb.toString();
    }

    /**
     * URI path에서 InterfaceConfig 리소스 id를 추출.
     * `/api/interfaces/{id}` 프리픽스를 강제해 다른 엔티티({@code /api/executions/{id}} 등)의 낙관적 락 충돌 시
     * InterfaceConfig가 아닌 id를 조회하는 교차 오염을 차단(DevilsAdvocate 지적).
     * 향후 ExecutionLog에 @Version 도입 시 prefix 분기를 확장한다.
     */
    private Long extractInterfaceIdFromPath(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (path == null) return null;
        String prefix = "/api/interfaces/";
        int start = path.indexOf(prefix);
        if (start < 0) return null;
        String rest = path.substring(start + prefix.length());
        int slash = rest.indexOf('/');
        String idSeg = slash < 0 ? rest : rest.substring(0, slash);
        try {
            return Long.parseLong(idSeg);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    /**
     * 낙관적 락 충돌 시 최신 엔티티 조회 — 2차 예외는 null로 승격 거부.
     * Service 경유로 레이어 경계 정합 + configJson 제외 경량 Snapshot 사용 (PII 노출 최소화).
     * DefensiveMaskingFilter는 `ErrorDetail` 응답에 skip하므로 여기서 {@link MaskingRule} 1차 마스킹이
     * 최종 방어선. Snapshot 자체에 민감값은 없으나 회귀 방어 목적으로 재적용.
     */
    private Object lookupSnapshot(Long id) {
        if (id == null) return null;
        try {
            InterfaceConfigService service = interfaceServiceProvider.getIfAvailable();
            if (service == null) return null;
            InterfaceConfigSnapshot snapshot = service.findSnapshot(id);
            return snapshot == null ? null : maskingRule.mask(snapshot);
        } catch (Exception ignore) {
            return null;
        }
    }

    /** DataIntegrityViolationException → PSQLException 추출. H2는 PSQLException 아님 → null 필드. */
    private SqlDiagnostic extractSqlDiagnostic(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        String sqlState = null;
        String constraint = null;
        if (cause instanceof SQLException sql) {
            sqlState = sql.getSQLState();
            // PSQLException 전용 API. instanceof 가드로 H2 호환.
            try {
                Class<?> psqlClass = Class.forName("org.postgresql.util.PSQLException");
                if (psqlClass.isInstance(cause)) {
                    Object serverErr = psqlClass.getMethod("getServerErrorMessage").invoke(cause);
                    if (serverErr != null) {
                        Object c = serverErr.getClass().getMethod("getConstraint").invoke(serverErr);
                        if (c != null) constraint = c.toString();
                    }
                }
            } catch (ReflectiveOperationException ignore) {
                // PSQLException 미존재(클래스패스) 또는 리플렉션 실패 → constraint=null 유지
            }
        }
        // 메시지에서 제약명 추출 폴백 (H2 · 리플렉션 실패 시)
        if (constraint == null) {
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains(UK_IFC_NAME)) {
                constraint = UK_IFC_NAME;
            }
        }
        return new SqlDiagnostic(sqlState, constraint);
    }

    private record SqlDiagnostic(String sqlState, String constraint) {}
}
