package com.noaats.ifms.domain.execution.domain;

/**
 * 실행 로그 상태 (erd.md §3.2 ck_log_status).
 *
 * 상태 전이는 단방향만 허용:
 *   RUNNING → SUCCESS  (성공 종료)
 *   RUNNING → FAILED   (실패 종료)
 *   SUCCESS/FAILED → 다른 상태 전이 금지 (Entity 비즈니스 메서드에서 강제)
 */
public enum ExecutionStatus {
    RUNNING,
    SUCCESS,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
