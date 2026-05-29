package com.rapid7.integrationregistry.adapter.insightconnect;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionStatusMapperTest {

    private final ConnectionStatusMapper mapper = new ConnectionStatusMapper();

    private static ConnectionTest test(String status, Boolean isStale, Instant createdAt) {
        return new ConnectionTest("t", "c", status, isStale, null, createdAt);
    }

    private static final Instant T1 = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-05-03T00:00:00Z");

    // ---- deriveStatus: single-signal happy paths ----

    @Test
    void deriveStatus_shouldReturnHealthy_whenOrchestratorHealthyAndTestSuccessAndNotStale() {
        // Arrange
        ConnectionTest t = test("success", false, T1);
        // Act
        IntegrationStatus status = mapper.deriveStatus("healthy", t);
        // Assert
        assertThat(status).isEqualTo(IntegrationStatus.HEALTHY);
    }

    @Test
    void deriveStatus_shouldReturnError_whenOrchestratorError() {
        // Arrange
        ConnectionTest t = test("success", false, T1);
        // Act / Assert
        assertThat(mapper.deriveStatus("error", t)).isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldReturnError_whenTestFailed() {
        assertThat(mapper.deriveStatus("healthy", test("failed", false, T1)))
            .isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldReturnError_whenTestTimeout() {
        assertThat(mapper.deriveStatus("healthy", test("timeout", false, T1)))
            .isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenTestStale() {
        assertThat(mapper.deriveStatus("healthy", test("success", true, T1)))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorUnknown() {
        assertThat(mapper.deriveStatus("unknown", test("success", false, T1)))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldReturnWarning_whenOrchestratorWarning() {
        assertThat(mapper.deriveStatus("warning", test("success", false, T1)))
            .isEqualTo(IntegrationStatus.WARNING);
    }

    @Test
    void deriveStatus_shouldReturnDisabled_whenOrchestratorStopped() {
        assertThat(mapper.deriveStatus("stopped", test("success", false, T1)))
            .isEqualTo(IntegrationStatus.DISABLED);
    }

    // ---- deriveStatus: precedence + no-test + edge cases ----

    @Test
    void deriveStatus_shouldReturnError_whenTestFailedAndOrchestratorWarning() {
        // Precedence: error > warning
        assertThat(mapper.deriveStatus("warning", test("failed", false, T1)))
            .isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorHealthyAndNoTest() {
        // No confirming healthy test => cannot confirm it works
        assertThat(mapper.deriveStatus("healthy", null))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorHealthyAndTestPending() {
        assertThat(mapper.deriveStatus("healthy", test("pending", false, T1)))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldTreatNullIsStaleAsNotStale_whenHealthySuccess() {
        // null isStale must not block healthy
        assertThat(mapper.deriveStatus("healthy", test("success", null, T1)))
            .isEqualTo(IntegrationStatus.HEALTHY);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorUnrecognized() {
        // Forward-compat: a 6th orchestrator value falls back to missing_data
        assertThat(mapper.deriveStatus("decommissioning", test("success", false, T1)))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorNull() {
        assertThat(mapper.deriveStatus(null, test("success", false, T1)))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    // ---- mostRecentByCreatedAt ----

    @Test
    void mostRecentByCreatedAt_shouldReturnLatest_whenMultipleTests() {
        // Arrange — deliberately out of order
        List<ConnectionTest> tests = List.of(
            test("failed", false, T1),
            test("success", false, T3),
            test("success", false, T2));
        // Act
        ConnectionTest latest = mapper.mostRecentByCreatedAt(tests);
        // Assert
        assertThat(latest.createdAt()).isEqualTo(T3);
    }

    @Test
    void mostRecentByCreatedAt_shouldReturnNull_whenEmpty() {
        assertThat(mapper.mostRecentByCreatedAt(List.of())).isNull();
    }

    @Test
    void mostRecentByCreatedAt_shouldReturnNull_whenNull() {
        assertThat(mapper.mostRecentByCreatedAt(null)).isNull();
    }

    // ---- deriveLastSuccess ----

    @Test
    void deriveLastSuccess_shouldReturnMostRecentSuccessTimestamp_whenSuccessesExist() {
        List<ConnectionTest> tests = List.of(
            test("success", false, T1),
            test("success", false, T2));
        assertThat(mapper.deriveLastSuccess(tests)).isEqualTo(T2);
    }

    @Test
    void deriveLastSuccess_shouldReturnNull_whenNoSuccess() {
        List<ConnectionTest> tests = List.of(
            test("failed", false, T1),
            test("timeout", false, T2));
        assertThat(mapper.deriveLastSuccess(tests)).isNull();
    }

    @Test
    void deriveLastSuccess_shouldReturnLastSuccess_whenLatestOverallIsFailure() {
        // Latest overall (T3) failed, but T2 succeeded => report T2
        List<ConnectionTest> tests = List.of(
            test("success", false, T2),
            test("failed", false, T3),
            test("failed", false, T1));
        assertThat(mapper.deriveLastSuccess(tests)).isEqualTo(T2);
    }

    @Test
    void deriveLastSuccess_shouldReturnNull_whenNullOrEmpty() {
        assertThat(mapper.deriveLastSuccess(null)).isNull();
        assertThat(mapper.deriveLastSuccess(List.of())).isNull();
    }
}
