package com.rapid7.integrationregistry.adapter.insightconnect;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Pure health-derivation logic for ICON connections (RFC-001
 * §InsightConnectAdapter). No Spring, no HTTP, no clock — unit-testable in
 * isolation. Owns the five-state status table, the instance-level precedence
 * ({@code error > missing_data > warning > disabled > healthy}), most-recent
 * {@link ConnectionTest} selection, and {@code last_success_timestamp}
 * derivation.
 */
public class ConnectionStatusMapper {

    private static final Logger log = LoggerFactory.getLogger(ConnectionStatusMapper.class);

    private static final String ORCH_HEALTHY = "healthy";
    private static final String ORCH_ERROR = "error";
    private static final String ORCH_WARNING = "warning";
    private static final String ORCH_STOPPED = "stopped";
    private static final String ORCH_UNKNOWN = "unknown";

    private static final String TEST_SUCCESS = "success";
    private static final String TEST_FAILED = "failed";
    private static final String TEST_TIMEOUT = "timeout";

    /**
     * Derive the instance status from the orchestrator status and the most
     * recent connection test (which may be {@code null} when no tests exist).
     * First match wins, in precedence order. An unrecognized or {@code null}
     * orchestrator value falls back to {@link IntegrationStatus#MISSING_DATA}
     * with a WARN log — one anomalous connection must not fail the fetch.
     */
    public IntegrationStatus deriveStatus(String orchestratorStatus, ConnectionTest mostRecentTest) {
        boolean stale = mostRecentTest != null && Boolean.TRUE.equals(mostRecentTest.isStale());
        String testStatus = mostRecentTest == null ? null : mostRecentTest.status();

        // 1. error
        if (TEST_FAILED.equals(testStatus) || TEST_TIMEOUT.equals(testStatus)
                || ORCH_ERROR.equals(orchestratorStatus)) {
            return IntegrationStatus.ERROR;
        }
        // 2. missing_data (stale test or unknown orchestrator)
        if (stale || ORCH_UNKNOWN.equals(orchestratorStatus)) {
            return IntegrationStatus.MISSING_DATA;
        }
        // 3. warning
        if (ORCH_WARNING.equals(orchestratorStatus)) {
            return IntegrationStatus.WARNING;
        }
        // 4. disabled
        if (ORCH_STOPPED.equals(orchestratorStatus)) {
            return IntegrationStatus.DISABLED;
        }
        // 5. healthy (requires a confirming successful, non-stale test)
        if (ORCH_HEALTHY.equals(orchestratorStatus)
                && TEST_SUCCESS.equals(testStatus)) {
            return IntegrationStatus.HEALTHY;
        }
        // 6. missing_data tail: healthy orchestrator with no confirming test,
        //    or any unrecognized / null orchestrator value.
        if (!ORCH_HEALTHY.equals(orchestratorStatus)) {
            log.warn("Unrecognized ICON orchestrator status '{}'; mapping to missing_data",
                     orchestratorStatus);
        }
        return IntegrationStatus.MISSING_DATA;
    }

    /**
     * The most recent test by {@code createdAt}, or {@code null} when the list
     * is null/empty. Tests with a null {@code createdAt} sort earliest.
     */
    public ConnectionTest mostRecentByCreatedAt(List<ConnectionTest> tests) {
        if (tests == null || tests.isEmpty()) {
            return null;
        }
        return tests.stream()
            .max(Comparator.comparing(ConnectionTest::createdAt,
                     Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(null);
    }

    /**
     * Timestamp of the most recent successful test, or {@code null} when none
     * succeeded (or the list is null/empty). Independent of
     * {@link #mostRecentByCreatedAt} — a connection whose latest test failed
     * still reports its last success.
     */
    public Instant deriveLastSuccess(List<ConnectionTest> tests) {
        if (tests == null || tests.isEmpty()) {
            return null;
        }
        return tests.stream()
            .filter(t -> TEST_SUCCESS.equals(t.status()) && t.createdAt() != null)
            .map(ConnectionTest::createdAt)
            .max(Comparator.naturalOrder())
            .orElse(null);
    }
}
