package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Test helper: loads the locked {@code openapi.json} contract fixture and validates JSON nodes
 * against individual {@code #/components/schemas/<Name>} component schemas, with {@code $ref}
 * resolution against the same document. Used by the DTO serialization tests to prove wire
 * conformance.
 *
 * <p>The contract is OpenAPI 3.1, whose component schemas are JSON-Schema-2020-12 compatible, so
 * validation runs under {@link VersionFlag#V202012}.
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
  private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder().build();
  private static final ConcurrentHashMap<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

  private OpenApiSchemas() {}

  /** Validates {@code node} against the named component schema; empty set means conformant. */
  static Set<ValidationMessage> validate(String schemaName, JsonNode node) {
    return CACHE.computeIfAbsent(schemaName, OpenApiSchemas::buildSchema).validate(node);
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
