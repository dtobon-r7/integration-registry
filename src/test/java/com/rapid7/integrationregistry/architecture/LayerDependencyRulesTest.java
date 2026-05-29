package com.rapid7.integrationregistry.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.rapid7.integrationregistry", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyRulesTest {

    @ArchTest
    static final ArchRule controllerLayer_shouldNotDependOnInternalLayers =
            LayerDependencyRules.controllerLayer_shouldNotDependOnInternalLayers;

    @ArchTest
    static final ArchRule serviceLayer_shouldNotDependOnWebLayer =
            LayerDependencyRules.serviceLayer_shouldNotDependOnWebLayer;

    @ArchTest
    static final ArchRule serviceLayer_shouldNotDependOnMappingLayer =
            LayerDependencyRules.serviceLayer_shouldNotDependOnMappingLayer;

    @ArchTest
    static final ArchRule coordinatorLayer_shouldNotDependOnDisallowedLayers =
            LayerDependencyRules.coordinatorLayer_shouldNotDependOnDisallowedLayers;

    @ArchTest
    static final ArchRule aggregatorLayer_shouldOnlyDependOnAdapterAndMapping =
            LayerDependencyRules.aggregatorLayer_shouldOnlyDependOnAdapterAndMapping;

    @ArchTest
    static final ArchRule adapterLayer_shouldNotDependOnInternalLayers =
            LayerDependencyRules.adapterLayer_shouldNotDependOnInternalLayers;

    @ArchTest
    static final ArchRule mappingCoreLayer_shouldNotDependOnFrameworks =
            LayerDependencyRules.mappingCoreLayer_shouldNotDependOnFrameworks;

    @ArchTest
    static final ArchRule adapterExceptions_shouldExtendAdapterExceptionParent =
            LayerDependencyRules.adapterExceptions_shouldExtendAdapterExceptionParent;
}
