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
 * <p>Per the spec's {@code displayName} gap: {@code displayName} is the raw {@code sourceValue} for
 * both mapped and unmapped triplets until the snapshot surfaces curated bundle {@code display_name}
 * values.
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
