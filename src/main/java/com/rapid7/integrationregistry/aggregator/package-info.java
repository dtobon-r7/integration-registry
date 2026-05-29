/**
 * Vendor-service grouping and worst-state-wins health rollup.
 *
 * <p>Holds the typed surface (projection records) and pure helpers
 * ({@link com.rapid7.integrationregistry.aggregator.HealthRollup},
 * {@link com.rapid7.integrationregistry.aggregator.DataSourceIdMinter}) that the
 * {@code VendorAggregator} composes against and the read-API controller layer
 * serializes from.
 */
package com.rapid7.integrationregistry.aggregator;
