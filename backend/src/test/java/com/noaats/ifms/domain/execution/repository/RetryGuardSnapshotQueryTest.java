package com.noaats.ifms.domain.execution.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.execution.service.RetryGuardSnapshot;
import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.support.AbstractPostgresIntegrationTest;
import com.noaats.ifms.support.ExecutionLogTestSeeder;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link ExecutionLogRepository#findRetryGuardSnapshot} 네이티브 쿼리 매핑 회귀 테스트.
 *
 * <p>T21 E2E에서 {@code Optional<Object[]>} 반환이 Hibernate 6에서 {@code Object[][]}로
 * 한 겹 더 감싸져 {@code ClassCastException}으로 재처리 전체가 500 장애가 났던 회귀를 잠그기 위함.
 *
 * <p>이 테스트가 지키는 계약:
 * <ul>
 *   <li>Tuple 이름 기반 추출이 9개 alias 모두에 대해 동작</li>
 *   <li>{@code RetryGuardSnapshot#fromTuple}이 보안 경계의 핵심 필드
 *       (parentActorId, rootActorId, interfaceStatus, parentStatus)를 정확히 채움</li>
 *   <li>원본 로그(parent/root 미지정)에 대해 rootId = parentId, rootActor = parentActor
 *       로 COALESCE가 정상 동작</li>
 * </ul>
 *
 * <p>로컬 실행 조건: {@code DOCKER_HOST} 환경변수가 설정되어 있을 때만 활성화.
 * Windows Docker Desktop에서는 예:
 * {@code DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine}.
 * CI에서는 항상 설정되어 있다고 가정.
 */
@EnabledIfEnvironmentVariable(named = "DOCKER_HOST", matches = ".+")
class RetryGuardSnapshotQueryTest extends AbstractPostgresIntegrationTest {

    @Autowired ExecutionLogRepository   logRepository;
    @Autowired ExecutionLogTestSeeder   seeder;

    @Test
    void rootLog_snapshotMapsAllFieldsByAlias() {
        InterfaceConfig ifc = seeder.seedInterface("retry-snapshot-root",
                InterfaceStatus.ACTIVE, 3);
        Long rootLogId = seeder.seedLog(
                ifc.getId(),
                ExecutionStatus.FAILED,
                "operator-root",
                /* parentId */ null,
                /* rootId   */ null,
                /* retryCount      */ 0,
                /* maxRetrySnapshot*/ 3);

        Optional<RetryGuardSnapshot> opt = logRepository
                .findRetryGuardSnapshot(rootLogId)
                .map(RetryGuardSnapshot::fromTuple);

        assertThat(opt).isPresent();
        RetryGuardSnapshot snap = opt.orElseThrow();
        assertThat(snap.parentId()).isEqualTo(rootLogId);
        assertThat(snap.parentActorId()).isEqualTo("operator-root");
        assertThat(snap.parentRetryCount()).isEqualTo(0);
        assertThat(snap.maxRetrySnapshot()).isEqualTo(3);
        assertThat(snap.payloadTruncated()).isFalse();
        assertThat(snap.parentStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(snap.rootId()).isEqualTo(rootLogId);            // COALESCE(NULL, id)
        assertThat(snap.rootActorId()).isEqualTo("operator-root"); // 자기 자신 actor
        assertThat(snap.interfaceStatus()).isEqualTo(InterfaceStatus.ACTIVE);
    }

    @Test
    void childLog_snapshotResolvesRootActorFromChain() {
        InterfaceConfig ifc = seeder.seedInterface("retry-snapshot-child",
                InterfaceStatus.ACTIVE, 3);
        Long rootId = seeder.seedLog(
                ifc.getId(), ExecutionStatus.FAILED, "operator-root",
                null, null, 0, 3);
        Long childId = seeder.seedLog(
                ifc.getId(), ExecutionStatus.FAILED, "operator-child",
                /* parentId */ rootId,
                /* rootId   */ rootId,
                /* retryCount */ 1,
                /* maxRetrySnapshot (캐스케이드) */ 3);

        RetryGuardSnapshot snap = logRepository
                .findRetryGuardSnapshot(childId)
                .map(RetryGuardSnapshot::fromTuple)
                .orElseThrow();

        // RETRY_FORBIDDEN_ACTOR는 rootActor 기준 (ADR-005 Q2) — 체인 자식에서도 루트 actor가 들어와야 함
        assertThat(snap.parentId()).isEqualTo(childId);
        assertThat(snap.parentActorId()).isEqualTo("operator-child");
        assertThat(snap.rootId()).isEqualTo(rootId);
        assertThat(snap.rootActorId()).isEqualTo("operator-root");
        assertThat(snap.parentRetryCount()).isEqualTo(1);
    }

    @Test
    void missingLog_returnsEmpty() {
        Optional<RetryGuardSnapshot> opt = logRepository
                .findRetryGuardSnapshot(9_999_999L)
                .map(RetryGuardSnapshot::fromTuple);
        assertThat(opt).isEmpty();
    }
}
