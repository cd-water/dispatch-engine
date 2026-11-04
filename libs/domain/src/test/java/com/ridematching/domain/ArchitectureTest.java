package com.ridematching.domain;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 强制执行 ADR-0001 中的依赖规则。
 *
 * <p>六边形架构会在无声中腐化：有人为了图方便给某个领域记录加上一个 Jackson 注解，
 * 几个月后领域模块就传递依赖了半个 Spring。不被检查的规则算不上规则，所以这条
 * 检查被纳入构建。
 */
class ArchitectureTest {

    private static JavaClasses domainClasses;

    @BeforeAll
    static void importDomain() {
        domainClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ridematching.domain");
    }

    @Test
    @DisplayName("domain must not depend on any framework")
    void domainIsFrameworkFree() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "jakarta.validation..",
                        // 两个代次的 Jackson 都要禁：Boot 4 把 Jackson 3 放在 tools.jackson 之下，
                        // 只禁旧包名的话，这条规则本要堵住的漏洞会被无声地重新打开。
                        "com.fasterxml.jackson..",
                        "tools.jackson..",
                        "org.apache.kafka..",
                        "io.lettuce..",
                        "redis.clients..",
                        "org.hibernate..",
                        "io.micrometer..")
                .because("the domain model must be unit-testable without infrastructure (ADR-0001)")
                .check(domainClasses);
    }

    @Test
    @DisplayName("domain must not reach back into adapters or services")
    void domainDoesNotDependOnOuterLayers() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ridematching.platform..",
                        "..adapters..",
                        "..application..")
                .because("dependencies point inward: adapters -> application -> domain (ADR-0001)")
                .check(domainClasses);
    }
}
