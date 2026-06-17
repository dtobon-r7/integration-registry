package com.rapid7.integrationregistry.testsupport;

import com.rapid7.integrationregistry.mapping.DataSourceResolution;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;

/**
 * Test stub of {@link VendorMappingSnapshot} for unit tests in the loader package that need a
 * snapshot without spinning up the real {@code MapBackedVendorMappingSnapshot} (e.g. listener
 * tests, holder tests, decorator tests, health-indicator tests).
 *
 * <p>Two factories: {@link #returningUnknown(String)} for tests that only care about the {@code
 * mappingVersion()} surface, and {@link #returning(String, DataSourceResolution)} for tests that
 * need a specific resolution value back from {@code lookup(...)}.
 */
public final class StubVendorMappingSnapshot {

  private StubVendorMappingSnapshot() {}

  /**
   * Stub whose {@code lookup(...)} always returns {@link DataSourceResolution#unknown()} — useful
   * for tests that only assert on {@code mappingVersion()} or on side effects of the lookup call.
   */
  public static VendorMappingSnapshot returningUnknown(String version) {
    return returning(version, DataSourceResolution.unknown());
  }

  /**
   * Stub whose {@code lookup(...)} always returns the supplied resolution regardless of arguments.
   * The caller controls both the version and the resolution.
   */
  public static VendorMappingSnapshot returning(String version, DataSourceResolution resolution) {
    return new VendorMappingSnapshot() {
      @Override
      public DataSourceResolution lookup(
          ProductName productName, SourceType sourceType, String sourceValue) {
        return resolution;
      }

      @Override
      public String mappingVersion() {
        return version;
      }
    };
  }
}
