package com.rapid7.integrationregistry.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EnumSchemaSyncTest {

    private static final String CATEGORY_ENUM_POINTER = "/$defs/VendorService/properties/category/enum";
    private static final String SOURCE_TYPE_ENUM_POINTER = "/$defs/DataSource/properties/source_type/enum";
    private static final String PRODUCT_ENUM_POINTER = "/$defs/DataSource/properties/product/enum";

    private static JsonNode schemaRoot;

    @BeforeAll
    static void loadSchema() throws IOException {
        schemaRoot = BundleSchemaResources.loadSchemaNode();
    }

    @Test
    void schemaCategoryEnum_shouldMatchVendorCategoryWireForms_whenInspected() {
        // Arrange
        Set<String> javaWireForms = Stream.of(VendorCategory.values())
            .map(VendorCategory::wireForm)
            .collect(Collectors.toSet());

        // Act
        Set<String> schemaWireForms = BundleSchemaResources.textValuesAt(schemaRoot, CATEGORY_ENUM_POINTER);

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

        // Act
        Set<String> schemaWireForms = BundleSchemaResources.textValuesAt(schemaRoot, SOURCE_TYPE_ENUM_POINTER);

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

        // Act
        Set<String> schemaWireForms = BundleSchemaResources.textValuesAt(schemaRoot, PRODUCT_ENUM_POINTER);

        // Assert
        assertThat(schemaWireForms)
            .as("schema product enum must match ProductName wire forms exactly")
            .isEqualTo(javaWireForms);
    }
}
