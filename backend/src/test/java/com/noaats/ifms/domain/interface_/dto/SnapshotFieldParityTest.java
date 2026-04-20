package com.noaats.ifms.domain.interface_.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link InterfaceConfigDetailResponse}와 {@link InterfaceConfigSnapshot}의 필드 정합 회귀 보호.
 *
 * <p>Architect Day 2-B SHOULD: Detail에 필드를 추가하면서 Snapshot에 누락하면 409 OPTIMISTIC_LOCK_CONFLICT
 * 응답이 핵심 정보를 못 보여줘 UX·감사 추적에 구멍이 생긴다. 본 테스트가 그 회귀를 차단.
 *
 * <p>의도적 제외 필드는 {@link #SNAPSHOT_INTENTIONALLY_OMITTED} 상수에 사유와 함께 명시.
 * 새 필드를 의도적으로 제외하려면 본 set에 추가 + 사유 주석. 그렇지 않으면 Snapshot에 함께 추가해야.
 */
class SnapshotFieldParityTest {

    /**
     * Snapshot에 의도적으로 빠진 필드.
     * <ul>
     *   <li>{@code configJson} — 409 UX는 핵심 필드만 노출, 마스킹 재실행·PII 노출 위험 회피</li>
     *   <li>{@code createdAt} — 충돌 대조에는 무관 (updatedAt만 있으면 충분)</li>
     * </ul>
     */
    private static final Set<String> SNAPSHOT_INTENTIONALLY_OMITTED = Set.of(
            "configJson",
            "createdAt"
    );

    @Test
    void detailMinusOmittedEqualsSnapshot() {
        Set<String> detail = fieldNames(InterfaceConfigDetailResponse.class);
        Set<String> snap = fieldNames(InterfaceConfigSnapshot.class);

        Set<String> detailExpected = detail.stream()
                .filter(n -> !SNAPSHOT_INTENTIONALLY_OMITTED.contains(n))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> snapSorted = new TreeSet<>(snap);

        assertThat(snapSorted)
                .as("Snapshot must equal Detail minus intentionally-omitted fields. "
                        + "If you added a Detail field, decide: include in Snapshot, or add to "
                        + "SNAPSHOT_INTENTIONALLY_OMITTED with rationale.")
                .isEqualTo(detailExpected);
    }

    private Set<String> fieldNames(Class<?> c) {
        if (c.isRecord()) {
            return Arrays.stream(c.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toSet());
        }
        return Arrays.stream(c.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());
    }
}
