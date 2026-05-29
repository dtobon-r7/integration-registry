package com.rapid7.integrationregistry.architecture;

import com.rapid7.integrationregistry.adapter.exception.AdapterException;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

final class LayerDependencyRules {

    static final ArchRule controllerLayer_shouldNotDependOnInternalLayers =
            noClasses().that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..coordinator..", "..adapter..", "..aggregator..", "..mapping..");

    static final ArchRule serviceLayer_shouldNotDependOnWebLayer =
            noClasses().that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.servlet..", "org.springframework.web..", "org.springframework.http..");

    static final ArchRule serviceLayer_shouldNotDependOnMappingLayer =
            noClasses().that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().resideInAnyPackage("..mapping..");

    static final ArchRule coordinatorLayer_shouldNotDependOnDisallowedLayers =
            noClasses().that().resideInAPackage("..coordinator..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..controller..", "..service..", "..aggregator..", "..mapping..");

    static final ArchRule aggregatorLayer_shouldOnlyDependOnAdapterAndMapping =
            noClasses().that().resideInAPackage("..aggregator..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..controller..", "..service..", "..coordinator..");

    static final ArchRule adapterLayer_shouldNotDependOnInternalLayers =
            noClasses().that().resideInAPackage("..adapter..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..controller..", "..service..", "..coordinator..", "..aggregator..", "..mapping..");

    static final ArchRule mappingCoreLayer_shouldNotDependOnFrameworks =
            noClasses().that().resideInAPackage("..mapping..")
                    .and().resideOutsideOfPackages("..mapping.loader..", "..mapping.exception..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..", "software.amazon.awssdk..");

    static final ArchRule adapterExceptions_shouldExtendAdapterExceptionParent =
            classes().that().resideInAPackage("..adapter.exception..")
                    .and().areNotInterfaces()
                    .and(DescribedPredicate.not(JavaClass.Predicates.type(AdapterException.class)))
                    .should().beAssignableTo(AdapterException.class)
                    .because("ADR-001: every concrete class in adapter.exception must extend "
                            + "AdapterException (the family parent). The abstract parent itself "
                            + "is excluded.");

    private LayerDependencyRules() {}
}
