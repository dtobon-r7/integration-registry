package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.mapping.VendorCategory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Expanded row for {@code GET /vendor-services/{vendor_service_id}} per RFC-001
 * §Read API Contract → Projections. Vendor-service header (mirrors
 * {@link VendorServiceCard}) plus {@code dataSources[]} of {@link DataSourceDetail},
 * each carrying its own nested {@code integrations[]}. {@code lastUpdated} is
 * nullable per the RFC.
 */
@SuppressWarnings("PMD.ExcessiveParameterList")
// 11 fields are dictated by the RFC §Vendor Service entity + nested data sources.
public record VendorServiceDetail(
    String vendorServiceId,
    String vendorServiceName,
    String vendorId,
    String vendorName,
    VendorCategory vendorCategory,
    int integrationsConnected,
    List<IntegrationTypeCount> integrationTypeCounts,
    List<String> productsConnected,
    IntegrationStatus aggregateHealth,
    Instant lastUpdated,
    List<DataSourceDetail> dataSources
) {

    static final String FIELD_VENDOR_SERVICE_ID = "vendorServiceId";
    static final String FIELD_VENDOR_SERVICE_NAME = "vendorServiceName";
    static final String FIELD_VENDOR_ID = "vendorId";
    static final String FIELD_VENDOR_NAME = "vendorName";
    static final String FIELD_VENDOR_CATEGORY = "vendorCategory";
    static final String FIELD_INTEGRATIONS_CONNECTED = "integrationsConnected";
    static final String FIELD_INTEGRATION_TYPE_COUNTS = "integrationTypeCounts";
    static final String FIELD_PRODUCTS_CONNECTED = "productsConnected";
    static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";
    static final String FIELD_DATA_SOURCES = "dataSources";

    public VendorServiceDetail {
        Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
        Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
        Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
        Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
        Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
        Objects.requireNonNull(integrationTypeCounts, FIELD_INTEGRATION_TYPE_COUNTS);
        Objects.requireNonNull(productsConnected, FIELD_PRODUCTS_CONNECTED);
        Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
        Objects.requireNonNull(dataSources, FIELD_DATA_SOURCES);
        if (integrationsConnected < 0) {
            throw new IllegalArgumentException(
                FIELD_INTEGRATIONS_CONNECTED + " must be >= 0: " + integrationsConnected);
        }
        integrationTypeCounts = List.copyOf(integrationTypeCounts);
        productsConnected = List.copyOf(productsConnected);
        dataSources = List.copyOf(dataSources);
    }
}
