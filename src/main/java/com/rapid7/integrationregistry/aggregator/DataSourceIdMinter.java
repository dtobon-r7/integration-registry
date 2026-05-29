package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.mapping.SourceType;
import java.util.Locale;
import java.util.Objects;

/**
 * Mints the canonical {@code data_source_id} per RFC-001 §Data Model → {@code data_source_id}
 * construction:
 *
 * <pre>data_source_id = lower(productName).replace(' ', '-')
 *                  + '|' + sourceType.wireForm()
 *                  + '|' + sourceValue</pre>
 */
public final class DataSourceIdMinter {

  private static final char DELIMITER = '|';

  private DataSourceIdMinter() {}

  /**
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code productName} or {@code sourceValue} is empty, or if
   *     {@code sourceValue} contains '|' (which would make the composite ambiguously parseable)
   */
  public static String mint(String productName, SourceType sourceType, String sourceValue) {
    Objects.requireNonNull(productName, "productName");
    Objects.requireNonNull(sourceType, "sourceType");
    Objects.requireNonNull(sourceValue, "sourceValue");
    if (productName.isBlank()) {
      throw new IllegalArgumentException("productName must not be blank");
    }
    if (sourceValue.isEmpty()) {
      throw new IllegalArgumentException("sourceValue must not be empty");
    }
    if (sourceValue.indexOf(DELIMITER) >= 0) {
      throw new IllegalArgumentException("sourceValue must not contain '|': " + sourceValue);
    }
    String slug = productName.toLowerCase(Locale.ROOT).replace(' ', '-');
    return slug + DELIMITER + sourceType.wireForm() + DELIMITER + sourceValue;
  }
}
