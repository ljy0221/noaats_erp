package com.noaats.ifms.domain.interface_.domain;

/**
 * 인터페이스 스케줄 유형.
 * CRON이면 cron_expression 필수 (erd.md §3.1 ck_ifc_cron_required)
 */
public enum ScheduleType {
    MANUAL,
    CRON
}
