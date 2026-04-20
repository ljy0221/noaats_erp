package com.noaats.ifms.domain.execution.service;

import com.noaats.ifms.domain.execution.domain.ExecutionLog;
import com.noaats.ifms.domain.execution.dto.ExecutionListParams;
import com.noaats.ifms.domain.execution.dto.ExecutionLogResponse;
import com.noaats.ifms.domain.execution.repository.ExecutionLogRepository;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.repository.InterfaceConfigRepository;
import com.noaats.ifms.global.exception.ErrorCode;
import com.noaats.ifms.global.exception.NotFoundException;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ExecutionHistory·상세 조회 전용 서비스 (읽기 전용). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExecutionQueryService {

    private final ExecutionLogRepository logRepo;
    private final InterfaceConfigRepository configRepo;

    public Page<ExecutionLogResponse> list(ExecutionListParams params) {
        Page<ExecutionLog> page = logRepo.findList(params.status(), params.interfaceConfigId(), params.pageable());
        Map<Long, String> names = configRepo.findAllById(
                page.getContent().stream()
                        .map(ExecutionLog::getInterfaceConfig)
                        .filter(Objects::nonNull)
                        .map(InterfaceConfig::getId)
                        .distinct()
                        .toList()
        ).stream().collect(Collectors.toMap(InterfaceConfig::getId, InterfaceConfig::getName));
        return page.map(e -> {
            Long cid = e.getInterfaceConfig() != null ? e.getInterfaceConfig().getId() : null;
            return ExecutionLogResponse.of(e, cid == null ? "(삭제됨)" : names.getOrDefault(cid, "(삭제됨)"));
        });
    }

    public ExecutionLogResponse detail(Long id) {
        ExecutionLog e = logRepo.findById(id)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EXECUTION_NOT_FOUND));
        String name = e.getInterfaceConfig() == null ? "(삭제됨)"
                : configRepo.findById(e.getInterfaceConfig().getId())
                    .map(InterfaceConfig::getName).orElse("(삭제됨)");
        return ExecutionLogResponse.of(e, name);
    }
}
