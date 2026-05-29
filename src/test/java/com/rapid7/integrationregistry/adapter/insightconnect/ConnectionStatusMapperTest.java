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

    /** Wrap a single test as the connection's test list (deriveStatus selects the most recent). */
    private static List<ConnectionTest> tests(ConnectionTest t) {
        return List.of(t);
    }

    private static final Instant T1 = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-05-03T00:00:00Z");

    // ---- deriveStatus: single-signal happy paths ----

    @Test
    void deriveStatus_shouldReturnHealthy_whenOrchestratorHealthyAndTestSuccessAndNotStale() {
        // Arrange
        List<ConnectionTest> t = tests(test("success", false, T1));
        // Act
        IntegrationStatus status = mapper.deriveStatus("healthy", t);
        // Assert
        assertThat(status).isEqualTo(IntegrationStatus.HEALTHY);
    }

    @Test
    void deriveStatus_shouldReturnError_whenOrchestratorError() {
        // Arrange
        List<ConnectionTest> t = tests(test("success", false, T1));
        // Act / Assert
        assertThat(mapper.deriveStatus("error", t)).isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldReturnError_whenTestFailed() {
        assertThat(mapper.deriveStatus("healthy", tests(test("failed", false, T1))))
            .isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldReturnError_whenTestTimeout() {
        assertThat(mapper.deriveStatus("healthy", tests(test("timeout", false, T1))))
            .isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenTestStale() {
        assertThat(mapper.deriveStatus("healthy", tests(test("success", true, T1))))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorUnknown() {
        assertThat(mapper.deriveStatus("unknown", tests(test("success", false, T1))))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldReturnWarning_whenOrchestratorWarning() {
        assertThat(mapper.deriveStatus("warning", tests(test("success", false, T1))))
            .isEqualTo(IntegrationStatus.WARNING);
    }

    @Test
    void deriveStatus_shouldReturnDisabled_whenOrchestratorStopped() {
        assertThat(mapper.deriveStatus("stopped", tests(test("success", false, T1))))
            .isEqualTo(IntegrationStatus.DISABLED);
    }

    // ---- deriveStatus: precedence + no-test + edge cases ----

    @Test
    void deriveStatus_shouldReturnError_whenTestFailedAndOrchestratorWarning() {
        // Precedence: error > warning
        assertThat(mapper.deriveStatus("warning", tests(test("failed", false, T1))))
            .isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldSelectMostRecentTest_whenMultiplePresent() {
        // The latest test (T3, failed) drives status; an earlier success does not.
        List<ConnectionTest> multi = List.of(
            test("success", false, T1),
            test("failed", false, T3),
            test("success", false, T2));
        assertThat(mapper.deriveStatus("healthy", multi)).isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorHealthyAndNoTest() {
        // No confirming healthy test => cannot confirm it works
        assertThat(mapper.deriveStatus("healthy", null))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
        assertThat(mapper.deriveStatus("healthy", List.of()))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorHealthyAndTestPending() {
        assertThat(mapper.deriveStatus("healthy", tests(test("pending", false, T1))))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldTreatNullIsStaleAsNotStale_whenHealthySuccess() {
        // null isStale must not block healthy
        assertThat(mapper.deriveStatus("healthy", tests(test("success", null, T1))))
            .isEqualTo(IntegrationStatus.HEALTHY);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorUnrecognized() {
        // Forward-compat: a 6th orchestrator value falls back to missing_data
        assertThat(mapper.deriveStatus("decommissioning", tests(test("success", false, T1))))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorNull() {
        assertThat(mapper.deriveStatus(null, tests(test("success", false, T1))))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    // ---- case-insensitivity (defensive normalization) ----

    @Test
    void deriveStatus_shouldReturnHealthy_whenOrchestratorAndTestStatusAreUppercase() {
        // ICON documents lowercase enums; a casing change upstream must not flip
        // a healthy connection to missing_data.
        assertThat(mapper.deriveStatus("HEALTHY", tests(test("SUCCESS", false, T1))))
            .isEqualTo(IntegrationStatus.HEALTHY);
    }

    @Test
    void deriveStatus_shouldReturnError_whenTestStatusIsMixedCaseFailed() {
        assertThat(mapper.deriveStatus("Healthy", tests(test("Failed", false, T1))))
            .isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveLastSuccess_shouldMatchSuccessCaseInsensitively() {
        // Arrange
        List<ConnectionTest> tests = List.of(test("SUCCESS", false, T2));
        // Act / Assert
        assertThat(mapper.deriveLastSuccess(tests)).isEqualTo(T2);
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
        ConnectionTest latest = ConnectionStatusMapper.mostRecentByCreatedAt(tests);
        // Assert
        assertThat(latest.createdAt()).isEqualTo(T3);
    }

    @Test
    void mostRecentByCreatedAt_shouldReturnNull_whenEmpty() {
        assertThat(ConnectionStatusMapper.mostRecentByCreatedAt(List.of())).isNull();
    }

    @Test
    void mostRecentByCreatedAt_shouldReturnNull_whenNull() {
        assertThat(ConnectionStatusMapper.mostRecentByCreatedAt(null)).isNull();
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
