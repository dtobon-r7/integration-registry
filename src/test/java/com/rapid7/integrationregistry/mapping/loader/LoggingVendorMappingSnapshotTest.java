package com.rapid7.integrationregistry.mapping.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rapid7.integrationregistry.mapping.DataSourceResolution;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import com.rapid7.integrationregistry.testsupport.StubVendorMappingSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingVendorMappingSnapshotTest {

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void attachAppender() {
    logger = (Logger) LoggerFactory.getLogger(LoggingVendorMappingSnapshot.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
  }

  @Test
  void constructor_shouldThrowNpe_whenDelegateNull() {
    // Arrange / Act / Assert
    assertThatNullPointerException()
        .isThrownBy(() -> new LoggingVendorMappingSnapshot(null))
        .withMessage("delegate");
  }

  @Test
  void lookup_shouldLogWarn_whenUnderlyingReturnsUnknown() {
    // Arrange
    LoggingVendorMappingSnapshot decorator =
        new LoggingVendorMappingSnapshot(
            StubVendorMappingSnapshot.returning("v1.0.0", DataSourceResolution.unknown()));

    // Act
    DataSourceResolution result =
        decorator.lookup(ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "mystery-source");

    // Assert
    assertThat(result).isSameAs(DataSourceResolution.unknown());
    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    String formatted = event.getFormattedMessage();
    assertThat(formatted)
        .contains("Unknown vendor mapping triplet")
        .contains("INSIGHT_IDR")
        .contains("PRODUCT_TYPE")
        .contains("mystery-source")
        .contains("v1.0.0");
  }

  @Test
  void lookup_shouldNotLog_whenUnderlyingReturnsKnown() {
    // Arrange
    VendorResolution known =
        new VendorResolution(
            "microsoft-defender",
            "Microsoft Defender",
            VendorCategory.EDR,
            "microsoft",
            "Microsoft");
    DataSourceResolution knownDsr = new DataSourceResolution(known, "Microsoft Defender");
    LoggingVendorMappingSnapshot decorator =
        new LoggingVendorMappingSnapshot(StubVendorMappingSnapshot.returning("v1.0.0", knownDsr));

    // Act
    DataSourceResolution result =
        decorator.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

    // Assert
    assertThat(result).isSameAs(knownDsr);
    assertThat(appender.list).isEmpty();
  }

  @Test
  void mappingVersion_shouldDelegate_always() {
    // Arrange
    LoggingVendorMappingSnapshot decorator =
        new LoggingVendorMappingSnapshot(
            StubVendorMappingSnapshot.returning("v1.42.0", DataSourceResolution.unknown()));

    // Act
    String version = decorator.mappingVersion();

    // Assert
    assertThat(version).isEqualTo("v1.42.0");
    assertThat(appender.list).isEmpty(); // mappingVersion() never logs
  }
}
