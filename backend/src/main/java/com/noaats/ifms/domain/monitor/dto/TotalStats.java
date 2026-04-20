package com.noaats.ifms.domain.monitor.dto;

/**
 * 대시보드 총 집계 (api-spec.md §6.2).
 */
public record TotalStats(long success, long failed, long running, long total) {
}
