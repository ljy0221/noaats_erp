package com.noaats.ifms.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

/**
 * ADR-006 후속 아키텍처 규칙 3종 (Day 4).
 *
 * <ol>
 *   <li><b>Repository 주입 범위</b>: Repository는 Service·Repository·Config 패키지에서만 의존 허용.
 *       Controller가 Repository를 직접 주입하면 TX 경계가 흐려진다.</li>
 *   <li><b>EntityManager.merge 금지</b>: ID 재할당·detached 상태 혼재로 잠재 버그 유발.
 *       새 엔티티는 {@code save}, 기존 엔티티는 상태 전이 메서드로 수정.</li>
 *   <li><b>@Modifying 사용 범위</b>: {@code @Modifying} 쿼리 메서드는 Repository 인터페이스에서만
 *       선언해야 캐시·플러시 규칙이 일관된다.</li>
 * </ol>
 */
@AnalyzeClasses(
        packages = "com.noaats.ifms",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule repositoriesOnlyInjectedInServiceOrRepository =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "..service..",
                            "..repository..",
                            "..config..")
                    .should().dependOnClassesThat().areAssignableTo(JpaRepository.class)
                    .because("Repository는 Service/Repository 레이어에서만 주입되어야 한다 (ADR-006)");

    @ArchTest
    static final ArchRule noEntityManagerMerge =
            noClasses()
                    .should().callMethod(EntityManager.class, "merge", Object.class)
                    .because("EntityManager.merge는 ID 재할당 위험으로 금지 (ADR-006 §4)");

    @ArchTest
    static final ArchRule modifyingOnlyOnRepositories =
            methods()
                    .that().areAnnotatedWith(Modifying.class)
                    .should().beDeclaredInClassesThat().resideInAPackage("..repository..")
                    .because("@Modifying은 Repository 인터페이스 메서드에만 허용 (ADR-006)");
}
