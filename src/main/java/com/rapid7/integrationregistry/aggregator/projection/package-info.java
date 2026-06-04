/**
 * Read-API projection records produced by {@link
 * com.rapid7.integrationregistry.aggregator.VendorAggregator} — the aggregator's internal output
 * contract consumed by {@code VendorService}, NOT the wire surface.
 *
 * <p>The public JSON wire surface is {@code com.rapid7.integrationregistry.controller.dto}. These
 * projection records are the source of each wire field's value, but they carry internal-only fields
 * (e.g. {@code dataSourceId} on {@link
 * com.rapid7.integrationregistry.aggregator.projection.IntegrationDetail}, {@code
 * vendorServicesCount} on {@link
 * com.rapid7.integrationregistry.aggregator.projection.VendorScopedView}) and Java-native enums
 * that do not appear on the wire.
 *
 * <p>Each record maps directly to a response body or embedded object in RFC-001 §Read API Contract:
 *
 * <ul>
 *   <li>{@link com.rapid7.integrationregistry.aggregator.projection.VendorServiceCard} — one row in
 *       {@code GET /vendor-services}
 *   <li>{@link com.rapid7.integrationregistry.aggregator.projection.VendorServiceDetail} — body of
 *       {@code GET /vendor-services/{id}}
 *   <li>{@link com.rapid7.integrationregistry.aggregator.projection.VendorCard} — one entry in
 *       {@code GET /vendors}
 *   <li>{@link com.rapid7.integrationregistry.aggregator.projection.VendorScopedView} — body of
 *       {@code GET /vendors/{vendor_id}}
 *   <li>{@link com.rapid7.integrationregistry.aggregator.projection.DataSourceDetail} — nested
 *       data-source block inside {@code VendorServiceDetail}
 *   <li>{@link com.rapid7.integrationregistry.aggregator.projection.IntegrationDetail} —
 *       per-instance row nested inside {@code DataSourceDetail}
 *   <li>{@link com.rapid7.integrationregistry.aggregator.projection.IntegrationTypeCount} —
 *       per-type breakdown nested in {@code VendorServiceCard} and {@code VendorServiceDetail}
 * </ul>
 *
 * <p>All records are immutable value types with compact-constructor validation. No Spring
 * annotations — purely data.
 */
package com.rapid7.integrationregistry.aggregator.projection;
