package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import java.util.Objects;

/**
 * Output of {@code VendorAggregator}'s shared resolution pass — one record per input {@link
 * NormalizedIntegration}, carrying the minted {@code dataSourceId}, the canonical {@link
 * VendorResolution} (real or {@link VendorResolution#unknown()}), and the per-data-source {@code
 * displayName}.
 *
 * <p>{@code displayName} carries the curated, data-source-level {@code display_name} from the
 * vendor-mapping bundle for mapped triplets, and the fixed label {@code "Unknown"} for unmapped
 * triplets (via {@link com.rapid7.integrationregistry.mapping.DataSourceResolution#unknown()}). It
 * is never the raw {@code sourceValue}.
 *
 * <p>Package-private — internal contract between resolution and projection stages of {@code
 * VendorAggregator}, never surfaced on the public API.
 */
record ResolvedInstance(
    NormalizedIntegration instance,
    String dataSourceId,
    VendorResolution resolution,
    String displayName) {

  private static final String FIELD_INSTANCE = "instance";
  private static final String FIELD_DATA_SOURCE_ID = "dataSourceId";
  private static final String FIELD_RESOLUTION = "resolution";
  private static final String FIELD_DISPLAY_NAME = "displayName";

  ResolvedInstance {
    Objects.requireNonNull(instance, FIELD_INSTANCE);
    Objects.requireNonNull(dataSourceId, FIELD_DATA_SOURCE_ID);
    Objects.requireNonNull(resolution, FIELD_RESOLUTION);
    Objects.requireNonNull(displayName, FIELD_DISPLAY_NAME);
  }
}
