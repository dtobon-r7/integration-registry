package com.rapid7.integrationregistry.adapter.insightidr;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class EventSourceStatusMapperTest {

  private static final Instant NOW = Instant.parse("2026-06-25T12:00:00Z");
  private static final Duration THRESHOLD = Duration.ofHours(24);
  private final EventSourceStatusMapper mapper = new EventSourceStatusMapper();

  private static long epochMillis(String iso) {
    return Instant.parse(iso).toEpochMilli();
  }

  @Test
  void deriveStatus_shouldReturnMissingData_whenLastActiveNull() {
    assertThat(mapper.deriveStatus("Active", null, null, NOW, THRESHOLD))
        .isEqualTo(IntegrationStatus.MISSING_DATA);
  }

  @Test
  void deriveStatus_shouldReturnHealthy_whenActiveAndFresh() {
    long fresh = epochMillis("2026-06-25T06:00:00Z"); // 6h ago
    assertThat(mapper.deriveStatus("Active", null, fresh, NOW, THRESHOLD))
        .isEqualTo(IntegrationStatus.HEALTHY);
  }

  @Test
  void deriveStatus_shouldReturnMissingData_whenActiveButStale() {
    long stale = epochMillis("2026-06-23T06:00:00Z"); // ~54h ago
    assertThat(mapper.deriveStatus("Active", null, stale, NOW, THRESHOLD))
        .isEqualTo(IntegrationStatus.MISSING_DATA);
  }

  @Test
  void deriveStatus_shouldReturnError_whenStatusIndicatesFailure() {
    long fresh = epochMillis("2026-06-25T06:00:00Z");
    assertThat(mapper.deriveStatus("Error", null, fresh, NOW, THRESHOLD))
        .isEqualTo(IntegrationStatus.ERROR);
  }

  @Test
  void deriveStatus_shouldReturnError_whenIssueSeverityFatal() {
    long fresh = epochMillis("2026-06-25T06:00:00Z");
    EventSourceIssueDto issue = new EventSourceIssueDto("CRITICAL", "boom", null);
    assertThat(mapper.deriveStatus("Active", issue, fresh, NOW, THRESHOLD))
        .isEqualTo(IntegrationStatus.ERROR);
  }

  @Test
  void deriveStatus_shouldReturnWarning_whenIssueNonFatal() {
    long fresh = epochMillis("2026-06-25T06:00:00Z");
    EventSourceIssueDto issue = new EventSourceIssueDto("WARNING", "slow", null);
    assertThat(mapper.deriveStatus("Active", issue, fresh, NOW, THRESHOLD))
        .isEqualTo(IntegrationStatus.WARNING);
  }

  @Test
  void deriveStatus_shouldReturnDisabled_whenStatusPaused() {
    long fresh = epochMillis("2026-06-25T06:00:00Z");
    assertThat(mapper.deriveStatus("Paused", null, fresh, NOW, THRESHOLD))
        .isEqualTo(IntegrationStatus.DISABLED);
  }

  @Test
  void deriveStatus_shouldBeCaseInsensitive_whenStatusUpperCase() {
    long fresh = epochMillis("2026-06-25T06:00:00Z");
    assertThat(mapper.deriveStatus("ACTIVE", null, fresh, NOW, THRESHOLD))
        .isEqualTo(IntegrationStatus.HEALTHY);
  }

  @Test
  void deriveStatus_shouldReturnMissingDataAndWarn_whenStatusUnrecognized() {
    Logger logger = (Logger) LoggerFactory.getLogger(EventSourceStatusMapper.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      long fresh = epochMillis("2026-06-25T06:00:00Z");
      IntegrationStatus result = mapper.deriveStatus("Klingon", null, fresh, NOW, THRESHOLD);
      assertThat(result).isEqualTo(IntegrationStatus.MISSING_DATA);
      assertThat(appender.list)
          .filteredOn(e -> e.getLevel() == Level.WARN)
          .extracting(ILoggingEvent::getFormattedMessage)
          .anySatisfy(m -> assertThat(m).contains("Klingon"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void deriveLastSuccess_shouldConvertEpochMillis_whenNonNull() {
    long ts = epochMillis("2026-05-19T10:00:00Z");
    assertThat(mapper.deriveLastSuccess(ts)).isEqualTo(Instant.parse("2026-05-19T10:00:00Z"));
  }

  @Test
  void deriveLastSuccess_shouldReturnNull_whenNull() {
    assertThat(mapper.deriveLastSuccess(null)).isNull();
  }

  @Test
  void deriveStatus_shouldPreferErrorOverDisabled_whenStatusErrorButPausedWordAbsent() {
    // precedence guard: an error status with a stale timestamp still resolves error (error >
    // missing_data)
    long stale = epochMillis("2026-06-23T06:00:00Z");
    assertThat(mapper.deriveStatus("Error", null, stale, NOW, THRESHOLD))
        .isEqualTo(IntegrationStatus.ERROR);
  }
}
