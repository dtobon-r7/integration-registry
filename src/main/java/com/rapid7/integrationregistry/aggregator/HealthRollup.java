package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;

import java.util.Objects;

/**
 * Worst-state-wins reduction over the RFC-001 status precedence:
 * {@code error > missing_data > warning > disabled > healthy}.
 *
 * <p>Call sites that need to reduce a stream of statuses can use
 * {@code stream.reduce(HealthRollup::worstOf)} directly.
 */
public final class HealthRollup {

    private HealthRollup() {}

    public static IntegrationStatus worstOf(IntegrationStatus a, IntegrationStatus b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(IntegrationStatus s) {
        return switch (s) {
            case ERROR        -> 4;
            case MISSING_DATA -> 3;
            case WARNING      -> 2;
            case DISABLED     -> 1;
            case HEALTHY      -> 0;
        };
    }
}
