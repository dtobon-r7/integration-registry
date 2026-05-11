package com.rapid7.integrationregistry.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.rapid7.integrationregistry", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyRulesTest {

    @ArchTest
    static final ArchRule controllerLayer_shouldNotDependOnInternalLayers =
            noClasses().that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..coordinator..", "..adapter..", "..aggregator..", "..mapping..");

    @ArchTest
    static final ArchRule serviceLayer_shouldNotDependOnWebLayer =
            noClasses().that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.servlet..", "org.springframework.web..", "org.springframework.http..");

    @ArchTest
    static final ArchRule coordinatorLayer_shouldNotDependOnDisallowedLayers =
            noClasses().that().resideInAPackage("..coordinator..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..controller..", "..aggregator..", "..mapping..");

    @ArchTest
    static final ArchRule aggregatorLayer_shouldOnlyDependOnMapping =
            noClasses().that().resideInAPackage("..aggregator..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..controller..", "..service..", "..coordinator..", "..adapter..");

    @ArchTest
    static final ArchRule adapterLayer_shouldNotDependOnInternalLayers =
            noClasses().that().resideInAPackage("..adapter..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..controller..", "..service..", "..coordinator..", "..aggregator..", "..mapping..");
}
