package com.noaats.ifms.domain.execution.dto;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import org.springframework.data.domain.Pageable;

/** ExecutionHistory 리스트 쿼리 파라미터 묶음. */
public record ExecutionListParams(
        ExecutionStatus status,
        Long interfaceConfigId,
        Pageable pageable
) {}
