package com.rapid7.integrationregistry.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Parses a vendor-mapping bundle YAML document into an immutable
 * {@link VendorMappingSnapshot}. Stateless and framework-agnostic;
 * Plan 03 wires this into Spring with the S3 fetch and readiness gate.
 */
public final class BundleParser {

    private static final String SCHEMA_CLASSPATH = "/vendor-mapping/schema/v1.json";
    private static final String FIELD_YAML_STREAM = "yamlStream";

    private final ObjectMapper yamlMapper;
    private final JsonSchema schema;

    public BundleParser() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.schema = loadSchema();
    }

    /**
     * Parse, schema-validate, and index a bundle YAML document.
     *
     * @throws BundleParseException if the YAML is unparseable or the parsed
     *     document fails JSON Schema validation.
     * @throws NullPointerException if {@code yamlStream} is null
     */
    public VendorMappingSnapshot parse(InputStream yamlStream) throws BundleParseException {
        Objects.requireNonNull(yamlStream, FIELD_YAML_STREAM);
        JsonNode tree = parseYaml(yamlStream);
        Set<ValidationMessage> errors = schema.validate(tree);
        if (!errors.isEmpty()) {
            throw BundleParseException.schemaInvalid(errors);
        }
        return buildSnapshot(tree);
    }

    private JsonNode parseYaml(InputStream yamlStream) throws BundleParseException {
        try {
            return yamlMapper.readTree(yamlStream);
        } catch (IOException ex) {
            // Jackson throws JacksonException (a subclass of IOException) for
            // YAML syntax failures and IOException for upstream stream failures.
            // Both wrap into the same parse-failure shape; the original cause
            // (preserved via getCause()) carries the discriminating runtime type.
            throw BundleParseException.yamlSyntaxError(ex);
        }
    }

    // Allocating one VendorResolution and one TripletKey per data source IS the
    // contract here: the snapshot's index is keyed by triplet and valued by
    // resolution, so each entry must be minted fresh. Hoisting the allocations
    // out of the loop would defeat indexing.
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private VendorMappingSnapshot buildSnapshot(JsonNode tree) {
        String mappingVersion = tree.at("/metadata/mapping_version").asText();
        Map<Object, VendorResolution> index = new HashMap<>();
        for (JsonNode vendor : tree.at("/spec/vendors")) {
            String vendorId = vendor.get("id").asText();
            String vendorName = vendor.get("name").asText();
            JsonNode services = vendor.get("services");
            if (services == null) {
                continue;
            }
            for (JsonNode service : services) {
                String serviceId = service.get("id").asText();
                String serviceName = service.get("name").asText();
                VendorCategory category = requireEnum(
                    VendorCategory.fromWireForm(service.get("category").asText()),
                    "category", service.get("category").asText());
                JsonNode dataSources = service.get("data_sources");
                if (dataSources == null) {
                    continue;
                }
                for (JsonNode ds : dataSources) {
                    ProductName product = requireEnum(
                        ProductName.fromWireForm(ds.get("product").asText()),
                        "product", ds.get("product").asText());
                    SourceType sourceType = requireEnum(
                        SourceType.fromWireForm(ds.get("source_type").asText()),
                        "source_type", ds.get("source_type").asText());
                    String sourceValue = ds.get("source_value").asText();
                    VendorResolution resolution = new VendorResolution(
                        serviceId, serviceName, category, vendorId, vendorName);
                    index.put(
                        MapBackedVendorMappingSnapshot.key(product, sourceType, sourceValue),
                        resolution);
                }
            }
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map typedIndex = index;
        return new MapBackedVendorMappingSnapshot(typedIndex, mappingVersion);
    }

    private static <E extends Enum<E>> E requireEnum(Optional<E> resolved, String fieldName, String wireForm) {
        return resolved.orElseThrow(() -> new IllegalStateException(
            "Schema-validated bundle contained " + fieldName + "=" + wireForm
                + " which the Java enum does not recognize. "
                + "This is a schema/enum-sync defect — see EnumSchemaSyncTest."));
    }

    private static JsonSchema loadSchema() {
        try (InputStream in = BundleParser.class.getResourceAsStream(SCHEMA_CLASSPATH)) {
            if (in == null) {
                throw new IllegalStateException(
                    "Bundle schema resource missing from classpath: " + SCHEMA_CLASSPATH
                        + ". This is a packaging defect.");
            }
            JsonNode schemaNode = new ObjectMapper().readTree(in);
            return JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaNode);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load bundle schema from classpath", ex);
        }
    }
}
