package com.rapid7.integrationregistry.aggregator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProjectionRecordsTest {

  private static final String VS_ID = "microsoft-defender";
  private static final String VS_NAME = "Microsoft Defender";
  private static final String VENDOR_ID = "microsoft";
  private static final String VENDOR_NAME = "Microsoft";
  private static final VendorCategory VENDOR_CATEGORY = VendorCategory.EDR;
  private static final IntegrationStatus AGGREGATE_HEALTH = IntegrationStatus.HEALTHY;
  private static final Instant LAST_UPDATED = Instant.parse("2026-05-29T10:00:00Z");

  @Nested
  class IntegrationTypeCountTest {

    @Test
    void constructor_shouldBuildRecord_whenAllFieldsValid() {
      // Act
      IntegrationTypeCount record = new IntegrationTypeCount("SIEM Event Source", 4, 1);

      // Assert
      assertThat(record.integrationType()).isEqualTo("SIEM Event Source");
      assertThat(record.total()).isEqualTo(4);
      assertThat(record.errorCount()).isEqualTo(1);
    }

    @Test
    void constructor_shouldThrowNPE_whenIntegrationTypeNull() {
      assertNpeFromCtor(
          () -> new IntegrationTypeCount(null, 4, 1), IntegrationTypeCount.FIELD_INTEGRATION_TYPE);
    }

    @Test
    void constructor_shouldThrowIAE_whenTotalNegative() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new IntegrationTypeCount("SIEM Event Source", -1, 0))
          .withMessageContaining(IntegrationTypeCount.FIELD_TOTAL);
    }

    @Test
    void constructor_shouldThrowIAE_whenErrorCountNegative() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new IntegrationTypeCount("SIEM Event Source", 4, -1))
          .withMessageContaining(IntegrationTypeCount.FIELD_ERROR_COUNT);
    }

    @Test
    void constructor_shouldThrowIAE_whenErrorCountExceedsTotal() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new IntegrationTypeCount("SIEM Event Source", 2, 3))
          .withMessageContaining(IntegrationTypeCount.FIELD_ERROR_COUNT)
          .withMessageContaining(IntegrationTypeCount.FIELD_TOTAL);
    }

    @Test
    void constructor_shouldAccept_whenErrorCountEqualsTotal() {
      // Arrange — boundary case: every instance is in error
      // Act
      IntegrationTypeCount record = new IntegrationTypeCount("SIEM Event Source", 4, 4);

      // Assert
      assertThat(record.errorCount()).isEqualTo(4);
    }
  }

  @Nested
  class VendorCardTest {

    @Test
    void constructor_shouldBuildRecord_whenAllFieldsValid() {
      // Act
      VendorCard record = new VendorCard(VENDOR_ID, VENDOR_NAME, 3);

      // Assert
      assertThat(record.vendorId()).isEqualTo(VENDOR_ID);
      assertThat(record.vendorName()).isEqualTo(VENDOR_NAME);
      assertThat(record.vendorServicesCount()).isEqualTo(3);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorIdNull() {
      assertNpeFromCtor(() -> new VendorCard(null, VENDOR_NAME, 3), VendorCard.FIELD_VENDOR_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorNameNull() {
      assertNpeFromCtor(() -> new VendorCard(VENDOR_ID, null, 3), VendorCard.FIELD_VENDOR_NAME);
    }

    @Test
    void constructor_shouldThrowIAE_whenVendorServicesCountNegative() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new VendorCard(VENDOR_ID, VENDOR_NAME, -1))
          .withMessageContaining(VendorCard.FIELD_VENDOR_SERVICES_COUNT);
    }
  }

  @Nested
  class IntegrationDetailTest {

    private static final String INTEGRATION_ID = "es_1234";
    private static final String DATA_SOURCE_ID =
        "insightidr|product_type|microsoft-defender-endpoint";
    private static final String CONFIGURATION_URL = "https://idr.example/eventsources/es_1234";

    @Test
    void constructor_shouldBuildRecord_whenAllFieldsValid() {
      // Act
      IntegrationDetail record =
          new IntegrationDetail(
              INTEGRATION_ID,
              DATA_SOURCE_ID,
              "my-defender",
              IntegrationStatus.HEALTHY,
              LAST_UPDATED,
              CONFIGURATION_URL);

      // Assert
      assertThat(record.integrationId()).isEqualTo(INTEGRATION_ID);
      assertThat(record.dataSourceId()).isEqualTo(DATA_SOURCE_ID);
      assertThat(record.integrationLabel()).isEqualTo("my-defender");
      assertThat(record.status()).isEqualTo(IntegrationStatus.HEALTHY);
      assertThat(record.lastSuccessTimestamp()).isEqualTo(LAST_UPDATED);
      assertThat(record.configurationUrl()).isEqualTo(CONFIGURATION_URL);
    }

    @Test
    void constructor_shouldAcceptNullIntegrationLabel_whenSourceProductHasNoPerInstanceName() {
      // Arrange — RFC-001: integrationLabel is nullable when the source product
      // exposes no per-instance customer-given name (e.g. ICON connections).

      // Act
      IntegrationDetail record =
          new IntegrationDetail(
              INTEGRATION_ID,
              DATA_SOURCE_ID,
              null,
              IntegrationStatus.HEALTHY,
              LAST_UPDATED,
              CONFIGURATION_URL);

      // Assert
      assertThat(record.integrationLabel()).isNull();
    }

    @Test
    void constructor_shouldAcceptNullLastSuccessTimestamp_whenNoSuccessfulActivityRecorded() {
      // Act
      IntegrationDetail record =
          new IntegrationDetail(
              INTEGRATION_ID,
              DATA_SOURCE_ID,
              "my-defender",
              IntegrationStatus.HEALTHY,
              null,
              CONFIGURATION_URL);

      // Assert
      assertThat(record.lastSuccessTimestamp()).isNull();
    }

    @Test
    void constructor_shouldThrowNPE_whenIntegrationIdNull() {
      assertNpeFromCtor(
          () ->
              new IntegrationDetail(
                  null,
                  DATA_SOURCE_ID,
                  "x",
                  IntegrationStatus.HEALTHY,
                  LAST_UPDATED,
                  CONFIGURATION_URL),
          IntegrationDetail.FIELD_INTEGRATION_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenDataSourceIdNull() {
      assertNpeFromCtor(
          () ->
              new IntegrationDetail(
                  INTEGRATION_ID,
                  null,
                  "x",
                  IntegrationStatus.HEALTHY,
                  LAST_UPDATED,
                  CONFIGURATION_URL),
          IntegrationDetail.FIELD_DATA_SOURCE_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenStatusNull() {
      assertNpeFromCtor(
          () ->
              new IntegrationDetail(
                  INTEGRATION_ID, DATA_SOURCE_ID, "x", null, LAST_UPDATED, CONFIGURATION_URL),
          IntegrationDetail.FIELD_STATUS);
    }

    @Test
    void constructor_shouldThrowNPE_whenConfigurationUrlNull() {
      assertNpeFromCtor(
          () ->
              new IntegrationDetail(
                  INTEGRATION_ID,
                  DATA_SOURCE_ID,
                  "x",
                  IntegrationStatus.HEALTHY,
                  LAST_UPDATED,
                  null),
          IntegrationDetail.FIELD_CONFIGURATION_URL);
    }
  }

  @Nested
  class DataSourceDetailTest {

    private static final String DS_ID = "insightidr|product_type|microsoft-defender-endpoint";
    private static final String DISPLAY_NAME = "Microsoft Defender for Endpoint";
    private static final String INTEGRATION_TYPE = "SIEM Event Source";
    private static final String PRODUCT_NAME = "InsightIDR";

    private IntegrationDetail oneIntegration() {
      return new IntegrationDetail(
          "es_1234",
          DS_ID,
          "my-defender",
          IntegrationStatus.HEALTHY,
          LAST_UPDATED,
          "https://idr.example/eventsources/es_1234");
    }

    @Test
    void constructor_shouldBuildRecord_whenAllFieldsValid() {
      // Arrange
      List<IntegrationDetail> instances = List.of(oneIntegration());

      // Act
      DataSourceDetail record =
          new DataSourceDetail(
              DS_ID,
              DISPLAY_NAME,
              INTEGRATION_TYPE,
              PRODUCT_NAME,
              IntegrationStatus.HEALTHY,
              1,
              instances);

      // Assert
      assertThat(record.dataSourceId()).isEqualTo(DS_ID);
      assertThat(record.displayName()).isEqualTo(DISPLAY_NAME);
      assertThat(record.integrationType()).isEqualTo(INTEGRATION_TYPE);
      assertThat(record.productName()).isEqualTo(PRODUCT_NAME);
      assertThat(record.status()).isEqualTo(IntegrationStatus.HEALTHY);
      assertThat(record.integrationsCount()).isEqualTo(1);
      assertThat(record.integrations()).hasSize(1);
    }

    @Test
    void constructor_shouldDefensivelyCopyIntegrations() {
      // Arrange — pass a mutable list, then mutate it after construction
      List<IntegrationDetail> mutable = new ArrayList<>();
      mutable.add(oneIntegration());

      // Act
      DataSourceDetail record =
          new DataSourceDetail(
              DS_ID,
              DISPLAY_NAME,
              INTEGRATION_TYPE,
              PRODUCT_NAME,
              IntegrationStatus.HEALTHY,
              1,
              mutable);
      mutable.clear();

      // Assert — record's snapshot is unaffected
      assertThat(record.integrations()).hasSize(1);
    }

    @Test
    void integrationsAccessor_shouldReturnUnmodifiableList() {
      // Arrange
      DataSourceDetail record =
          new DataSourceDetail(
              DS_ID,
              DISPLAY_NAME,
              INTEGRATION_TYPE,
              PRODUCT_NAME,
              IntegrationStatus.HEALTHY,
              1,
              List.of(oneIntegration()));

      // Assert
      assertThatThrownBy(() -> record.integrations().add(oneIntegration()))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constructor_shouldThrowIAE_whenIntegrationsCountDoesNotEqualListSize() {
      // Arrange — invariant: integrationsCount must equal integrations.size()
      List<IntegrationDetail> instances = List.of(oneIntegration());

      // Assert
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  new DataSourceDetail(
                      DS_ID,
                      DISPLAY_NAME,
                      INTEGRATION_TYPE,
                      PRODUCT_NAME,
                      IntegrationStatus.HEALTHY,
                      5,
                      instances))
          .withMessageContaining(DataSourceDetail.FIELD_INTEGRATIONS_COUNT);
    }

    @Test
    void constructor_shouldThrowIAE_whenIntegrationsCountNegative() {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  new DataSourceDetail(
                      DS_ID,
                      DISPLAY_NAME,
                      INTEGRATION_TYPE,
                      PRODUCT_NAME,
                      IntegrationStatus.HEALTHY,
                      -1,
                      List.of()))
          .withMessageContaining(DataSourceDetail.FIELD_INTEGRATIONS_COUNT);
    }

    @Test
    void constructor_shouldThrowNPE_whenDataSourceIdNull() {
      assertNpeFromCtor(
          () ->
              new DataSourceDetail(
                  null,
                  DISPLAY_NAME,
                  INTEGRATION_TYPE,
                  PRODUCT_NAME,
                  IntegrationStatus.HEALTHY,
                  0,
                  List.of()),
          DataSourceDetail.FIELD_DATA_SOURCE_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenDisplayNameNull() {
      assertNpeFromCtor(
          () ->
              new DataSourceDetail(
                  DS_ID,
                  null,
                  INTEGRATION_TYPE,
                  PRODUCT_NAME,
                  IntegrationStatus.HEALTHY,
                  0,
                  List.of()),
          DataSourceDetail.FIELD_DISPLAY_NAME);
    }

    @Test
    void constructor_shouldThrowNPE_whenIntegrationTypeNull() {
      assertNpeFromCtor(
          () ->
              new DataSourceDetail(
                  DS_ID, DISPLAY_NAME, null, PRODUCT_NAME, IntegrationStatus.HEALTHY, 0, List.of()),
          DataSourceDetail.FIELD_INTEGRATION_TYPE);
    }

    @Test
    void constructor_shouldThrowNPE_whenProductNameNull() {
      assertNpeFromCtor(
          () ->
              new DataSourceDetail(
                  DS_ID,
                  DISPLAY_NAME,
                  INTEGRATION_TYPE,
                  null,
                  IntegrationStatus.HEALTHY,
                  0,
                  List.of()),
          DataSourceDetail.FIELD_PRODUCT_NAME);
    }

    @Test
    void constructor_shouldThrowNPE_whenStatusNull() {
      assertNpeFromCtor(
          () ->
              new DataSourceDetail(
                  DS_ID, DISPLAY_NAME, INTEGRATION_TYPE, PRODUCT_NAME, null, 0, List.of()),
          DataSourceDetail.FIELD_STATUS);
    }

    @Test
    void constructor_shouldThrowNPE_whenIntegrationsNull() {
      assertNpeFromCtor(
          () ->
              new DataSourceDetail(
                  DS_ID,
                  DISPLAY_NAME,
                  INTEGRATION_TYPE,
                  PRODUCT_NAME,
                  IntegrationStatus.HEALTHY,
                  0,
                  null),
          DataSourceDetail.FIELD_INTEGRATIONS);
    }
  }

  @Nested
  class VendorServiceCardTest {

    private VendorServiceCard build(Instant lastUpdated) {
      return new VendorServiceCard(
          VS_ID,
          VS_NAME,
          VENDOR_ID,
          VENDOR_NAME,
          VENDOR_CATEGORY,
          4,
          List.of(new IntegrationTypeCount("SIEM Event Source", 4, 1)),
          List.of("InsightIDR"),
          AGGREGATE_HEALTH,
          lastUpdated);
    }

    @Test
    void constructor_shouldBuildRecord_whenAllFieldsValid() {
      // Act
      VendorServiceCard record = build(LAST_UPDATED);

      // Assert
      assertThat(record.vendorServiceId()).isEqualTo(VS_ID);
      assertThat(record.integrationsConnected()).isEqualTo(4);
      assertThat(record.integrationTypeCounts()).hasSize(1);
      assertThat(record.productsConnected()).containsExactly("InsightIDR");
      assertThat(record.aggregateHealth()).isEqualTo(AGGREGATE_HEALTH);
      assertThat(record.lastUpdated()).isEqualTo(LAST_UPDATED);
    }

    @Test
    void constructor_shouldAcceptNullLastUpdated_whenNoInstanceHasSucceededYet() {
      // Arrange — RFC-001: lastUpdated is nullable

      // Act
      VendorServiceCard record = build(null);

      // Assert
      assertThat(record.lastUpdated()).isNull();
    }

    @Test
    void constructor_shouldDefensivelyCopyCollections() {
      // Arrange
      List<IntegrationTypeCount> mutableCounts = new ArrayList<>();
      mutableCounts.add(new IntegrationTypeCount("SIEM Event Source", 4, 1));
      List<String> mutableProducts = new ArrayList<>();
      mutableProducts.add("InsightIDR");

      // Act
      VendorServiceCard record =
          new VendorServiceCard(
              VS_ID,
              VS_NAME,
              VENDOR_ID,
              VENDOR_NAME,
              VENDOR_CATEGORY,
              4,
              mutableCounts,
              mutableProducts,
              AGGREGATE_HEALTH,
              LAST_UPDATED);
      mutableCounts.clear();
      mutableProducts.clear();

      // Assert
      assertThat(record.integrationTypeCounts()).hasSize(1);
      assertThat(record.productsConnected()).containsExactly("InsightIDR");
    }

    @Test
    void constructor_shouldThrowIAE_whenIntegrationsConnectedNegative() {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  new VendorServiceCard(
                      VS_ID,
                      VS_NAME,
                      VENDOR_ID,
                      VENDOR_NAME,
                      VENDOR_CATEGORY,
                      -1,
                      List.of(),
                      List.of(),
                      AGGREGATE_HEALTH,
                      LAST_UPDATED))
          .withMessageContaining(VendorServiceCard.FIELD_INTEGRATIONS_CONNECTED);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorServiceIdNull() {
      assertNpeFromCtor(
          () ->
              new VendorServiceCard(
                  null,
                  VS_NAME,
                  VENDOR_ID,
                  VENDOR_NAME,
                  VENDOR_CATEGORY,
                  0,
                  List.of(),
                  List.of(),
                  AGGREGATE_HEALTH,
                  LAST_UPDATED),
          VendorServiceCard.FIELD_VENDOR_SERVICE_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorServiceNameNull() {
      assertNpeFromCtor(
          () ->
              new VendorServiceCard(
                  VS_ID,
                  null,
                  VENDOR_ID,
                  VENDOR_NAME,
                  VENDOR_CATEGORY,
                  0,
                  List.of(),
                  List.of(),
                  AGGREGATE_HEALTH,
                  LAST_UPDATED),
          VendorServiceCard.FIELD_VENDOR_SERVICE_NAME);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorIdNull() {
      assertNpeFromCtor(
          () ->
              new VendorServiceCard(
                  VS_ID,
                  VS_NAME,
                  null,
                  VENDOR_NAME,
                  VENDOR_CATEGORY,
                  0,
                  List.of(),
                  List.of(),
                  AGGREGATE_HEALTH,
                  LAST_UPDATED),
          VendorServiceCard.FIELD_VENDOR_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorNameNull() {
      assertNpeFromCtor(
          () ->
              new VendorServiceCard(
                  VS_ID,
                  VS_NAME,
                  VENDOR_ID,
                  null,
                  VENDOR_CATEGORY,
                  0,
                  List.of(),
                  List.of(),
                  AGGREGATE_HEALTH,
                  LAST_UPDATED),
          VendorServiceCard.FIELD_VENDOR_NAME);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorCategoryNull() {
      assertNpeFromCtor(
          () ->
              new VendorServiceCard(
                  VS_ID,
                  VS_NAME,
                  VENDOR_ID,
                  VENDOR_NAME,
                  null,
                  0,
                  List.of(),
                  List.of(),
                  AGGREGATE_HEALTH,
                  LAST_UPDATED),
          VendorServiceCard.FIELD_VENDOR_CATEGORY);
    }

    @Test
    void constructor_shouldThrowNPE_whenIntegrationTypeCountsNull() {
      assertNpeFromCtor(
          () ->
              new VendorServiceCard(
                  VS_ID,
                  VS_NAME,
                  VENDOR_ID,
                  VENDOR_NAME,
                  VENDOR_CATEGORY,
                  0,
                  null,
                  List.of(),
                  AGGREGATE_HEALTH,
                  LAST_UPDATED),
          VendorServiceCard.FIELD_INTEGRATION_TYPE_COUNTS);
    }

    @Test
    void constructor_shouldThrowNPE_whenProductsConnectedNull() {
      assertNpeFromCtor(
          () ->
              new VendorServiceCard(
                  VS_ID,
                  VS_NAME,
                  VENDOR_ID,
                  VENDOR_NAME,
                  VENDOR_CATEGORY,
                  0,
                  List.of(),
                  null,
                  AGGREGATE_HEALTH,
                  LAST_UPDATED),
          VendorServiceCard.FIELD_PRODUCTS_CONNECTED);
    }

    @Test
    void constructor_shouldThrowNPE_whenAggregateHealthNull() {
      assertNpeFromCtor(
          () ->
              new VendorServiceCard(
                  VS_ID,
                  VS_NAME,
                  VENDOR_ID,
                  VENDOR_NAME,
                  VENDOR_CATEGORY,
                  0,
                  List.of(),
                  List.of(),
                  null,
                  LAST_UPDATED),
          VendorServiceCard.FIELD_AGGREGATE_HEALTH);
    }
  }

  @Nested
  class VendorScopedViewTest {

    private VendorServiceCard oneVendorService() {
      return new VendorServiceCard(
          VS_ID,
          VS_NAME,
          VENDOR_ID,
          VENDOR_NAME,
          VENDOR_CATEGORY,
          4,
          List.of(new IntegrationTypeCount("SIEM Event Source", 4, 1)),
          List.of("InsightIDR"),
          AGGREGATE_HEALTH,
          LAST_UPDATED);
    }

    @Test
    void constructor_shouldBuildRecord_whenAllFieldsValid() {
      // Act
      VendorScopedView record =
          new VendorScopedView(
              VENDOR_ID,
              VENDOR_NAME,
              1,
              AGGREGATE_HEALTH,
              LAST_UPDATED,
              List.of(oneVendorService()));

      // Assert
      assertThat(record.vendorId()).isEqualTo(VENDOR_ID);
      assertThat(record.vendorServicesCount()).isEqualTo(1);
      assertThat(record.aggregateHealth()).isEqualTo(AGGREGATE_HEALTH);
      assertThat(record.lastUpdated()).isEqualTo(LAST_UPDATED);
      assertThat(record.vendorServices()).hasSize(1);
    }

    @Test
    void constructor_shouldAcceptNullLastUpdated_whenNoInstanceHasSucceededYet() {
      // Act
      VendorScopedView record =
          new VendorScopedView(
              VENDOR_ID, VENDOR_NAME, 1, AGGREGATE_HEALTH, null, List.of(oneVendorService()));

      // Assert
      assertThat(record.lastUpdated()).isNull();
    }

    @Test
    void constructor_shouldDefensivelyCopyVendorServices() {
      // Arrange
      List<VendorServiceCard> mutable = new ArrayList<>();
      mutable.add(oneVendorService());

      // Act
      VendorScopedView record =
          new VendorScopedView(VENDOR_ID, VENDOR_NAME, 1, AGGREGATE_HEALTH, LAST_UPDATED, mutable);
      mutable.clear();

      // Assert
      assertThat(record.vendorServices()).hasSize(1);
    }

    @Test
    void constructor_shouldThrowIAE_whenVendorServicesCountNegative() {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  new VendorScopedView(
                      VENDOR_ID, VENDOR_NAME, -1, AGGREGATE_HEALTH, LAST_UPDATED, List.of()))
          .withMessageContaining(VendorScopedView.FIELD_VENDOR_SERVICES_COUNT);
    }

    @Test
    void constructor_shouldThrowIAE_whenVendorServicesCountDoesNotEqualListSize() {
      // Arrange — invariant: vendorServicesCount must equal vendorServices.size()
      // (mirrors DataSourceDetail's integrationsCount invariant)
      List<VendorServiceCard> services = List.of(oneVendorService());

      // Assert
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  new VendorScopedView(
                      VENDOR_ID, VENDOR_NAME, 5, AGGREGATE_HEALTH, LAST_UPDATED, services))
          .withMessageContaining(VendorScopedView.FIELD_VENDOR_SERVICES_COUNT);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorIdNull() {
      assertNpeFromCtor(
          () ->
              new VendorScopedView(null, VENDOR_NAME, 0, AGGREGATE_HEALTH, LAST_UPDATED, List.of()),
          VendorScopedView.FIELD_VENDOR_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorNameNull() {
      assertNpeFromCtor(
          () -> new VendorScopedView(VENDOR_ID, null, 0, AGGREGATE_HEALTH, LAST_UPDATED, List.of()),
          VendorScopedView.FIELD_VENDOR_NAME);
    }

    @Test
    void constructor_shouldThrowNPE_whenAggregateHealthNull() {
      assertNpeFromCtor(
          () -> new VendorScopedView(VENDOR_ID, VENDOR_NAME, 0, null, LAST_UPDATED, List.of()),
          VendorScopedView.FIELD_AGGREGATE_HEALTH);
    }

    @Test
    void constructor_shouldThrowNPE_whenVendorServicesNull() {
      assertNpeFromCtor(
          () ->
              new VendorScopedView(VENDOR_ID, VENDOR_NAME, 0, AGGREGATE_HEALTH, LAST_UPDATED, null),
          VendorScopedView.FIELD_VENDOR_SERVICES);
    }
  }

  @Nested
  class VendorServiceDetailTest {

    private DataSourceDetail oneDataSource() {
      return new DataSourceDetail(
          "insightidr|product_type|microsoft-defender-endpoint",
          "Microsoft Defender for Endpoint",
          "SIEM Event Source",
          "InsightIDR",
          IntegrationStatus.HEALTHY,
          0,
          List.of());
    }

    private VendorServiceDetail build(Instant lastUpdated) {
      return new VendorServiceDetail(
          VS_ID,
          VS_NAME,
          VENDOR_ID,
          VENDOR_NAME,
          VENDOR_CATEGORY,
          0,
          List.of(),
          List.of(),
          AGGREGATE_HEALTH,
          lastUpdated,
          List.of(oneDataSource()));
    }

    @Test
    void constructor_shouldBuildRecord_whenAllFieldsValid() {
      // Act
      VendorServiceDetail record = build(LAST_UPDATED);

      // Assert
      assertThat(record.vendorServiceId()).isEqualTo(VS_ID);
      assertThat(record.dataSources()).hasSize(1);
      assertThat(record.lastUpdated()).isEqualTo(LAST_UPDATED);
    }

    @Test
    void constructor_shouldAcceptNullLastUpdated() {
      // Act
      VendorServiceDetail record = build(null);

      // Assert
      assertThat(record.lastUpdated()).isNull();
    }

    @Test
    void constructor_shouldDefensivelyCopyDataSources() {
      // Arrange
      List<DataSourceDetail> mutable = new ArrayList<>();
      mutable.add(oneDataSource());

      // Act
      VendorServiceDetail record =
          new VendorServiceDetail(
              VS_ID,
              VS_NAME,
              VENDOR_ID,
              VENDOR_NAME,
              VENDOR_CATEGORY,
              0,
              List.of(),
              List.of(),
              AGGREGATE_HEALTH,
              LAST_UPDATED,
              mutable);
      mutable.clear();

      // Assert
      assertThat(record.dataSources()).hasSize(1);
    }

    @Test
    void constructor_shouldThrowIAE_whenIntegrationsConnectedNegative() {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  new VendorServiceDetail(
                      VS_ID,
                      VS_NAME,
                      VENDOR_ID,
                      VENDOR_NAME,
                      VENDOR_CATEGORY,
                      -1,
                      List.of(),
                      List.of(),
                      AGGREGATE_HEALTH,
                      LAST_UPDATED,
                      List.of()))
          .withMessageContaining(VendorServiceDetail.FIELD_INTEGRATIONS_CONNECTED);
    }

    @Test
    void constructor_shouldThrowNPE_whenDataSourcesNull() {
      assertNpeFromCtor(
          () ->
              new VendorServiceDetail(
                  VS_ID,
                  VS_NAME,
                  VENDOR_ID,
                  VENDOR_NAME,
                  VENDOR_CATEGORY,
                  0,
                  List.of(),
                  List.of(),
                  AGGREGATE_HEALTH,
                  LAST_UPDATED,
                  null),
          VendorServiceDetail.FIELD_DATA_SOURCES);
    }
  }

  private static void assertNpeFromCtor(ThrowingCallable ctor, String expectedField) {
    assertThatNullPointerException().isThrownBy(ctor).withMessage(expectedField);
  }
}
