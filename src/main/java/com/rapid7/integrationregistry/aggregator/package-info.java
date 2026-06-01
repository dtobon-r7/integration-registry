/**
 * Vendor-service grouping and worst-state-wins health rollup.
 *
 * <p>{@link com.rapid7.integrationregistry.aggregator.VendorAggregator} is the single Spring
 * component — it drives resolution and projection. Pure helpers ({@link
 * com.rapid7.integrationregistry.aggregator.HealthRollup}, {@link
 * com.rapid7.integrationregistry.aggregator.DataSourceIdMinter}) live here alongside the
 * package-private {@link com.rapid7.integrationregistry.aggregator.ResolvedInstance} intermediate.
 * Output projections (the records the read-API controller serializes) live in the {@code
 * projection} sub-package.
 */
package com.rapid7.integrationregistry.aggregator;
