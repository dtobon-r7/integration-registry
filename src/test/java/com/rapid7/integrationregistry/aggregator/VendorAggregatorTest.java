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
import java.time.Instant;
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

  @Nested
  class DataSourceRollupTest {

    @Test
    void toVendorServiceCards_shouldRollUpInstanceStates_atDataSourceLevel() {
      // Arrange — three IDR instances under one data source, mixed states.
      // Worst-state: error > missing_data > warning > disabled > healthy.
      // Expected DS status: ERROR.
      VendorMappingSnapshot snapshot =
          FakeVendorMappingSnapshot.with(MAPPING_VERSION)
              .map(
                  ProductName.INSIGHT_IDR,
                  SourceType.PRODUCT_TYPE,
                  "microsoft-defender-endpoint",
                  MS_DEFENDER)
              .build();

      List<NormalizedIntegration> instances =
          List.of(
              NormalizedIntegrationFixtures.idrInstance(
                  "es_1", "microsoft-defender-endpoint", IntegrationStatus.HEALTHY),
              NormalizedIntegrationFixtures.idrInstance(
                  "es_2", "microsoft-defender-endpoint", IntegrationStatus.WARNING),
              NormalizedIntegrationFixtures.idrInstance(
                  "es_3", "microsoft-defender-endpoint", IntegrationStatus.ERROR));

      // Act
      List<VendorServiceCard> cards = aggregatorWith(snapshot).toVendorServiceCards(instances);

      // Assert — single VS card, single DS, aggregate is ERROR rolled across instances
      assertThat(cards).hasSize(1);
      assertThat(cards.get(0).aggregateHealth()).isEqualTo(IntegrationStatus.ERROR);
    }
  }

  @Nested
  class VendorServiceRollupTest {

    @Test
    void toVendorServiceCards_shouldRollUpDataSourceStates_atVendorServiceLevel() {
      // Arrange — Microsoft Defender exposed via two products. Two distinct data sources.
      // DS1 (IDR) = HEALTHY; DS2 (ICON) = WARNING. VS rollup = WARNING.
      VendorMappingSnapshot snapshot =
          FakeVendorMappingSnapshot.with(MAPPING_VERSION)
              .map(
                  ProductName.INSIGHT_IDR,
                  SourceType.PRODUCT_TYPE,
                  "microsoft-defender-endpoint",
                  MS_DEFENDER)
              .map(
                  ProductName.INSIGHT_CONNECT,
                  SourceType.PLUGIN_NAME,
                  "microsoft-defender",
                  MS_DEFENDER)
              .build();

      List<NormalizedIntegration> instances =
          List.of(
              NormalizedIntegrationFixtures.idrInstance(
                  "es_1", "microsoft-defender-endpoint", IntegrationStatus.HEALTHY),
              NormalizedIntegrationFixtures.iconInstance(
                  "c_1", "microsoft-defender", IntegrationStatus.WARNING));

      // Act
      List<VendorServiceCard> cards = aggregatorWith(snapshot).toVendorServiceCards(instances);

      // Assert
      assertThat(cards).hasSize(1);
      assertThat(cards.get(0).aggregateHealth()).isEqualTo(IntegrationStatus.WARNING);
      assertThat(cards.get(0).integrationsConnected()).isEqualTo(2);
    }
  }

  @Nested
  class VendorServiceCardsTest {

    private static final Instant T1 = Instant.parse("2026-05-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-15T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-05-29T10:00:00Z");

    @Test
    void toVendorServiceCards_shouldComputePerVendorServiceAggregates() {
      // Arrange — Microsoft Defender via IDR (2 instances) + ICON (1 instance).
      // Two distinct integrationTypes; one ERROR; mixed lastSuccess timestamps.
      VendorMappingSnapshot snapshot =
          FakeVendorMappingSnapshot.with(MAPPING_VERSION)
              .map(
                  ProductName.INSIGHT_IDR,
                  SourceType.PRODUCT_TYPE,
                  "microsoft-defender-endpoint",
                  MS_DEFENDER)
              .map(
                  ProductName.INSIGHT_CONNECT,
                  SourceType.PLUGIN_NAME,
                  "microsoft-defender",
                  MS_DEFENDER)
              .build();

      List<NormalizedIntegration> instances =
          List.of(
              NormalizedIntegrationFixtures.idrInstance(
                  "es_1", "microsoft-defender-endpoint", IntegrationStatus.HEALTHY, T1),
              NormalizedIntegrationFixtures.idrInstance(
                  "es_2", "microsoft-defender-endpoint", IntegrationStatus.ERROR, T2),
              NormalizedIntegrationFixtures.iconInstance(
                  "c_1", "microsoft-defender", IntegrationStatus.HEALTHY, T3));

      // Act
      List<VendorServiceCard> cards = aggregatorWith(snapshot).toVendorServiceCards(instances);

      // Assert
      assertThat(cards).hasSize(1);
      VendorServiceCard card = cards.get(0);
      assertThat(card.integrationsConnected()).isEqualTo(3);
      assertThat(card.lastUpdated()).isEqualTo(T3);
      assertThat(card.productsConnected()).containsExactly("InsightIDR", "InsightConnect");
      assertThat(card.integrationTypeCounts())
          .containsExactlyInAnyOrder(
              new IntegrationTypeCount("SIEM Event Source", 2, 1),
              new IntegrationTypeCount("Automation Plugin", 1, 0));
    }

    @Test
    void toVendorServiceCards_shouldReturnNullLastUpdated_whenNoInstanceHasTimestamp() {
      // Arrange — single VS, every instance has null lastSuccessTimestamp
      VendorMappingSnapshot snapshot =
          FakeVendorMappingSnapshot.with(MAPPING_VERSION)
              .map(
                  ProductName.INSIGHT_IDR,
                  SourceType.PRODUCT_TYPE,
                  "microsoft-defender-endpoint",
                  MS_DEFENDER)
              .build();

      List<NormalizedIntegration> instances =
          List.of(
              NormalizedIntegrationFixtures.idrInstance(
                  "es_1", "microsoft-defender-endpoint", IntegrationStatus.HEALTHY, null),
              NormalizedIntegrationFixtures.idrInstance(
                  "es_2", "microsoft-defender-endpoint", IntegrationStatus.HEALTHY, null));

      // Act
      List<VendorServiceCard> cards = aggregatorWith(snapshot).toVendorServiceCards(instances);

      // Assert
      assertThat(cards.get(0).lastUpdated()).isNull();
    }

    @Test
    void toVendorServiceCards_shouldMergeMultipleProducts_intoOneVendorServiceCard() {
      // Arrange — Microsoft Defender via three products: IDR, ICON, and a hypothetical
      // unknown product instance. The first two map to the same vendor_service_id;
      // the third is unknown.
      VendorMappingSnapshot snapshot =
          FakeVendorMappingSnapshot.with(MAPPING_VERSION)
              .map(
                  ProductName.INSIGHT_IDR,
                  SourceType.PRODUCT_TYPE,
                  "microsoft-defender-endpoint",
                  MS_DEFENDER)
              .map(
                  ProductName.INSIGHT_CONNECT,
                  SourceType.PLUGIN_NAME,
                  "microsoft-defender",
                  MS_DEFENDER)
              .build();

      List<NormalizedIntegration> instances =
          List.of(
              NormalizedIntegrationFixtures.idrInstance(
                  "es_1", "microsoft-defender-endpoint", IntegrationStatus.HEALTHY),
              NormalizedIntegrationFixtures.iconInstance(
                  "c_1", "microsoft-defender", IntegrationStatus.HEALTHY));

      // Act
      List<VendorServiceCard> cards = aggregatorWith(snapshot).toVendorServiceCards(instances);

      // Assert — exactly ONE Microsoft Defender card
      assertThat(cards).hasSize(1);
      VendorServiceCard card = cards.get(0);
      assertThat(card.vendorServiceId()).isEqualTo("microsoft-defender");
      assertThat(card.integrationsConnected()).isEqualTo(2);
      assertThat(card.productsConnected())
          .containsExactlyInAnyOrder("InsightIDR", "InsightConnect");
    }
  }

  @Nested
  class VendorCardsTest {

    @Test
    void toVendorCards_shouldEmitOneCardPerDistinctVendor_withVendorServicesCount() {
      // Arrange — two Microsoft services (Defender + Sentinel) plus one Atlassian (Jira).
      // Microsoft has 2 vendor services; Atlassian has 1.
      VendorResolution msSentinel =
          new VendorResolution(
              "microsoft-sentinel",
              "Microsoft Sentinel",
              VendorCategory.SIEM,
              "microsoft",
              "Microsoft");
      VendorResolution jira =
          new VendorResolution("jira", "Jira", VendorCategory.ITSM, "atlassian", "Atlassian");

      VendorMappingSnapshot snapshot =
          FakeVendorMappingSnapshot.with(MAPPING_VERSION)
              .map(
                  ProductName.INSIGHT_IDR,
                  SourceType.PRODUCT_TYPE,
                  "microsoft-defender-endpoint",
                  MS_DEFENDER)
              .map(
                  ProductName.INSIGHT_IDR,
                  SourceType.PRODUCT_TYPE,
                  "microsoft-sentinel",
                  msSentinel)
              .map(ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "jira", jira)
              .build();

      List<NormalizedIntegration> instances =
          List.of(
              NormalizedIntegrationFixtures.idrInstance(
                  "es_1", "microsoft-defender-endpoint", IntegrationStatus.HEALTHY),
              NormalizedIntegrationFixtures.idrInstance(
                  "es_2", "microsoft-sentinel", IntegrationStatus.HEALTHY),
              NormalizedIntegrationFixtures.iconInstance("c_1", "jira", IntegrationStatus.HEALTHY));

      // Act
      List<VendorCard> vendors = aggregatorWith(snapshot).toVendorCards(instances);

      // Assert
      assertThat(vendors).hasSize(2);
      VendorCard microsoft =
          vendors.stream().filter(v -> v.vendorId().equals("microsoft")).findFirst().orElseThrow();
      VendorCard atlassian =
          vendors.stream().filter(v -> v.vendorId().equals("atlassian")).findFirst().orElseThrow();
      assertThat(microsoft.vendorName()).isEqualTo("Microsoft");
      assertThat(microsoft.vendorServicesCount()).isEqualTo(2);
      assertThat(atlassian.vendorName()).isEqualTo("Atlassian");
      assertThat(atlassian.vendorServicesCount()).isEqualTo(1);
    }

    @Test
    void toVendorCards_shouldEmitSyntheticUnknownVendor_whenUnmappedTripletPresent() {
      VendorMappingSnapshot snapshot = FakeVendorMappingSnapshot.with(MAPPING_VERSION).build();
      List<NormalizedIntegration> instances =
          List.of(
              NormalizedIntegrationFixtures.iconInstance(
                  "c_1", "new-product", IntegrationStatus.HEALTHY));

      // Act
      List<VendorCard> vendors = aggregatorWith(snapshot).toVendorCards(instances);

      // Assert — single synthetic vendor card with vendorServicesCount=1
      assertThat(vendors).hasSize(1);
      assertThat(vendors.get(0).vendorId()).isEqualTo("unknown");
      assertThat(vendors.get(0).vendorName()).isEqualTo("Unknown");
      assertThat(vendors.get(0).vendorServicesCount()).isEqualTo(1);
    }
  }
}
