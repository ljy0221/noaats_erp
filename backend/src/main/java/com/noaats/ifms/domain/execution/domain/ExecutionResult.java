package com.noaats.ifms.domain.execution.domain;

import java.util.Map;

/**
 * Mock/실 실행기 결과 value object (planning.md §5.5).
 *
 * MockExecutor → AsyncExecutionRunner 경계에서 사용된다.
 * ADR-001 §3 마스킹 호출 지점: Mock 응답 직후 MaskingRule 적용 후 본 객체 생성.
 * 따라서 본 record의 payload는 이미 마스킹 적용본이며 그대로 ExecutionLog에 영속화된다.
 *
 * payloadFormat에 따라 jsonPayload(Map) 또는 xmlPayload(String) 한쪽만 채워진다.
 *
 * @param success         성공 여부
 * @param durationMs      실행 소요 시간 (ms, > 0)
 * @param payloadFormat   응답 직렬화 포맷
 * @param requestPayload  요청 본문 (JSON Map, payloadFormat=JSON일 때만)
 * @param responsePayload 응답 본문 (JSON Map, payloadFormat=JSON일 때만)
 * @param requestPayloadXml  요청 원문 (XML, payloadFormat=XML일 때만)
 * @param responsePayloadXml 응답 원문 (XML, payloadFormat=XML일 때만)
 * @param payloadTruncated 64KB 초과로 잘렸는지
 * @param errorCode       실패 사유 코드 (success=false일 때만)
 * @param errorMessage    실패 사유 메시지 (success=false일 때만, 1000자 제한)
 */
public record ExecutionResult(
        boolean success,
        long durationMs,
        PayloadFormat payloadFormat,
        Map<String, Object> requestPayload,
        Map<String, Object> responsePayload,
        String requestPayloadXml,
        String responsePayloadXml,
        boolean payloadTruncated,
        ExecutionErrorCode errorCode,
        String errorMessage
) {

    public static ExecutionResult successJson(long durationMs,
                                              Map<String, Object> request,
                                              Map<String, Object> response,
                                              boolean truncated) {
        return new ExecutionResult(true, durationMs, PayloadFormat.JSON,
                request, response, null, null, truncated, null, null);
    }

    public static ExecutionResult successXml(long durationMs,
                                             String requestXml,
                                             String responseXml,
                                             boolean truncated) {
        return new ExecutionResult(true, durationMs, PayloadFormat.XML,
                null, null, requestXml, responseXml, truncated, null, null);
    }

    public static ExecutionResult failure(long durationMs,
                                          PayloadFormat format,
                                          ExecutionErrorCode code,
                                          String message) {
        return new ExecutionResult(false, durationMs, format,
                null, null, null, null, false, code, message);
    }
}
