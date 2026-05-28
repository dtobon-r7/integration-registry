package com.rapid7.integrationregistry.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.rapid7.integrationregistry.mapping.exception.BundleParseException;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Parses a vendor-mapping bundle YAML document into an immutable
 * {@link VendorMappingSnapshot}. Stateless and framework-agnostic; the
 * runtime loader (Spring-wired in a downstream layer) supplies the input
 * stream and handles S3 fetch, disk caching, and the readiness gate.
 *
 * <h2>Threading</h2>
 *
 * <p>This parser holds two final fields configured in the constructor and is
 * intended for single-threaded use on the boot / refresh path that the
 * runtime loader owns. {@link com.fasterxml.jackson.databind.ObjectMapper}
 * is documented thread-safe once configured (which is the case here), but
 * {@link com.networknt.schema.JsonSchema#validate(com.fasterxml.jackson.databind.JsonNode)}
 * does not carry an explicit thread-safety guarantee in the
 * {@code com.networknt:json-schema-validator} 1.5.4 release notes. Callers
 * sharing a single {@code BundleParser} instance across request-handler
 * threads must verify the validator's concurrency posture before relying on
 * it; the boot/refresh use case avoids the question entirely.
 *
 * <h2>Input expectations</h2>
 *
 * <p>The {@link #parse(java.io.InputStream)} entry point reads the entire
 * stream into memory via Jackson's YAML factory. The bundle format (defined
 * by RFC-001 §Vendor mapping) is small by design — vendor / vendor-service /
 * data-source counts are bounded by the curated catalog, not by user input —
 * so the parser does not impose explicit size, depth, or alias caps. The
 * trust boundary is owned by the runtime loader's S3 fetch (the bundle is
 * curated in-repo and shipped via a CI publish pipeline; the loader is
 * responsible for any threat-model hardening such as SnakeYAML
 * {@code LoaderOptions} caps).
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
     * <p>Failures fall into three classes:
     *
     * <ul>
     *   <li><b>Bundle-data failures</b> — surface as
     *       {@link BundleParseException} (checked). YAML-syntax errors carry
     *       the underlying Jackson exception as their cause; schema-validation
     *       failures carry the validator's structured
     *       {@code Set<ValidationMessage>} via
     *       {@link BundleParseException#validationMessages()}.</li>
     *   <li><b>Schema/enum-sync drift</b> — propagates as
     *       {@link IllegalStateException} (unchecked) when a schema-validated
     *       wire form does not map through {@code fromWireForm()}. This is a
     *       build-time invariant violation that {@code EnumSchemaSyncTest} is
     *       the first line of defense against; it is NOT caught by
     *       {@code BundleParseException}. Callers that need to handle every
     *       parse failure (e.g. the readiness-gate loader) must catch it
     *       explicitly.</li>
     *   <li><b>Packaging defects</b> — propagate as
     *       {@link IllegalStateException} (unchecked) when the bundled JSON
     *       Schema resource is missing or unreadable from the classpath. Same
     *       caveat: not caught by {@code BundleParseException}.</li>
     * </ul>
     *
     * <p>An empty {@code services} array on a vendor (or an empty
     * {@code data_sources} array on a service) contributes zero index entries
     * to the resulting snapshot. The schema makes both arrays
     * {@code required}; the parser tolerates them as legal empty placeholders
     * for vendors / services staged across multiple bundle revisions.
     *
     * @throws BundleParseException if the YAML is unparseable or the parsed
     *     document fails JSON Schema validation.
     * @throws IllegalStateException if a schema/enum invariant is violated or
     *     the bundled schema resource is missing/unreadable. See the failure
     *     classes above.
     * @throws NullPointerException if {@code yamlStream} is null.
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
            // Single catch (not split JacksonException/IOException) — PMD
            // IdenticalCatchBranches. Cause carries the discriminating type.
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
        // Raw-type bridge: TripletKey is private to MapBackedVendorMappingSnapshot,
        // so the public-typed index above is Map<Object, ...>. Mirror site:
        // MapBackedVendorMappingSnapshotTest.construct(...). Replacing the index
        // implementation requires updating both call sites.
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
