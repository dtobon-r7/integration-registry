package com.rapid7.integrationregistry.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerDependencyViolationDetectionTest {

    private static final JavaClasses allClasses = new ClassFileImporter()
            .importPackages("com.rapid7.integrationregistry");

    @Test
    void controllerLayer_shouldDetectCoordinatorViolation() {
        EvaluationResult result = LayerDependencyRules.controllerLayer_shouldNotDependOnInternalLayers
                .evaluate(allClasses);

        assertTrue(result.hasViolation(),
                "Expected ArchUnit to detect controller→coordinator violation from fixture class");
    }

    @Test
    void serviceLayer_shouldDetectHttpViolation() {
        EvaluationResult result = LayerDependencyRules.serviceLayer_shouldNotDependOnWebLayer
                .evaluate(allClasses);

        assertTrue(result.hasViolation(),
                "Expected ArchUnit to detect service→HTTP violation from fixture class");
    }

    @Test
    void serviceLayer_shouldDetectMappingViolation() {
        EvaluationResult result = LayerDependencyRules.serviceLayer_shouldNotDependOnMappingLayer
                .evaluate(allClasses);

        assertTrue(result.hasViolation(),
                "Expected ArchUnit to detect service→mapping violation from fixture class");
    }
}
