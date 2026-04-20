package com.noaats.ifms.domain.monitor.dto;

/**
 * 프로토콜별 상태 집계 (api-spec.md §6.2).
 */
public record ProtocolStats(String protocol, long success, long failed, long running) {
}
