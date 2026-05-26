package com.rapid7.integrationregistry.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationStatusTest {

    @Test
    void values_shouldContainExactlyFiveStates_whenInspected() {
        // Arrange
        // (no setup — IntegrationStatus.values() is a class-level invariant)

        // Act
        IntegrationStatus[] all = IntegrationStatus.values();

        // Assert
        assertThat(all).containsExactly(
            IntegrationStatus.HEALTHY,
            IntegrationStatus.WARNING,
            IntegrationStatus.ERROR,
            IntegrationStatus.MISSING_DATA,
            IntegrationStatus.DISABLED
        );
    }

    @Test
    void valueOf_shouldResolveAllFiveConstants_whenLookedUpByName() {
        // Arrange
        String[] names = {"HEALTHY", "WARNING", "ERROR", "MISSING_DATA", "DISABLED"};

        // Act + Assert
        for (String name : names) {
            assertThat(IntegrationStatus.valueOf(name).name()).isEqualTo(name);
        }
    }
}
