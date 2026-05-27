package com.rapid7.integrationregistry.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class EnumSchemaSyncTest {

    private static final String SCHEMA_CLASSPATH = "/vendor-mapping/schema/v1.json";
    private static JsonNode schemaRoot;

    @BeforeAll
    static void loadSchema() throws IOException {
        try (InputStream in = EnumSchemaSyncTest.class.getResourceAsStream(SCHEMA_CLASSPATH)) {
            assertThat(in)
                .as("schema resource %s present on classpath", SCHEMA_CLASSPATH)
                .isNotNull();
            schemaRoot = new ObjectMapper().readTree(in);
        }
    }

    @Test
    void schemaCategoryEnum_shouldMatchVendorCategoryWireForms_whenInspected() {
        // Arrange
        Set<String> javaWireForms = Stream.of(VendorCategory.values())
            .map(VendorCategory::wireForm)
            .collect(Collectors.toSet());
        JsonNode schemaEnum = schemaRoot.at("/$defs/VendorService/properties/category/enum");

        // Act
        Set<String> schemaWireForms = StreamSupport.stream(schemaEnum.spliterator(), false)
            .map(JsonNode::asText)
            .collect(Collectors.toSet());

        // Assert
        assertThat(schemaWireForms)
            .as("schema category enum must match VendorCategory wire forms exactly")
            .isEqualTo(javaWireForms);
    }

    @Test
    void schemaSourceTypeEnum_shouldMatchSourceTypeWireForms_whenInspected() {
        // Arrange
        Set<String> javaWireForms = Stream.of(SourceType.values())
            .map(SourceType::wireForm)
            .collect(Collectors.toSet());
        JsonNode schemaEnum = schemaRoot.at("/$defs/DataSource/properties/source_type/enum");

        // Act
        Set<String> schemaWireForms = StreamSupport.stream(schemaEnum.spliterator(), false)
            .map(JsonNode::asText)
            .collect(Collectors.toSet());

        // Assert
        assertThat(schemaWireForms)
            .as("schema source_type enum must match SourceType wire forms exactly")
            .isEqualTo(javaWireForms);
    }

    @Test
    void schemaProductEnum_shouldMatchProductNameWireForms_whenInspected() {
        // Arrange
        Set<String> javaWireForms = Stream.of(ProductName.values())
            .map(ProductName::wireForm)
            .collect(Collectors.toSet());
        JsonNode schemaEnum = schemaRoot.at("/$defs/DataSource/properties/product/enum");

        // Act
        Set<String> schemaWireForms = StreamSupport.stream(schemaEnum.spliterator(), false)
            .map(JsonNode::asText)
            .collect(Collectors.toSet());

        // Assert
        assertThat(schemaWireForms)
            .as("schema product enum must match ProductName wire forms exactly")
            .isEqualTo(javaWireForms);
    }
}
