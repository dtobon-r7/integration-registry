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
        // First match wins, in precedence order: error > missing_data > warning > disabled > healthy.
        if (isErrorState(orchestratorStatus, mostRecentTest)) {
            return IntegrationStatus.ERROR;
        }
        if (isMissingDataState(orchestratorStatus, mostRecentTest)) {
            return IntegrationStatus.MISSING_DATA;
        }
        if (ORCH_WARNING.equals(orchestratorStatus)) {
            return IntegrationStatus.WARNING;
        }
        if (ORCH_STOPPED.equals(orchestratorStatus)) {
            return IntegrationStatus.DISABLED;
        }
        if (isHealthyState(orchestratorStatus, mostRecentTest)) {
            return IntegrationStatus.HEALTHY;
        }
        // Tail: healthy orchestrator with no confirming test, or any unrecognized /
        // null orchestrator value. Only the latter is genuinely anomalous, so only
        // it is logged.
        if (!ORCH_HEALTHY.equals(orchestratorStatus)) {
            log.warn("Unrecognized ICON orchestrator status '{}'; mapping to missing_data",
                     orchestratorStatus);
        }
        return IntegrationStatus.MISSING_DATA;
    }

    /** A failed/timeout test or an {@code error} orchestrator. */
    private static boolean isErrorState(String orchestratorStatus, ConnectionTest test) {
        String testStatus = test == null ? null : test.status();
        return TEST_FAILED.equals(testStatus)
            || TEST_TIMEOUT.equals(testStatus)
            || ORCH_ERROR.equals(orchestratorStatus);
    }

    /** A stale test or an {@code unknown} orchestrator — the two RFC {@code missing_data} triggers. */
    private static boolean isMissingDataState(String orchestratorStatus, ConnectionTest test) {
        boolean stale = test != null && Boolean.TRUE.equals(test.isStale());
        return stale || ORCH_UNKNOWN.equals(orchestratorStatus);
    }

    /**
     * A {@code healthy} orchestrator confirmed by a successful test. Staleness is
     * not re-checked here — a stale test resolves to {@code missing_data} earlier
     * via {@link #isMissingDataState}, so it never reaches this rule.
     */
    private static boolean isHealthyState(String orchestratorStatus, ConnectionTest test) {
        return ORCH_HEALTHY.equals(orchestratorStatus)
            && test != null
            && TEST_SUCCESS.equals(test.status());
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
