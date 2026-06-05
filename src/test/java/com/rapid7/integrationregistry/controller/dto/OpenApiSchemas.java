package com.rapid7.integrationregistry.controller.dto;

import com.networknt.schema.InputFormat;
import com.networknt.schema.InvalidSchemaRefException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.ValidationMessage;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test helper: loads the locked {@code openapi.json} contract fixture and validates a serialized
 * JSON string against individual {@code #/components/schemas/<Name>} component schemas, with {@code
 * $ref} resolution against the same document. Used by the DTO serialization tests to prove wire
 * conformance.
 *
 * <p>The contract is OpenAPI 3.1, whose component schemas are JSON-Schema-2020-12 compatible, so
 * validation runs under {@link VersionFlag#V202012}.
 *
 * <p>Callers pass the raw JSON string from {@code JacksonTester.write(...).getJson()} — i.e. output
 * of the production Jackson 3 ({@code tools.jackson}) mapper. networknt parses that string with its
 * own internal parser via {@link InputFormat#JSON}, so this helper (and its callers) never import
 * Jackson 2 {@code databind} types; the only Jackson on the project's own source path is Jackson 3.
 */
final class OpenApiSchemas {

  /**
   * Classpath URI of the contract fixture. The {@code classpath:} scheme is resolved by networknt's
   * {@code ClasspathSchemaLoader}; appending a {@code #/components/schemas/<Name>} fragment makes
   * the factory load the whole document (so intra-document {@code $ref}s resolve) and then navigate
   * to the named sub-schema.
   */
  private static final String CONTRACT_URI = "classpath:contract/openapi.json";

  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(VersionFlag.V202012);

  /**
   * Format assertions are annotation-only by default under JSON Schema 2020-12, so {@code format:
   * date-time} (Iso8601) and {@code format: uri} (configuration_url) would NOT be enforced. We
   * enable them so the serialization tests actually prove timestamp/URI wire-format conformance
   * rather than relying incidentally on the DTOs happening to use {@code java.time.Instant}.
   */
  private static final SchemaValidatorsConfig CONFIG =
      SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();

  private static final ConcurrentHashMap<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

  private OpenApiSchemas() {}

  /**
   * Validates {@code json} against the named component schema; empty set means conformant.
   *
   * <p>The contract schemas do not set {@code additionalProperties: false}, so an empty result
   * confirms required fields, types, and enum values — but does NOT reject extra/unexpected
   * properties. Tests that must prove an internal-only field never leaks (e.g. {@code
   * data_source_id} on the wire Integration) assert that explicitly with {@code doesNotContain}
   * rather than relying on schema validation alone.
   */
  static Set<ValidationMessage> validate(String schemaName, String json) {
    return CACHE
        .computeIfAbsent(schemaName, OpenApiSchemas::buildSchema)
        .validate(json, InputFormat.JSON);
  }

  private static JsonSchema buildSchema(String schemaName) {
    SchemaLocation location =
        SchemaLocation.of(CONTRACT_URI + "#/components/schemas/" + schemaName);
    try {
      return FACTORY.getSchema(location, CONFIG);
    } catch (InvalidSchemaRefException e) {
      throw new IllegalArgumentException("No component schema named: " + schemaName, e);
    }
  }
}
