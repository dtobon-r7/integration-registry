/**
 * Read-API projection records produced by {@link
 * com.rapid7.integrationregistry.aggregator.VendorAggregator} and serialized by the controller
 * layer.
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
