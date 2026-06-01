package com.rapid7.integrationregistry.aggregator;

import com.rapid7.integrationregistry.mapping.SourceType;
import java.util.Locale;
import java.util.Objects;

/**
 * Mints the canonical {@code data_source_id} per RFC-001 §Data Model → {@code data_source_id}
 * construction:
 *
 * <pre>data_source_id = lower(productName).replace(' ', '-')
 *                  + '|' + sourceType
 *                  + '|' + sourceValue</pre>
 *
 * <p>Two overloads share the same formula. The enum overload is the preferred call site (type-safe
 * sourceType); the String overload exists for the aggregator's unmappable-enum fallback path, where
 * {@code SourceType.fromWireForm} returned {@code Optional.empty()} and a raw wire-form string is
 * all we have.
 */
public final class DataSourceIdMinter {

  private static final char DELIMITER = '|';
  private static final String FIELD_PRODUCT_NAME = "productName";
  private static final String FIELD_SOURCE_TYPE = "sourceType";
  private static final String FIELD_SOURCE_VALUE = "sourceValue";

  private DataSourceIdMinter() {}

  /**
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code productName} or {@code sourceType} is blank, if
   *     {@code sourceValue} is empty, or if {@code sourceType} or {@code sourceValue} contains '|'
   *     (which would make the composite ambiguously parseable)
   */
  public static String mint(String productName, String sourceType, String sourceValue) {
    validateNotBlank(productName, FIELD_PRODUCT_NAME);
    validateSourceType(sourceType);
    validateSourceValue(sourceValue);
    String slug = productName.toLowerCase(Locale.ROOT).replace(' ', '-');
    return slug + DELIMITER + sourceType + DELIMITER + sourceValue;
  }

  private static void validateNotBlank(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
  }

  private static void validateSourceType(String sourceType) {
    validateNotBlank(sourceType, FIELD_SOURCE_TYPE);
    if (sourceType.indexOf(DELIMITER) >= 0) {
      throw new IllegalArgumentException(
          FIELD_SOURCE_TYPE + " must not contain '|': " + sourceType);
    }
  }

  private static void validateSourceValue(String sourceValue) {
    Objects.requireNonNull(sourceValue, FIELD_SOURCE_VALUE);
    if (sourceValue.isEmpty()) {
      throw new IllegalArgumentException(FIELD_SOURCE_VALUE + " must not be empty");
    }
    if (sourceValue.indexOf(DELIMITER) >= 0) {
      throw new IllegalArgumentException(
          FIELD_SOURCE_VALUE + " must not contain '|': " + sourceValue);
    }
  }

  /**
   * Type-safe overload that delegates to {@link #mint(String, String, String)} with {@code
   * sourceType.wireForm()}. Behaviour is identical for in-enum source types — existing call sites
   * and tests are unaffected.
   */
  public static String mint(String productName, SourceType sourceType, String sourceValue) {
    Objects.requireNonNull(sourceType, FIELD_SOURCE_TYPE);
    return mint(productName, sourceType.wireForm(), sourceValue);
  }
}
