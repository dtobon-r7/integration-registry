package com.rapid7.integrationregistry.adapter.insightidr;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure health-derivation logic for IDR event sources (RFC-001 §InsightIDRAdapter). No Spring, no
 * HTTP, no clock — the reference instant is injected so fixtures are deterministic. Owns the
 * status-string table, the instance-level precedence ({@code error > missing_data > warning >
 * disabled > healthy}), the null/stale {@code lastActive} rules, and {@code last_success_timestamp}
 * conversion.
 *
 * <p>Status strings are matched case-insensitively against known vocabularies. An unrecognized
 * status maps to {@link IntegrationStatus#MISSING_DATA} with a WARN — one anomalous source must not
 * fail the fetch, and silently calling it healthy would hide a real problem.
 */
public class EventSourceStatusMapper {

  private static final Logger log = LoggerFactory.getLogger(EventSourceStatusMapper.class);

  private static final Set<String> ERROR_STATUSES = Set.of("error", "failed", "fault", "errored");
  private static final Set<String> DISABLED_STATUSES =
      Set.of("paused", "disabled", "inactive", "stopped");
  private static final Set<String> HEALTHY_STATUSES =
      Set.of("active", "running", "healthy", "ok", "enabled");
  private static final Set<String> FATAL_SEVERITIES =
      Set.of("error", "critical", "fatal", "high", "severe");

  /**
   * Derive the instance status. {@code now} and {@code stalenessThreshold} are injected; {@code
   * lastActive} is epoch milliseconds or null. First match wins, in precedence order.
   */
  public IntegrationStatus deriveStatus(
      String status,
      EventSourceIssueDto issue,
      Long lastActive,
      Instant now,
      Duration stalenessThreshold) {
    String normalized = normalize(status);

    // error: status indicates failure, OR a present issue carries a fatal severity.
    if (ERROR_STATUSES.contains(normalized) || isFatalIssue(issue)) {
      return IntegrationStatus.ERROR;
    }
    // missing_data: no successful activity ever recorded (null always wins over the threshold).
    if (lastActive == null) {
      return IntegrationStatus.MISSING_DATA;
    }
    // warning: a present, non-fatal issue.
    if (issue != null) {
      return IntegrationStatus.WARNING;
    }
    // disabled: intentionally inactive.
    if (DISABLED_STATUSES.contains(normalized)) {
      return IntegrationStatus.DISABLED;
    }
    // missing_data: configured but stale beyond the threshold.
    if (isStale(lastActive, now, stalenessThreshold)) {
      return IntegrationStatus.MISSING_DATA;
    }
    // healthy: operational status AND fresh activity.
    if (HEALTHY_STATUSES.contains(normalized)) {
      return IntegrationStatus.HEALTHY;
    }
    // tail: unrecognized status with fresh data and no issue — anomalous, surface as missing_data.
    log.warn("Unrecognized InsightIDR event-source status '{}'; mapping to missing_data", status);
    return IntegrationStatus.MISSING_DATA;
  }

  /** Epoch-millis {@code lastActive} as an {@link Instant}, or null when absent. */
  public Instant deriveLastSuccess(Long lastActive) {
    return lastActive == null ? null : Instant.ofEpochMilli(lastActive);
  }

  private static boolean isFatalIssue(EventSourceIssueDto issue) {
    return issue != null && FATAL_SEVERITIES.contains(normalize(issue.severity()));
  }

  private static boolean isStale(long lastActive, Instant now, Duration threshold) {
    Instant last = Instant.ofEpochMilli(lastActive);
    return last.isBefore(now.minus(threshold));
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
  }
}
