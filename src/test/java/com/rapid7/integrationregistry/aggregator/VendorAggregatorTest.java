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
  }
}
