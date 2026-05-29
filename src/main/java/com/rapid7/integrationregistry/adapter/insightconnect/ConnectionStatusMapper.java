package com.rapid7.integrationregistry.adapter.insightconnect;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
     * Derive the instance status from the orchestrator status and the connection's
     * tests. The most recent test by {@code createdAt} drives the test-derived
     * signals; {@code tests} may be null/empty when no tests exist. First match
     * wins, in precedence order. An unrecognized or {@code null} orchestrator value
     * falls back to {@link IntegrationStatus#MISSING_DATA} with a WARN log — one
     * anomalous connection must not fail the fetch.
     *
     * <p>Selecting the most-recent test is done here rather than by the caller, so
     * there is no two-step protocol a caller could get wrong by passing a
     * non-most-recent test.
     */
    public IntegrationStatus deriveStatus(String orchestratorStatus, List<ConnectionTest> tests) {
        ConnectionTest mostRecentTest = mostRecentByCreatedAt(tests);
        // ICON's status enums are documented lowercase, but we normalize defensively:
        // a casing change upstream must not silently flip every healthy connection to
        // missing_data. Normalize once here; the helpers compare against lowercase literals.
        String orch = normalize(orchestratorStatus);
        String testStatus = mostRecentTest == null ? null : normalize(mostRecentTest.status());
        boolean stale = mostRecentTest != null && Boolean.TRUE.equals(mostRecentTest.isStale());

        // First match wins, in precedence order: error > missing_data > warning > disabled > healthy.
        // The two highest-precedence rules combine test and orchestrator signals; the
        // remaining rules are orchestrator-only and are resolved in a helper to keep this
        // method's branch count low.
        if (isErrorState(orch, testStatus)) {
            return IntegrationStatus.ERROR;
        }
        if (stale || ORCH_UNKNOWN.equals(orch)) {
            return IntegrationStatus.MISSING_DATA;
        }
        return deriveFromOrchestrator(orch, testStatus, orchestratorStatus);
    }

    /**
     * Resolve the warning / disabled / healthy / missing_data-tail rules, which
     * depend on the orchestrator status (plus a confirming test for healthy).
     * {@code orch} / {@code testStatus} are normalized; {@code rawOrch} is the
     * original value, logged verbatim when unrecognized.
     */
    private IntegrationStatus deriveFromOrchestrator(String orch, String testStatus, String rawOrch) {
        if (ORCH_WARNING.equals(orch)) {
            return IntegrationStatus.WARNING;
        }
        if (ORCH_STOPPED.equals(orch)) {
            return IntegrationStatus.DISABLED;
        }
        // Healthy requires a confirming successful test. Staleness is not re-checked —
        // a stale test already resolved to missing_data in deriveStatus, so it never reaches here.
        if (ORCH_HEALTHY.equals(orch) && TEST_SUCCESS.equals(testStatus)) {
            return IntegrationStatus.HEALTHY;
        }
        // Tail: healthy orchestrator with no confirming test, or any unrecognized /
        // null orchestrator value. Only the latter is genuinely anomalous, so only
        // it is logged (with the original, un-normalized value for fidelity).
        if (!ORCH_HEALTHY.equals(orch)) {
            log.warn("Unrecognized ICON orchestrator status '{}'; mapping to missing_data", rawOrch);
        }
        return IntegrationStatus.MISSING_DATA;
    }

    /** A failed/timeout test or an {@code error} orchestrator. Inputs already normalized. */
    private static boolean isErrorState(String orchestratorStatus, String testStatus) {
        return TEST_FAILED.equals(testStatus)
            || TEST_TIMEOUT.equals(testStatus)
            || ORCH_ERROR.equals(orchestratorStatus);
    }

    /** Lowercase a status string for case-insensitive comparison; {@code null} stays {@code null}. */
    private static String normalize(String status) {
        return status == null ? null : status.toLowerCase(Locale.ROOT);
    }

    /**
     * The most recent test by {@code createdAt}, or {@code null} when the list
     * is null/empty. Tests with a null {@code createdAt} sort earliest.
     * Package-private: used internally by {@link #deriveStatus} and exercised
     * directly by the unit tests; not part of the public mapper surface.
     */
    static ConnectionTest mostRecentByCreatedAt(List<ConnectionTest> tests) {
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
            .filter(t -> TEST_SUCCESS.equals(normalize(t.status())) && t.createdAt() != null)
            .map(ConnectionTest::createdAt)
            .max(Comparator.naturalOrder())
            .orElse(null);
    }
}
