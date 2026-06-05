package com.rapid7.integrationregistry.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared classpath/JSON helpers for tests that exercise the bundle JSON Schema and its fixtures.
 *
 * <p>Parsing uses Jackson 3 ({@code tools.jackson}); networknt (Jackson-2-internal) is fed raw
 * strings via {@link InputFormat#JSON} so no Jackson 2 databind type is imported here. Owns the
 * single {@link JsonMapper} reused across schema and fixture parsing, the schema/fixture classpath
 * roots, and the JSON-Pointer extraction used by enum-vs-schema sync checks.
 */
final class BundleSchemaResources {

  static final String SCHEMA_CLASSPATH = "/vendor-mapping/schema/v1.json";
  static final String FIXTURES_ROOT = "/vendor-mapping/";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private BundleSchemaResources() {}

  /** Load and parse the bundle JSON Schema (Draft 2020-12). */
  static JsonSchema loadSchema() throws IOException {
    String schemaJson = readClasspath(SCHEMA_CLASSPATH);
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    return factory.getSchema(schemaJson, InputFormat.JSON);
  }

  /** Load the bundle JSON Schema as a raw {@link JsonNode} for structural inspection. */
  static JsonNode loadSchemaNode() throws IOException {
    return MAPPER.readTree(readClasspath(SCHEMA_CLASSPATH));
  }

  /** Read a fixture file under {@link #FIXTURES_ROOT} as a raw JSON string. */
  static String readFixture(String fixtureFileName) throws IOException {
    return readClasspath(FIXTURES_ROOT + fixtureFileName);
  }

  /**
   * Resolve {@code pointer} against {@code root} and collect the resulting array's text values into
   * a {@link Set}.
   */
  static Set<String> textValuesAt(JsonNode root, String pointer) {
    JsonNode node = root.at(pointer);
    return StreamSupport.stream(node.spliterator(), false)
        .map(JsonNode::asString)
        .collect(Collectors.toSet());
  }

  private static String readClasspath(String classpathPath) throws IOException {
    try (InputStream in = BundleSchemaResources.class.getResourceAsStream(classpathPath)) {
      assertThat(in).as("classpath resource %s present", classpathPath).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
