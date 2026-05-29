package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.rapid7.integrationregistry.adapter.IntegrationStatus.DISABLED;
import static com.rapid7.integrationregistry.adapter.IntegrationStatus.ERROR;
import static com.rapid7.integrationregistry.adapter.IntegrationStatus.HEALTHY;
import static com.rapid7.integrationregistry.adapter.IntegrationStatus.MISSING_DATA;
import static com.rapid7.integrationregistry.adapter.IntegrationStatus.WARNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class HealthRollupTest {

    @ParameterizedTest(name = "worstOf({0}, {1}) = {2}")
    @MethodSource("statusPairs")
    void worstOf_shouldReturnRfcWorstState_forEveryPair(
        IntegrationStatus a, IntegrationStatus b, IntegrationStatus expected) {
        // Act
        IntegrationStatus result = HealthRollup.worstOf(a, b);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest(name = "worstOf({0}, {1}) is symmetric")
    @MethodSource("statusPairs")
    void worstOf_shouldBeSymmetric_forEveryPair(
        IntegrationStatus a, IntegrationStatus b, IntegrationStatus expected) {
        // Act
        IntegrationStatus forward = HealthRollup.worstOf(a, b);
        IntegrationStatus reverse = HealthRollup.worstOf(b, a);

        // Assert
        assertThat(forward).isEqualTo(reverse);
    }

    @Test
    void worstOf_shouldThrowNPE_whenFirstArgNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> HealthRollup.worstOf(null, HEALTHY))
            .withMessage("a");
    }

    @Test
    void worstOf_shouldThrowNPE_whenSecondArgNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> HealthRollup.worstOf(HEALTHY, null))
            .withMessage("b");
    }

    /**
     * 5x5 matrix of all ordered status pairs with the RFC-001 expected worst state.
     * Precedence: error > missing_data > warning > disabled > healthy.
     */
    private static Stream<Arguments> statusPairs() {
        return Stream.of(
            // ERROR row — error wins against everything
            Arguments.of(ERROR, ERROR, ERROR),
            Arguments.of(ERROR, MISSING_DATA, ERROR),
            Arguments.of(ERROR, WARNING, ERROR),
            Arguments.of(ERROR, DISABLED, ERROR),
            Arguments.of(ERROR, HEALTHY, ERROR),
            // MISSING_DATA row
            Arguments.of(MISSING_DATA, ERROR, ERROR),
            Arguments.of(MISSING_DATA, MISSING_DATA, MISSING_DATA),
            Arguments.of(MISSING_DATA, WARNING, MISSING_DATA),
            Arguments.of(MISSING_DATA, DISABLED, MISSING_DATA),
            Arguments.of(MISSING_DATA, HEALTHY, MISSING_DATA),
            // WARNING row
            Arguments.of(WARNING, ERROR, ERROR),
            Arguments.of(WARNING, MISSING_DATA, MISSING_DATA),
            Arguments.of(WARNING, WARNING, WARNING),
            Arguments.of(WARNING, DISABLED, WARNING),
            Arguments.of(WARNING, HEALTHY, WARNING),
            // DISABLED row
            Arguments.of(DISABLED, ERROR, ERROR),
            Arguments.of(DISABLED, MISSING_DATA, MISSING_DATA),
            Arguments.of(DISABLED, WARNING, WARNING),
            Arguments.of(DISABLED, DISABLED, DISABLED),
            Arguments.of(DISABLED, HEALTHY, DISABLED),
            // HEALTHY row
            Arguments.of(HEALTHY, ERROR, ERROR),
            Arguments.of(HEALTHY, MISSING_DATA, MISSING_DATA),
            Arguments.of(HEALTHY, WARNING, WARNING),
            Arguments.of(HEALTHY, DISABLED, DISABLED),
            Arguments.of(HEALTHY, HEALTHY, HEALTHY)
        );
    }
}
