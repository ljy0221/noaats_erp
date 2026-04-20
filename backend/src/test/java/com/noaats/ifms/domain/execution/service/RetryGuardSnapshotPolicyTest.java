package com.noaats.ifms.domain.execution.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noaats.ifms.domain.execution.domain.ExecutionStatus;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.global.exception.ApiException;
import com.noaats.ifms.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * ADR-005 §5.2의 RetryGuard 5종 평가 단위 테스트.
 *
 * <p>RetryGuardSnapshot은 record이고 RetryGuard는 의존성 없는 POJO이므로 Spring 컨텍스트 없이
 * 직접 검증 가능. 통합 경로는 {@code RetryServiceIntegrationTest}(Testcontainers 환경 회복 시 활성).
 *
 * <p>커버 범위:
 * <ul>
 *   <li>Q1: max_retry_snapshot이 현재 config가 아닌 시작 시점 값을 반영해야 함</li>
 *   <li>Q2: 체인 루트 actor 기준 RETRY_FORBIDDEN_ACTOR (parent actor 무관)</li>
 *   <li>Q2-1: SYSTEM 루트는 모든 OPERATOR가 재처리 가능</li>
 *   <li>Q2-2: ANONYMOUS_* 루트는 재처리 금지</li>
 *   <li>truncated 차단</li>
 *   <li>전 검사 통과 시 예외 없음</li>
 * </ul>
 */
class RetryGuardSnapshotPolicyTest {

    private final RetryGuard guard = new RetryGuard();

    private RetryGuardSnapshot snap(String parentActor, int retryCount, int maxSnapshot,
                                    boolean truncated, ExecutionStatus parentStatus,
                                    String rootActor, InterfaceStatus icStatus) {
        return new RetryGuardSnapshot(
                1L, parentActor, retryCount, maxSnapshot,
                truncated, parentStatus, 1L, rootActor, icStatus);
    }

    @Test
    void q1_maxSnapshotIsRespectedRegardlessOfCurrentConfig() {
        // ADR-005 Q1: snapshot=1, retryCount=1 → +1 > 1 위반
        var s = snap("actorA", 1, 1, false, ExecutionStatus.FAILED, "actorA", InterfaceStatus.ACTIVE);
        assertThatThrownBy(() -> guard.verify(s, "actorA"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_LIMIT_EXCEEDED);
    }

    @Test
    void q2_rootActorMismatchOverridesParentActor() {
        // ADR-005 Q2: parent actor=B, root actor=A. 세션=B → root 기준 false
        var s = snap("actorB", 0, 3, false, ExecutionStatus.FAILED, "actorA", InterfaceStatus.ACTIVE);
        assertThatThrownBy(() -> guard.verify(s, "actorB"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_FORBIDDEN_ACTOR);
    }

    @Test
    void q2_systemRootIsAllowedForAnyOperator() {
        var s = snap("SYSTEM", 0, 3, false, ExecutionStatus.FAILED, "SYSTEM", InterfaceStatus.ACTIVE);
        assertThatCode(() -> guard.verify(s, "operatorX")).doesNotThrowAnyException();
    }

    @Test
    void q2_anonymousRootIsForbidden() {
        var s = snap("ANONYMOUS_abc", 0, 3, false, ExecutionStatus.FAILED, "ANONYMOUS_abc",
                InterfaceStatus.ACTIVE);
        assertThatThrownBy(() -> guard.verify(s, "operatorX"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_FORBIDDEN_ACTOR);
    }

    @Test
    void truncatedPayload_isBlocked() {
        var s = snap("actorA", 0, 3, true, ExecutionStatus.FAILED, "actorA", InterfaceStatus.ACTIVE);
        assertThatThrownBy(() -> guard.verify(s, "actorA"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_TRUNCATED_BLOCKED);
    }

    @Test
    void inactiveInterface_isBlocked() {
        var s = snap("actorA", 0, 3, false, ExecutionStatus.FAILED, "actorA",
                InterfaceStatus.INACTIVE);
        assertThatThrownBy(() -> guard.verify(s, "actorA"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERFACE_INACTIVE);
    }

    @Test
    void parentNotFailed_isNotLeaf() {
        var s = snap("actorA", 0, 3, false, ExecutionStatus.SUCCESS, "actorA",
                InterfaceStatus.ACTIVE);
        assertThatThrownBy(() -> guard.verify(s, "actorA"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RETRY_NOT_LEAF);
    }

    @Test
    void allCheckPass_doesNotThrow() {
        var s = snap("actorA", 0, 3, false, ExecutionStatus.FAILED, "actorA", InterfaceStatus.ACTIVE);
        assertThatCode(() -> guard.verify(s, "actorA")).doesNotThrowAnyException();
    }
}
