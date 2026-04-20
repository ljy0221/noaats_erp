package com.noaats.ifms.domain.interface_.service;

import com.noaats.ifms.domain.interface_.domain.InterfaceConfig;
import com.noaats.ifms.domain.interface_.domain.InterfaceStatus;
import com.noaats.ifms.domain.interface_.domain.ProtocolType;
import org.springframework.data.jpa.domain.Specification;

/**
 * 인터페이스 목록 조회용 동적 필터 빌더 (api-spec.md §4.1).
 *
 * 술어 순서: status → protocol → nameLike (DBA 지정).
 * `idx_ifc_status_name(status, name)` 복합 인덱스와 `idx_ifc_protocol WHERE status='ACTIVE'`
 * 부분 인덱스를 활용. nameLike는 양측 와일드카드라 B-tree 인덱스 활용 불가 → Seq/Bitmap Scan 후처리.
 *
 * `name` 입력의 {@code %}·{@code _}·{@code \\}는 LIKE 메타 문자이므로 이스케이프 처리.
 */
public final class InterfaceConfigSpecification {

    private static final char LIKE_ESCAPE = '\\';

    private InterfaceConfigSpecification() {
    }

    /**
     * 동적 필터 조합. null 파라미터는 해당 술어 생략.
     */
    public static Specification<InterfaceConfig> filter(
            InterfaceStatus status, ProtocolType protocol, String name) {
        Specification<InterfaceConfig> spec = Specification.allOf();
        if (status != null) {
            spec = spec.and(statusEq(status));
        }
        if (protocol != null) {
            spec = spec.and(protocolEq(protocol));
        }
        if (name != null && !name.isBlank()) {
            spec = spec.and(nameLike(name));
        }
        return spec;
    }

    private static Specification<InterfaceConfig> statusEq(InterfaceStatus s) {
        return (root, query, cb) -> cb.equal(root.get("status"), s);
    }

    private static Specification<InterfaceConfig> protocolEq(ProtocolType p) {
        return (root, query, cb) -> cb.equal(root.get("protocol"), p);
    }

    private static Specification<InterfaceConfig> nameLike(String raw) {
        String escaped = escapeLike(raw);
        String pattern = "%" + escaped + "%";
        return (root, query, cb) -> cb.like(root.get("name"), pattern, LIKE_ESCAPE);
    }

    /** LIKE 메타 문자 이스케이프 (Security SHOULD: `%_\` 이스케이프). */
    private static String escapeLike(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
