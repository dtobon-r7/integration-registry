package com.rapid7.integrationregistry.aggregator;

import static com.rapid7.integrationregistry.aggregator.NormalizedIntegrationFixtures.idrInstance;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class VendorAggregatorTest {

  static final String MAPPING_VERSION = "v1.42.0";

  static final VendorResolution MS_DEFENDER =
      new VendorResolution(
          "microsoft-defender", "Microsoft Defender", VendorCategory.EDR, "microsoft", "Microsoft");

  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachAppender() {
    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(VendorAggregator.class)).addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    ((Logger) LoggerFactory.getLogger(VendorAggregator.class)).detachAppender(appender);
  }

  private VendorAggregator aggregatorWith(VendorMappingSnapshot snapshot) {
    return new VendorAggregator(snapshot);
  }

  @Nested
  class ResolutionTest {

    @Test
    void toVendorServiceCards_shouldEmitOneCard_forSingleKnownTriplet() {
      // Arrange — one IDR instance for Microsoft Defender. The snapshot has
      // exactly the triplet mapped.
      VendorMappingSnapshot snapshot =
          FakeVendorMappingSnapshot.with(MAPPING_VERSION)
              .map(
                  ProductName.INSIGHT_IDR,
                  SourceType.PRODUCT_TYPE,
                  "microsoft-defender-endpoint",
                  MS_DEFENDER)
              .build();
      NormalizedIntegration instance =
          idrInstance("es_1", "microsoft-defender-endpoint", IntegrationStatus.HEALTHY);

      // Act
      List<VendorServiceCard> cards =
          aggregatorWith(snapshot).toVendorServiceCards(List.of(instance));

      // Assert — single card carrying the resolved identity, one instance, no WARN
      assertThat(cards).hasSize(1);
      VendorServiceCard card = cards.get(0);
      assertThat(card.vendorServiceId()).isEqualTo("microsoft-defender");
      assertThat(card.vendorServiceName()).isEqualTo("Microsoft Defender");
      assertThat(card.vendorId()).isEqualTo("microsoft");
      assertThat(card.vendorName()).isEqualTo("Microsoft");
      assertThat(card.vendorCategory()).isEqualTo(VendorCategory.EDR);
      assertThat(card.integrationsConnected()).isEqualTo(1);
      assertThat(card.aggregateHealth()).isEqualTo(IntegrationStatus.HEALTHY);
      assertThat(appender.list).isEmpty();
    }

    @Test
    void toVendorServiceCards_shouldEmitSyntheticCard_whenSnapshotReturnsUnknown() {
      // Arrange — one ICON instance, snapshot has nothing mapped at all
      VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
      NormalizedIntegration instance =
          NormalizedIntegrationFixtures.iconInstance(
              "c_1", "new-product-x", IntegrationStatus.HEALTHY);

      // Act
      List<VendorServiceCard> cards =
          aggregatorWith(snapshot).toVendorServiceCards(List.of(instance));

      // Assert — synthetic VS card with the canonical unknown identity
      assertThat(cards).hasSize(1);
      VendorServiceCard card = cards.get(0);
      assertThat(card.vendorServiceId()).isEqualTo("unknown");
      assertThat(card.vendorServiceName()).isEqualTo("Unknown");
      assertThat(card.vendorId()).isEqualTo("unknown");
      assertThat(card.vendorName()).isEqualTo("Unknown");
      assertThat(card.vendorCategory()).isEqualTo(VendorCategory.OTHER);
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.get(0).getLevel().toString()).isEqualTo("WARN");
      assertThat(appender.list.get(0).getFormattedMessage())
          .contains("InsightConnect")
          .contains("plugin_name")
          .contains("new-product-x")
          .contains(MAPPING_VERSION);
    }

    @Test
    void toVendorServiceCards_shouldRouteToUnknownPath_whenProductNameStringIsUnmappable() {
      // Arrange — adapter wrote a productName string that isn't a ProductName enum value.
      // Spec ruling Q1.1: fold into unknown-collapse path with WARN.
      VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
      NormalizedIntegration instance =
          NormalizedIntegrationFixtures.instance(
              "MysteryProduct",
              "product_type",
              "weird-source",
              "Unknown Type",
              IntegrationStatus.WARNING,
              "i_1",
              null);

      // Act
      List<VendorServiceCard> cards =
          aggregatorWith(snapshot).toVendorServiceCards(List.of(instance));

      // Assert — synthetic card; WARN content carries the raw values
      assertThat(cards).hasSize(1);
      assertThat(cards.get(0).vendorServiceId()).isEqualTo("unknown");
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.get(0).getFormattedMessage())
          .contains("MysteryProduct")
          .contains("product_type")
          .contains("weird-source")
          .contains(MAPPING_VERSION);
    }

    @Test
    void toVendorServiceCards_shouldRouteToUnknownPath_whenSourceTypeStringIsUnmappable() {
      // Arrange — productName is canonical, sourceType is junk.
      VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
      NormalizedIntegration instance =
          NormalizedIntegrationFixtures.instance(
              "InsightIDR",
              "future_source_type",
              "x",
              "SIEM Event Source",
              IntegrationStatus.HEALTHY,
              "es_1",
              null);

      // Act
      List<VendorServiceCard> cards =
          aggregatorWith(snapshot).toVendorServiceCards(List.of(instance));

      // Assert — synthetic, single WARN with the raw sourceType in the message
      assertThat(cards).hasSize(1);
      assertThat(cards.get(0).vendorServiceId()).isEqualTo("unknown");
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.get(0).getFormattedMessage())
          .contains("InsightIDR")
          .contains("future_source_type")
          .contains(MAPPING_VERSION);
    }
  }
}
