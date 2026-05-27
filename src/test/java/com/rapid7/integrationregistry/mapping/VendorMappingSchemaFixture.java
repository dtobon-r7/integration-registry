package com.rapid7.integrationregistry.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared classpath/JSON helpers for tests that exercise the bundle JSON Schema
 * and its fixtures.
 *
 * <p>Owns the single {@link ObjectMapper} reused across schema and fixture
 * parsing, the schema/fixture classpath roots, and the JSON-Pointer extraction
 * used by enum-vs-schema sync checks.
 */
final class VendorMappingSchemaFixture {

    static final String SCHEMA_CLASSPATH = "/vendor-mapping/schema/v1.json";
    static final String FIXTURES_ROOT = "/vendor-mapping/";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VendorMappingSchemaFixture() {}

    /** Load and parse the bundle JSON Schema (Draft 2020-12). */
    static JsonSchema loadSchema() throws IOException {
        JsonNode schemaNode = readClasspathJson(SCHEMA_CLASSPATH);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        return factory.getSchema(schemaNode);
    }

    /** Load the bundle JSON Schema as a raw {@link JsonNode} for structural inspection. */
    static JsonNode loadSchemaNode() throws IOException {
        return readClasspathJson(SCHEMA_CLASSPATH);
    }

    /** Read a fixture file under {@link #FIXTURES_ROOT} as a {@link JsonNode}. */
    static JsonNode readFixture(String fixtureFileName) throws IOException {
        return readClasspathJson(FIXTURES_ROOT + fixtureFileName);
    }

    /**
     * Resolve {@code pointer} against {@code root} and collect the resulting array's
     * text values into a {@link Set}.
     */
    static Set<String> textValuesAt(JsonNode root, String pointer) {
        JsonNode node = root.at(pointer);
        return StreamSupport.stream(node.spliterator(), false)
            .map(JsonNode::asText)
            .collect(Collectors.toSet());
    }

    private static JsonNode readClasspathJson(String classpathPath) throws IOException {
        try (InputStream in = VendorMappingSchemaFixture.class.getResourceAsStream(classpathPath)) {
            assertThat(in)
                .as("classpath resource %s present", classpathPath)
                .isNotNull();
            return MAPPER.readTree(in);
        }
    }
}
