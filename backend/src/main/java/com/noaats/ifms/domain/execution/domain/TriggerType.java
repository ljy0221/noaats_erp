package com.noaats.ifms.domain.execution.domain;

/**
 * 실행 트리거 출처 (erd.md §3.2 ck_log_triggered_by).
 *
 * - MANUAL    : 운영자 화면에서 수동 실행
 * - SCHEDULER : CRON 스케줄러 자동 실행
 * - RETRY     : 재처리 (parent_log_id 필수, ADR-005)
 */
public enum TriggerType {
    MANUAL,
    SCHEDULER,
    RETRY
}
