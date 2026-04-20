# ADR-006: ConfigJsonValidator 호출 지점 최종 결정

**상태**: 결정됨
**날짜**: 2026-04-20
**결정자**: @Architect (사회자), @Security·@DBA·@DevilsAdvocate 합의

---

## 컨텍스트

v0.1에서 `InterfaceConfigListener`(`@PrePersist`/`@PreUpdate`)로 `configJson` 마스킹·검증을 수행했으나, EntityListener의 Spring Bean 주입 한계(`SpringBeanContainer` 미등록 시 silent null) 때문에 v0.2에서 **Service 단일 호출 + ArchUnit 방어**로 전환 합의 (4-에이전트 전원 A 수렴).

재회의에서 @Security는 MUST를 **철회**했고, @DBA는 `EntityManager.merge()` 직접 호출·타 도메인 Repository 직접 주입 등 Service 우회 경로가 실재함을 근거로 **하이브리드**(EntityListener + Service 이중)를 제안했다. @DevilsAdvocate는 **v0.2 유지**를 재확인했다.

erd.md §3.3에는 v0.1의 `InterfaceConfigListener` 코드 예시가 그대로 남아 있어 **문서 drift** 상태다.

---

## 트레이드오프

| 옵션 | 장점 | 단점 |
|---|---|---|
| **A. v0.2 유지 (Service 단일 호출 + ArchUnit)** | 호출 1회, CPU·로그 단순. ArchUnit으로 Repository 직접 주입 정적 차단. 개인 프로토타입 일정에 부합 | `merge()` 직접 호출·네이티브 쿼리는 정적 분석으로 못 잡음 |
| B. 하이브리드 (Listener 복원 + Service 호출) | 감사 요건 이중 방어. Listener가 최후 방어선 | 이중 마스킹 CPU 2배. Listener Bean 주입 실패 silent null 재발. 테스트 복잡도 증가 |

---

## 결정

**옵션 A (v0.2 유지)** 채택. @Security MUST 철회 수용, @DevilsAdvocate 의견 채택.

---

## 근거

1. 프로토타입 1주 / 1인 규모에서 하이브리드 이중 경로는 유지비용이 편익을 초과
2. @DBA가 제기한 `merge()`·네이티브 우회는 ArchUnit 3종 + 단위 테스트로 99% 차단 가능. 남는 1%는 코드 리뷰 항목으로 관리
3. Listener silent null은 금융 감사 요건(저장본=마스킹본)을 **침묵으로** 위반. 명시적 실패(Service 누락 시 ArchUnit 빌드 실패)가 우위
4. `test.dependsOn(archTest)` Gradle 강제로 우회 PR은 CI에서 실패

---

## 수용한 리스크

- **R1**: `EntityManager.merge()` 직접 호출 시 마스킹 누락 가능 → ArchUnit으로 Service 외 merge 호출 금지 규칙 추가로 완화
- **R2**: 네이티브 SQL `UPDATE` 시 마스킹 우회 → 본 ADR에 명시하고 코드 리뷰 체크리스트에 등재. JPQL/네이티브 UPDATE는 Service 메서드로만 허용
- **R3**: Reflection·테스트 코드의 Repository 직접 호출은 허용 (프로덕션 코드에만 ArchUnit 적용)

---

## 후속 조치

- [ ] **erd.md §3.3 drift 해결**: `InterfaceConfigListener` 코드 예시 제거, "마스킹·검증은 `InterfaceConfigService`에서 수행. ADR-006 참조" 주석으로 대체
- [ ] **ArchUnit 규칙 3종 명시** (`backend/src/test/java/.../ArchitectureTest.java` — Day 2-B 이후 별도 테스트 묶음에서 작성):
  1. `InterfaceConfigRepository`는 `InterfaceConfigService`에서만 주입 가능
  2. `EntityManager.merge(InterfaceConfig)` 호출은 `InterfaceConfigService`에서만 허용
  3. `@Modifying` 쿼리 메서드는 `InterfaceConfigRepository` 외 선언 금지
- [ ] **단위 테스트 요건** (Day 2-B 또는 Day 7 통합 테스트): `InterfaceConfigServiceTest` — save/update 양쪽에서 `ConfigJsonValidator.validate()` 1회 호출 검증 (Mockito `verify`), 민감키(`password`·`apiKey`) 마스킹된 엔티티 저장 확인
- [ ] **Gradle 강제**: `test.dependsOn(archTest)` 추가로 ArchUnit 실패 시 전체 테스트 차단
- [ ] **코드 리뷰 체크리스트**: 네이티브 `UPDATE` 추가 시 마스킹 재검증 항목 포함

---

## 관련 ADR

- ADR-001 (ExecutionLog 트랜잭션 범위) — Service 단일 진입점 원칙 공유
- ADR-004 (동시 실행 중복 방지) — advisory lock + UNIQUE 제약은 본 ADR 제약 적용
