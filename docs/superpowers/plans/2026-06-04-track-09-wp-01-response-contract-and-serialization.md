# Response Contract and Serialization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the controller-owned wire DTOs for all four Integration Registry read routes plus the shared envelope/error types, serializing byte-for-byte against the locked `openapi.json` contract.

**Architecture:** A new self-contained package `com.rapid7.integrationregistry.controller.dto` holds immutable Java records with compact-constructor validation. Per-DTO `@JsonNaming(SnakeCaseStrategy)` produces snake_case wire names; DTO-local enums (`HealthState`, `ErrorCode`, `UnavailableReason`) carry `@JsonValue` wire tokens. The package imports nothing from `aggregator`/`mapping`/`adapter`/`coordinator` (ArchUnit-enforced on production classes only). Tests are a Docker-free `@JsonTest` slice that validates serialized output against `openapi.json` component schemas (copied into test resources as a hermetic fixture) using `com.networknt:json-schema-validator`.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Jackson (jackson-datatype-jsr310 for `Instant`), JUnit 5, AssertJ, `com.networknt:json-schema-validator` 1.5.4, Maven wrapper. Quality gates: ArchUnit, PMD (`pmd-ruleset.xml`), Spotless / Google Java Format.

---

## Source of truth

- **Spec:** `docs/superpowers/specs/2026-06-04-track-09-wp-01-response-contract-and-serialization-design.md` — read it fully; it is authoritative for every type and decision.
- **Wire contract:** `engagements/unified-integrations-view/decisions/rfc/openapi.json` — lives in the **engagement repo, outside this service worktree**. Task 1 copies the relevant component schemas into `src/test/resources/contract/openapi.json` as a hermetic test fixture with recorded provenance.

## Conventions to mirror (read before starting)

- `src/main/java/com/rapid7/integrationregistry/aggregator/projection/*.java` — record style: compact-constructor `Objects.requireNonNull` with `FIELD_*` `String` constants, `List.copyOf` defensive copies, `count == list.size()` invariants, `@SuppressWarnings("PMD.ExcessiveParameterList")` with a one-line justification on wide records.
- `src/test/java/com/rapid7/integrationregistry/aggregator/projection/ProjectionRecordsTest.java` — `@Nested` per-record test classes, `assertNpeFromCtor` helper asserting the NPE message equals the `FIELD_*` constant.
- `src/main/java/com/rapid7/integrationregistry/mapping/VendorCategory.java` — the `@JsonValue`-style `wireForm()` enum pattern (DTO enums adapt this with a Jackson `@JsonValue` annotation).

## Critical constraints

1. **ArchUnit scans production classes only** (`@AnalyzeClasses(... ImportOption.DoNotIncludeTests.class)` in `LayerDependencyRulesTest`). The production `controller.dto` package MUST NOT import `..aggregator..`, `..mapping..`, `..adapter..`, `..coordinator..`. Test classes are exempt from the rule, but keep them clean anyway.
2. **No global Jackson `@Configuration` bean.** snake_case is per-DTO via `@JsonNaming`. Default Jackson inclusion (`ALWAYS`) renders nulls explicitly; only `stale_since` gets `@JsonInclude(NON_NULL)`.
3. **`IntegrationDto` has NO `data_source_id`.** `VendorServiceCardNestedDto` omits `vendor_id`/`vendor_name`. `last_updated` is nullable (explicit null). `vendor_category`/`integration_type`/`product_name` are plain `String`.
4. **Verification each checkpoint:** `./mvnw spotless:apply` then `./mvnw verify`. GJF is authoritative — never hand-format. The DTO tests are Docker-free.

## File structure

| File | Responsibility |
|---|---|
| `controller/dto/package-info.java` (modify) | Document the package as the wire surface |
| `controller/dto/HealthState.java` | Enum, `@JsonValue` health tokens |
| `controller/dto/ErrorCode.java` | Enum, `@JsonValue` error codes |
| `controller/dto/UnavailableReason.java` | Enum, `@JsonValue` unavailable reasons |
| `controller/dto/IntegrationTypeCountDto.java` | Per-type counts |
| `controller/dto/IntegrationDto.java` | Per-instance row (no `data_source_id`) |
| `controller/dto/DataSourceDto.java` | Data-source block with nested integrations |
| `controller/dto/UnavailableProductDto.java` | Partial-failure record (`stale_since` omit) |
| `controller/dto/ResponseMetadataDto.java` | Envelope metadata |
| `controller/dto/ErrorEnvelopeDto.java` | Error envelope + nested `ErrorBody` |
| `controller/dto/VendorListEntryDto.java` | Lightweight vendor row |
| `controller/dto/VendorServiceCardDto.java` | Flat card (10 fields) |
| `controller/dto/VendorServiceCardNestedDto.java` | Nested card (8 fields) |
| `controller/dto/VendorServicesResponse.java` | `/vendor-services` body |
| `controller/dto/VendorServiceDetailResponse.java` | `/vendor-services/{id}` body |
| `controller/dto/VendorsResponse.java` | `/vendors` body |
| `controller/dto/VendorDetailResponse.java` | `/vendors/{id}` body |
| `test/.../controller/dto/OpenApiSchemas.java` | Test helper: load/cache schemas, validate |
| `test/.../controller/dto/*Test.java` | `@JsonTest` serialization + validation tests |
| `test/resources/contract/openapi.json` | Hermetic copy of the wire contract |
| `aggregator/projection/package-info.java` (modify) | Reword: internal source-of-values |
| `engagements/.../ADR-003-...md` (modify) | Append 2026-06-04 amendment note |

---

## Task 1: Schema fixture + OpenApiSchemas test helper

Establishes the hermetic contract fixture and the validation helper all later tests use. No production code yet — this is test infrastructure, verified by a self-test against a known-good and known-bad node.

**Files:**
- Create: `src/test/resources/contract/openapi.json` (copied from the engagement repo)
- Create: `src/test/resources/contract/PROVENANCE.md`
- Create: `src/test/java/com/rapid7/integrationregistry/controller/dto/OpenApiSchemas.java`
- Create: `src/test/java/com/rapid7/integrationregistry/controller/dto/OpenApiSchemasTest.java`

- [ ] **Step 1: Copy the locked contract into test resources**

Run:
```bash
mkdir -p src/test/resources/contract
cp ../../../../../../engagements/unified-integrations-view/decisions/rfc/openapi.json \
   src/test/resources/contract/openapi.json
```
If that relative path does not resolve from the worktree, locate the file and copy it:
```bash
find / -path '*unified-integrations-view/decisions/rfc/openapi.json' 2>/dev/null | head -1
```
Expected: `src/test/resources/contract/openapi.json` exists and is ~29 KB. Verify it begins with `{ "openapi": "3.1.0"` :
```bash
head -c 60 src/test/resources/contract/openapi.json
```

- [ ] **Step 2: Record provenance**

Create `src/test/resources/contract/PROVENANCE.md`:
```markdown
# Contract fixture provenance

`openapi.json` is a verbatim copy of the locked Read API contract from the
engagement repo:

    engagements/unified-integrations-view/decisions/rfc/openapi.json

Copied 2026-06-04 for Track 09 / Work Plan 01 (response contract and
serialization). This service repo is self-contained; the test suite validates
DTO serialization against this fixture rather than reaching outside the repo.

When the engagement contract changes, re-copy this file and re-run
`./mvnw test -Dtest='*Dto*Test,*ResponseTest'`.
```

- [ ] **Step 3: Write the failing test for the helper**

Create `OpenApiSchemasTest.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenApiSchemasTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void validate_shouldReturnNoMessages_whenNodeMatchesSchema() throws Exception {
    // IntegrationTypeCount is a small closed schema in the contract.
    JsonNode valid =
        mapper.readTree("{\"integration_type\":\"SIEM Event Source\",\"total\":4,\"error_count\":1}");

    assertThat(OpenApiSchemas.validate("IntegrationTypeCount", valid)).isEmpty();
  }

  @Test
  void validate_shouldReturnMessages_whenRequiredFieldMissing() throws Exception {
    JsonNode invalid = mapper.readTree("{\"total\":4,\"error_count\":1}"); // missing integration_type

    assertThat(OpenApiSchemas.validate("IntegrationTypeCount", invalid)).isNotEmpty();
  }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=OpenApiSchemasTest`
Expected: FAIL — `OpenApiSchemas` does not exist (compilation error).

- [ ] **Step 5: Implement OpenApiSchemas**

Create `OpenApiSchemas.java`. It loads the contract once, exposes each `#/components/schemas/<Name>` as a validatable schema with `$ref` resolution against the same document, and returns the validation message set.
```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test helper: loads the locked {@code openapi.json} contract fixture once and validates JSON
 * nodes against individual {@code #/components/schemas/<Name>} component schemas, with {@code
 * $ref} resolution against the same document. Used by the DTO serialization tests to prove wire
 * conformance.
 */
final class OpenApiSchemas {

  private static final String CONTRACT = "/contract/openapi.json";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNode DOCUMENT = load();
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(VersionFlag.V202012);
  private static final ConcurrentHashMap<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

  private OpenApiSchemas() {}

  private static JsonNode load() {
    try (InputStream in = OpenApiSchemas.class.getResourceAsStream(CONTRACT)) {
      if (in == null) {
        throw new IllegalStateException("Contract fixture not found on classpath: " + CONTRACT);
      }
      return MAPPER.readTree(in);
    } catch (java.io.IOException e) {
      throw new UncheckedIOException("Failed to load contract fixture", e);
    }
  }

  /** Validates {@code node} against the named component schema; empty set means conformant. */
  static Set<ValidationMessage> validate(String schemaName, JsonNode node) {
    JsonSchema schema =
        CACHE.computeIfAbsent(schemaName, OpenApiSchemas::buildSchema);
    return schema.validate(node);
  }

  private static JsonSchema buildSchema(String schemaName) {
    JsonNode schemaNode = DOCUMENT.at("/components/schemas/" + schemaName);
    if (schemaNode.isMissingNode()) {
      throw new IllegalArgumentException("No component schema named: " + schemaName);
    }
    // Register the whole document so intra-document $ref (#/components/schemas/...) resolves.
    SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
    return FACTORY.getSchema(
        SchemaLocation.of("classpath:" + CONTRACT + "#/components/schemas/" + schemaName),
        config);
  }
}
```

> **Implementation note (read before coding Step 5):** The exact `networknt`
> 1.5.4 API for loading a sub-schema by `SchemaLocation` with `$ref` resolution
> can vary. If `getSchema(SchemaLocation, config)` does not resolve
> intra-document `$ref`s cleanly, the proven fallback is: register the full
> document via a `JsonSchemaFactory` configured with a `SchemaLoader` that maps
> the contract URI to `DOCUMENT`, then fetch the fragment. Keep the **public
> shape** of the helper exactly as the test expects: `static
> Set<ValidationMessage> validate(String schemaName, JsonNode node)`. The two
> self-tests in Step 3 are the contract for this helper — make them pass without
> changing their assertions.

- [ ] **Step 6: Run the helper self-tests to verify they pass**

Run: `./mvnw -q test -Dtest=OpenApiSchemasTest`
Expected: PASS (both tests). If the schema-loading API fights you, apply the fallback in the implementation note until both self-tests pass.

- [ ] **Step 7: Format and commit**

```bash
./mvnw -q spotless:apply
git add src/test/resources/contract src/test/java/com/rapid7/integrationregistry/controller/dto/OpenApiSchemas.java src/test/java/com/rapid7/integrationregistry/controller/dto/OpenApiSchemasTest.java
git commit -m "test(dto): contract fixture + OpenApiSchemas validation helper"
```

---

## Task 2: DTO enums (HealthState, ErrorCode, UnavailableReason)

Three net-new DTO-local enums with `@JsonValue` wire tokens. Self-contained — no reuse of `IntegrationStatus`/`VendorCategory`.

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/controller/dto/HealthState.java`
- Create: `src/main/java/com/rapid7/integrationregistry/controller/dto/ErrorCode.java`
- Create: `src/main/java/com/rapid7/integrationregistry/controller/dto/UnavailableReason.java`
- Test: `src/test/java/com/rapid7/integrationregistry/controller/dto/DtoEnumTest.java`

- [ ] **Step 1: Write the failing test**

Create `DtoEnumTest.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DtoEnumTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void healthState_shouldSerializeToWireTokens() throws Exception {
    assertThat(mapper.writeValueAsString(HealthState.HEALTHY)).isEqualTo("\"healthy\"");
    assertThat(mapper.writeValueAsString(HealthState.MISSING_DATA)).isEqualTo("\"missing_data\"");
    assertThat(mapper.writeValueAsString(HealthState.DISABLED)).isEqualTo("\"disabled\"");
  }

  @Test
  void errorCode_shouldSerializeToWireTokens() throws Exception {
    assertThat(mapper.writeValueAsString(ErrorCode.UNAUTHENTICATED)).isEqualTo("\"UNAUTHENTICATED\"");
    assertThat(mapper.writeValueAsString(ErrorCode.NOT_FOUND)).isEqualTo("\"NOT_FOUND\"");
    assertThat(mapper.writeValueAsString(ErrorCode.INTERNAL)).isEqualTo("\"INTERNAL\"");
  }

  @Test
  void unavailableReason_shouldSerializeToWireTokens() throws Exception {
    assertThat(mapper.writeValueAsString(UnavailableReason.TIMEOUT)).isEqualTo("\"timeout\"");
    assertThat(mapper.writeValueAsString(UnavailableReason.UPSTREAM_5XX)).isEqualTo("\"upstream_5xx\"");
    assertThat(mapper.writeValueAsString(UnavailableReason.AUTH_FAILURE)).isEqualTo("\"auth_failure\"");
    assertThat(mapper.writeValueAsString(UnavailableReason.NO_DATA)).isEqualTo("\"no_data\"");
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=DtoEnumTest`
Expected: FAIL — enums do not exist.

- [ ] **Step 3: Implement the three enums**

`HealthState.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Wire health state per openapi.json HealthState. Self-contained DTO enum (RFC §Spring layer
 * boundaries forbids the controller layer importing adapter.IntegrationStatus). */
public enum HealthState {
  HEALTHY("healthy"),
  WARNING("warning"),
  ERROR("error"),
  MISSING_DATA("missing_data"),
  DISABLED("disabled");

  private final String wire;

  HealthState(String wire) {
    this.wire = wire;
  }

  @JsonValue
  public String wire() {
    return wire;
  }
}
```

`ErrorCode.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Wire error code per openapi.json ErrorCode. */
public enum ErrorCode {
  UNAUTHENTICATED("UNAUTHENTICATED"),
  NOT_FOUND("NOT_FOUND"),
  INTERNAL("INTERNAL");

  private final String wire;

  ErrorCode(String wire) {
    this.wire = wire;
  }

  @JsonValue
  public String wire() {
    return wire;
  }
}
```

`UnavailableReason.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Wire unavailable-reason per openapi.json UnavailableReason. */
public enum UnavailableReason {
  TIMEOUT("timeout"),
  UPSTREAM_5XX("upstream_5xx"),
  AUTH_FAILURE("auth_failure"),
  NO_DATA("no_data");

  private final String wire;

  UnavailableReason(String wire) {
    this.wire = wire;
  }

  @JsonValue
  public String wire() {
    return wire;
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./mvnw -q test -Dtest=DtoEnumTest`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/rapid7/integrationregistry/controller/dto/HealthState.java src/main/java/com/rapid7/integrationregistry/controller/dto/ErrorCode.java src/main/java/com/rapid7/integrationregistry/controller/dto/UnavailableReason.java src/test/java/com/rapid7/integrationregistry/controller/dto/DtoEnumTest.java
git commit -m "feat(dto): wire enums HealthState, ErrorCode, UnavailableReason"
```

---

## Task 3: Leaf supporting DTOs (IntegrationTypeCountDto, ResponseMetadataDto, ErrorEnvelopeDto)

Three independent leaf records with validation and schema conformance. `ErrorEnvelopeDto` carries a nested `ErrorBody`.

**Files:**
- Create: `controller/dto/IntegrationTypeCountDto.java`
- Create: `controller/dto/ResponseMetadataDto.java`
- Create: `controller/dto/ErrorEnvelopeDto.java`
- Test: `controller/dto/SupportingTypesSerializationTest.java`

- [ ] **Step 1: Write the failing test**

Create `SupportingTypesSerializationTest.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class SupportingTypesSerializationTest {

  @Autowired private JacksonTester<IntegrationTypeCountDto> typeCount;
  @Autowired private JacksonTester<ResponseMetadataDto> metadata;
  @Autowired private JacksonTester<ErrorEnvelopeDto> error;

  @Test
  void integrationTypeCount_shouldMatchContractSchema() throws Exception {
    var json = typeCount.write(new IntegrationTypeCountDto("SIEM Event Source", 4, 1)).getJson();
    assertThat(json).contains("\"integration_type\":\"SIEM Event Source\"");
    assertThat(json).contains("\"error_count\":1");
    assertThat(OpenApiSchemas.validate("IntegrationTypeCount", new com.fasterxml.jackson.databind.ObjectMapper().readTree(json))).isEmpty();
  }

  @Test
  void responseMetadata_shouldMatchContractSchema() throws Exception {
    var dto = new ResponseMetadataDto(true, Instant.parse("2026-04-23T10:00:00Z"), "v1.42.0");
    var json = metadata.write(dto).getJson();
    assertThat(json).contains("\"cache_hit\":true");
    assertThat(json).contains("\"as_of\":\"2026-04-23T10:00:00Z\"");
    assertThat(json).contains("\"mapping_version\":\"v1.42.0\"");
    assertThat(OpenApiSchemas.validate("ResponseMetadata", new com.fasterxml.jackson.databind.ObjectMapper().readTree(json))).isEmpty();
  }

  @Test
  void errorEnvelope_shouldMatchContractSchema() throws Exception {
    var dto = new ErrorEnvelopeDto(new ErrorEnvelopeDto.ErrorBody(ErrorCode.NOT_FOUND, "Vendor service not found in this org"));
    var json = error.write(dto).getJson();
    assertThat(json).contains("\"error\":{");
    assertThat(json).contains("\"code\":\"NOT_FOUND\"");
    assertThat(OpenApiSchemas.validate("ErrorEnvelope", new com.fasterxml.jackson.databind.ObjectMapper().readTree(json))).isEmpty();
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=SupportingTypesSerializationTest`
Expected: FAIL — DTO types do not exist.

- [ ] **Step 3: Implement the three records**

`IntegrationTypeCountDto.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

/** Wire per-type counts per openapi.json IntegrationTypeCount. {@code integrationType} is a plain
 * String (bundle/value-driven; see spec §vendor_category mismatch). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntegrationTypeCountDto(String integrationType, int total, int errorCount) {

  static final String FIELD_INTEGRATION_TYPE = "integrationType";
  static final String FIELD_TOTAL = "total";
  static final String FIELD_ERROR_COUNT = "errorCount";

  public IntegrationTypeCountDto {
    Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
    if (total < 0) {
      throw new IllegalArgumentException(FIELD_TOTAL + " must be >= 0: " + total);
    }
    if (errorCount < 0) {
      throw new IllegalArgumentException(FIELD_ERROR_COUNT + " must be >= 0: " + errorCount);
    }
    if (errorCount > total) {
      throw new IllegalArgumentException(
          FIELD_ERROR_COUNT + " (" + errorCount + ") must be <= " + FIELD_TOTAL + " (" + total + ")");
    }
  }
}
```

`ResponseMetadataDto.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.Objects;

/** Wire envelope metadata per openapi.json ResponseMetadata. All three fields required. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ResponseMetadataDto(boolean cacheHit, Instant asOf, String mappingVersion) {

  static final String FIELD_AS_OF = "asOf";
  static final String FIELD_MAPPING_VERSION = "mappingVersion";

  public ResponseMetadataDto {
    Objects.requireNonNull(asOf, FIELD_AS_OF);
    Objects.requireNonNull(mappingVersion, FIELD_MAPPING_VERSION);
  }
}
```

`ErrorEnvelopeDto.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

/** Wire error envelope per openapi.json ErrorEnvelope: {@code {"error":{"code","message"}}}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ErrorEnvelopeDto(ErrorBody error) {

  static final String FIELD_ERROR = "error";

  public ErrorEnvelopeDto {
    Objects.requireNonNull(error, FIELD_ERROR);
  }

  /** Nested {@code error} object. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ErrorBody(ErrorCode code, String message) {

    static final String FIELD_CODE = "code";
    static final String FIELD_MESSAGE = "message";

    public ErrorBody {
      Objects.requireNonNull(code, FIELD_CODE);
      Objects.requireNonNull(message, FIELD_MESSAGE);
    }
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./mvnw -q test -Dtest=SupportingTypesSerializationTest`
Expected: PASS. If `json-schema-validator` rejects an OpenAPI-3.1 keyword in `ResponseMetadata` (it uses `allOf` over `$ref` Iso8601), apply the spec's JSONAssert fallback for that one assertion: replace the `OpenApiSchemas.validate(...)` line with explicit field assertions already present, and add a comment citing the fallback. Prefer schema validation; only fall back if the validator genuinely cannot parse.

- [ ] **Step 5: Add constructor-validation tests**

Append to `SupportingTypesSerializationTest.java` is not appropriate (that is `@JsonTest`). Instead create a plain unit test `SupportingTypesValidationTest.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SupportingTypesValidationTest {

  @Test
  void integrationTypeCount_shouldThrowNpe_whenTypeNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> new IntegrationTypeCountDto(null, 1, 0))
        .withMessage(IntegrationTypeCountDto.FIELD_INTEGRATION_TYPE);
  }

  @Test
  void integrationTypeCount_shouldThrowIae_whenErrorCountExceedsTotal() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new IntegrationTypeCountDto("SIEM Event Source", 2, 3))
        .withMessageContaining(IntegrationTypeCountDto.FIELD_ERROR_COUNT);
  }

  @Test
  void responseMetadata_shouldThrowNpe_whenAsOfNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ResponseMetadataDto(true, null, "v1"))
        .withMessage(ResponseMetadataDto.FIELD_AS_OF);
  }

  @Test
  void errorBody_shouldThrowNpe_whenCodeNull() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ErrorEnvelopeDto.ErrorBody(null, "msg"))
        .withMessage(ErrorEnvelopeDto.ErrorBody.FIELD_CODE);
  }
}
```

- [ ] **Step 6: Run both tests**

Run: `./mvnw -q test -Dtest=SupportingTypesSerializationTest,SupportingTypesValidationTest`
Expected: PASS.

- [ ] **Step 7: Format and commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/rapid7/integrationregistry/controller/dto/IntegrationTypeCountDto.java src/main/java/com/rapid7/integrationregistry/controller/dto/ResponseMetadataDto.java src/main/java/com/rapid7/integrationregistry/controller/dto/ErrorEnvelopeDto.java src/test/java/com/rapid7/integrationregistry/controller/dto/SupportingTypesSerializationTest.java src/test/java/com/rapid7/integrationregistry/controller/dto/SupportingTypesValidationTest.java
git commit -m "feat(dto): IntegrationTypeCountDto, ResponseMetadataDto, ErrorEnvelopeDto"
```

---

## Task 4: UnavailableProductDto (the stale_since omit rule)

The single field in the whole plan that uses `@JsonInclude(NON_NULL)`. This task pins the present-vs-absent behavior precisely (acceptance signal 3).

**Files:**
- Create: `controller/dto/UnavailableProductDto.java`
- Test: `controller/dto/UnavailableProductSerializationTest.java`

- [ ] **Step 1: Write the failing test**

Create `UnavailableProductSerializationTest.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class UnavailableProductSerializationTest {

  @Autowired private JacksonTester<UnavailableProductDto> tester;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void staleSince_shouldBePresent_whenStaleTrue() throws Exception {
    var dto =
        new UnavailableProductDto(
            "InsightIDR", true, UnavailableReason.TIMEOUT, Instant.parse("2026-04-23T09:00:00Z"));
    var json = tester.write(dto).getJson();
    assertThat(json).contains("\"stale\":true");
    assertThat(json).contains("\"stale_since\":\"2026-04-23T09:00:00Z\"");
    assertThat(OpenApiSchemas.validate("UnavailableProduct", mapper.readTree(json))).isEmpty();
  }

  @Test
  void staleSince_shouldBeAbsent_whenNull() throws Exception {
    var dto = new UnavailableProductDto("InsightIDR", false, UnavailableReason.UPSTREAM_5XX, null);
    var json = tester.write(dto).getJson();
    assertThat(json).contains("\"stale\":false");
    assertThat(json).doesNotContain("stale_since");
    assertThat(OpenApiSchemas.validate("UnavailableProduct", mapper.readTree(json))).isEmpty();
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=UnavailableProductSerializationTest`
Expected: FAIL — `UnavailableProductDto` does not exist.

- [ ] **Step 3: Implement UnavailableProductDto**

```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.Objects;

/** Wire partial-failure record per openapi.json UnavailableProduct. {@code staleSince} is the only
 * DTO field that omits when null ({@code stale_since} present only when {@code stale=true}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UnavailableProductDto(
    String productName,
    boolean stale,
    UnavailableReason reason,
    @JsonInclude(JsonInclude.Include.NON_NULL) Instant staleSince) {

  static final String FIELD_PRODUCT_NAME = "productName";
  static final String FIELD_REASON = "reason";

  public UnavailableProductDto {
    Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
    Objects.requireNonNull(reason, FIELD_REASON);
  }
}
```

> **Note:** `@JsonInclude` on a record component applies to the generated
> accessor/property. If the annotation does not take effect on the component in
> this Jackson version, move it to the accessor by adding an explicit compact
> form is not possible on records — instead annotate at the type level is wrong
> (it would hide nulls everywhere). The correct fallback is a custom getter:
> declare the component normally and add
> `@JsonInclude(JsonInclude.Include.NON_NULL) public Instant staleSince() { return staleSince; }`
> Verify with the Step 2 test (`doesNotContain("stale_since")`) before moving on.

- [ ] **Step 4: Run it to verify it passes**

Run: `./mvnw -q test -Dtest=UnavailableProductSerializationTest`
Expected: PASS (both present and absent cases). If the absent case fails, apply the custom-getter fallback in the note above.

- [ ] **Step 5: Format and commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/rapid7/integrationregistry/controller/dto/UnavailableProductDto.java src/test/java/com/rapid7/integrationregistry/controller/dto/UnavailableProductSerializationTest.java
git commit -m "feat(dto): UnavailableProductDto with stale_since omit-when-null"
```

---

## Task 5: IntegrationDto + DataSourceDto (nested, no internal fields)

`IntegrationDto` proves internal-only field suppression (no `data_source_id`) and explicit-null nullable fields. `DataSourceDto` nests it with the count invariant.

**Files:**
- Create: `controller/dto/IntegrationDto.java`
- Create: `controller/dto/DataSourceDto.java`
- Test: `controller/dto/DataSourceSerializationTest.java`

- [ ] **Step 1: Write the failing test**

Create `DataSourceSerializationTest.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class DataSourceSerializationTest {

  @Autowired private JacksonTester<IntegrationDto> integration;
  @Autowired private JacksonTester<DataSourceDto> dataSource;
  private final ObjectMapper mapper = new ObjectMapper();

  private IntegrationDto healthyInstance() {
    return new IntegrationDto(
        "es_1234", "DC1-Defender", HealthState.HEALTHY,
        Instant.parse("2026-04-23T09:00:00Z"), "https://idr.example/eventsources/es_1234");
  }

  @Test
  void integration_shouldOmitInternalFields_andMatchSchema() throws Exception {
    var json = integration.write(healthyInstance()).getJson();
    assertThat(json).contains("\"integration_id\":\"es_1234\"");
    assertThat(json).doesNotContain("data_source_id");
    assertThat(json).doesNotContain("source_type");
    assertThat(json).doesNotContain("source_value");
    assertThat(json).doesNotContain("customer_account_id");
    assertThat(OpenApiSchemas.validate("Integration", mapper.readTree(json))).isEmpty();
  }

  @Test
  void integration_shouldRenderNullableFieldsAsExplicitNull() throws Exception {
    var dto =
        new IntegrationDto("c_456", null, HealthState.HEALTHY, null,
            "https://automation.example/connections/c_456");
    var json = integration.write(dto).getJson();
    assertThat(json).contains("\"integration_label\":null");
    assertThat(json).contains("\"last_success_timestamp\":null");
    assertThat(OpenApiSchemas.validate("Integration", mapper.readTree(json))).isEmpty();
  }

  @Test
  void dataSource_shouldNestIntegrations_andMatchSchema() throws Exception {
    var dto =
        new DataSourceDto(
            "insightidr|product_type|microsoft-defender-endpoint",
            "Microsoft Defender for Endpoint", "SIEM Event Source", "InsightIDR",
            HealthState.HEALTHY, 1, List.of(healthyInstance()));
    var json = dataSource.write(dto).getJson();
    assertThat(json).contains("\"data_source_id\":\"insightidr|product_type|microsoft-defender-endpoint\"");
    assertThat(json).contains("\"integrations_count\":1");
    assertThat(json).contains("\"integrations\":[");
    assertThat(OpenApiSchemas.validate("DataSource", mapper.readTree(json))).isEmpty();
  }

  @Test
  void dataSource_shouldThrowIae_whenCountDoesNotMatchListSize() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new DataSourceDto("id", "name", "SIEM Event Source", "InsightIDR",
                    HealthState.HEALTHY, 5, List.of(healthyInstance())))
        .withMessageContaining(DataSourceDto.FIELD_INTEGRATIONS_COUNT);
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=DataSourceSerializationTest`
Expected: FAIL — DTOs do not exist.

- [ ] **Step 3: Implement IntegrationDto**

```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.Objects;

/** Wire per-instance row per openapi.json Integration. Deliberately carries NO {@code
 * data_source_id} — that internal FK lives only on the aggregator projection record, not the wire.
 * {@code integrationLabel} and {@code lastSuccessTimestamp} are nullable and render as explicit
 * JSON null. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntegrationDto(
    String integrationId,
    String integrationLabel,
    HealthState status,
    Instant lastSuccessTimestamp,
    String configurationUrl) {

  static final String FIELD_INTEGRATION_ID = "integrationId";
  static final String FIELD_STATUS = "status";
  static final String FIELD_CONFIGURATION_URL = "configurationUrl";

  public IntegrationDto {
    Objects.requireNonNull(integrationId, FIELD_INTEGRATION_ID);
    Objects.requireNonNull(status, FIELD_STATUS);
    Objects.requireNonNull(configurationUrl, FIELD_CONFIGURATION_URL);
  }
}
```

- [ ] **Step 4: Implement DataSourceDto**

```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Objects;

/** Wire data-source block per openapi.json DataSource. {@code integrationType} and {@code
 * productName} are plain String (bundle/value-driven). Enforces {@code integrationsCount ==
 * integrations.size()}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DataSourceDto(
    String dataSourceId,
    String displayName,
    String integrationType,
    String productName,
    HealthState status,
    int integrationsCount,
    List<IntegrationDto> integrations) {

  static final String FIELD_DATA_SOURCE_ID = "dataSourceId";
  static final String FIELD_DISPLAY_NAME = "displayName";
  static final String FIELD_INTEGRATION_TYPE = "integrationType";
  static final String FIELD_PRODUCT_NAME = "productName";
  static final String FIELD_STATUS = "status";
  static final String FIELD_INTEGRATIONS_COUNT = "integrationsCount";
  static final String FIELD_INTEGRATIONS = "integrations";

  public DataSourceDto {
    Objects.requireNonNull(dataSourceId, FIELD_DATA_SOURCE_ID);
    Objects.requireNonNull(displayName, FIELD_DISPLAY_NAME);
    Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
    Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
    Objects.requireNonNull(status, FIELD_STATUS);
    Objects.requireNonNull(integrations, FIELD_INTEGRATIONS);
    if (integrationsCount < 0) {
      throw new IllegalArgumentException(
          FIELD_INTEGRATIONS_COUNT + " must be >= 0: " + integrationsCount);
    }
    if (integrationsCount != integrations.size()) {
      throw new IllegalArgumentException(
          FIELD_INTEGRATIONS_COUNT + " (" + integrationsCount + ") must equal "
              + FIELD_INTEGRATIONS + ".size() (" + integrations.size() + ")");
    }
    integrations = List.copyOf(integrations);
  }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./mvnw -q test -Dtest=DataSourceSerializationTest`
Expected: PASS (all four).

- [ ] **Step 6: Format and commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/rapid7/integrationregistry/controller/dto/IntegrationDto.java src/main/java/com/rapid7/integrationregistry/controller/dto/DataSourceDto.java src/test/java/com/rapid7/integrationregistry/controller/dto/DataSourceSerializationTest.java
git commit -m "feat(dto): IntegrationDto (no data_source_id) + DataSourceDto"
```

---

## Task 6: Vendor card DTOs + VendorListEntryDto (flat vs nested distinction)

The flat card includes `vendor_id`/`vendor_name`; the nested card omits them (acceptance signal 5). PMD `ExcessiveParameterList` fires on the 10-field flat card — suppress locally.

**Files:**
- Create: `controller/dto/VendorListEntryDto.java`
- Create: `controller/dto/VendorServiceCardDto.java`
- Create: `controller/dto/VendorServiceCardNestedDto.java`
- Test: `controller/dto/VendorCardSerializationTest.java`

- [ ] **Step 1: Write the failing test**

Create `VendorCardSerializationTest.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class VendorCardSerializationTest {

  @Autowired private JacksonTester<VendorListEntryDto> listEntry;
  @Autowired private JacksonTester<VendorServiceCardDto> flat;
  @Autowired private JacksonTester<VendorServiceCardNestedDto> nested;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void vendorListEntry_shouldMatchSchema() throws Exception {
    var json = listEntry.write(new VendorListEntryDto("microsoft", "Microsoft", 2)).getJson();
    assertThat(json).contains("\"vendor_id\":\"microsoft\"");
    assertThat(json).contains("\"vendor_services_count\":2");
    assertThat(OpenApiSchemas.validate("VendorListEntry", mapper.readTree(json))).isEmpty();
  }

  @Test
  void flatCard_shouldIncludeVendorFields_andMatchSchema() throws Exception {
    var dto =
        new VendorServiceCardDto(
            "microsoft-defender", "Microsoft Defender", "microsoft", "Microsoft", "edr", 4,
            List.of(new IntegrationTypeCountDto("SIEM Event Source", 4, 1)),
            List.of("InsightIDR"), HealthState.ERROR, Instant.parse("2026-04-22T14:30:00Z"));
    var json = flat.write(dto).getJson();
    assertThat(json).contains("\"vendor_id\":\"microsoft\"");
    assertThat(json).contains("\"vendor_name\":\"Microsoft\"");
    assertThat(json).contains("\"vendor_category\":\"edr\"");
    assertThat(OpenApiSchemas.validate("VendorServiceCard", mapper.readTree(json))).isEmpty();
  }

  @Test
  void nestedCard_shouldOmitVendorFields_andMatchSchema() throws Exception {
    var dto =
        new VendorServiceCardNestedDto(
            "microsoft-defender", "Microsoft Defender", "edr", 4,
            List.of(new IntegrationTypeCountDto("SIEM Event Source", 4, 1)),
            List.of("InsightIDR"), HealthState.ERROR, Instant.parse("2026-04-22T14:30:00Z"));
    var json = nested.write(dto).getJson();
    assertThat(json).doesNotContain("vendor_id");
    assertThat(json).doesNotContain("vendor_name");
    assertThat(json).contains("\"vendor_service_id\":\"microsoft-defender\"");
    assertThat(OpenApiSchemas.validate("VendorServiceCardNested", mapper.readTree(json))).isEmpty();
  }

  @Test
  void flatCard_shouldRenderNullLastUpdatedAsExplicitNull() throws Exception {
    var dto =
        new VendorServiceCardDto(
            "jira", "Jira", "atlassian", "Atlassian", "itsm", 0,
            List.of(), List.of(), HealthState.HEALTHY, null);
    var json = flat.write(dto).getJson();
    assertThat(json).contains("\"last_updated\":null");
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=VendorCardSerializationTest`
Expected: FAIL — DTOs do not exist.

- [ ] **Step 3: Implement VendorListEntryDto**

```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

/** Wire lightweight vendor row per openapi.json VendorListEntry ({@code GET /vendors}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorListEntryDto(String vendorId, String vendorName, int vendorServicesCount) {

  static final String FIELD_VENDOR_ID = "vendorId";
  static final String FIELD_VENDOR_NAME = "vendorName";
  static final String FIELD_VENDOR_SERVICES_COUNT = "vendorServicesCount";

  public VendorListEntryDto {
    Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
    Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    if (vendorServicesCount < 0) {
      throw new IllegalArgumentException(
          FIELD_VENDOR_SERVICES_COUNT + " must be >= 0: " + vendorServicesCount);
    }
  }
}
```

- [ ] **Step 4: Implement VendorServiceCardDto (flat, 10 fields, PMD suppression)**

```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Wire flat vendor-service card per openapi.json VendorServiceCard ({@code GET /vendor-services}).
 * Embeds {@code vendorId}/{@code vendorName} so the UI renders the vendor filter chip without a
 * lookup. {@code vendorCategory} is a plain String (bundle/value-driven). {@code lastUpdated} is
 * nullable (explicit null). */
@SuppressWarnings("PMD.ExcessiveParameterList")
// 10 fields dictated by the openapi.json VendorServiceCard wire contract, not by ergonomics.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorServiceCardDto(
    String vendorServiceId,
    String vendorServiceName,
    String vendorId,
    String vendorName,
    String vendorCategory,
    int integrationsConnected,
    List<IntegrationTypeCountDto> integrationTypeCounts,
    List<String> productsConnected,
    HealthState aggregateHealth,
    Instant lastUpdated) {

  static final String FIELD_VENDOR_SERVICE_ID = "vendorServiceId";
  static final String FIELD_VENDOR_SERVICE_NAME = "vendorServiceName";
  static final String FIELD_VENDOR_ID = "vendorId";
  static final String FIELD_VENDOR_NAME = "vendorName";
  static final String FIELD_VENDOR_CATEGORY = "vendorCategory";
  static final String FIELD_INTEGRATIONS_CONNECTED = "integrationsConnected";
  static final String FIELD_INTEGRATION_TYPE_COUNTS = "integrationTypeCounts";
  static final String FIELD_PRODUCTS_CONNECTED = "productsConnected";
  static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";

  public VendorServiceCardDto {
    Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
    Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
    Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
    Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
    Objects.requireNonNull(integrationTypeCounts, FIELD_INTEGRATION_TYPE_COUNTS);
    Objects.requireNonNull(productsConnected, FIELD_PRODUCTS_CONNECTED);
    Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
    if (integrationsConnected < 0) {
      throw new IllegalArgumentException(
          FIELD_INTEGRATIONS_CONNECTED + " must be >= 0: " + integrationsConnected);
    }
    integrationTypeCounts = List.copyOf(integrationTypeCounts);
    productsConnected = List.copyOf(productsConnected);
  }
}
```

- [ ] **Step 5: Implement VendorServiceCardNestedDto (8 fields, omits vendor id/name)**

```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Wire vendor-scoped vendor-service card per openapi.json VendorServiceCardNested ({@code GET
 * /vendors/{vendor_id}}). Omits {@code vendorId}/{@code vendorName} present in the flat card —
 * the parent vendor scope already pins them. {@code lastUpdated} is nullable (explicit null). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorServiceCardNestedDto(
    String vendorServiceId,
    String vendorServiceName,
    String vendorCategory,
    int integrationsConnected,
    List<IntegrationTypeCountDto> integrationTypeCounts,
    List<String> productsConnected,
    HealthState aggregateHealth,
    Instant lastUpdated) {

  static final String FIELD_VENDOR_SERVICE_ID = "vendorServiceId";
  static final String FIELD_VENDOR_SERVICE_NAME = "vendorServiceName";
  static final String FIELD_VENDOR_CATEGORY = "vendorCategory";
  static final String FIELD_INTEGRATIONS_CONNECTED = "integrationsConnected";
  static final String FIELD_INTEGRATION_TYPE_COUNTS = "integrationTypeCounts";
  static final String FIELD_PRODUCTS_CONNECTED = "productsConnected";
  static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";

  public VendorServiceCardNestedDto {
    Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
    Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
    Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
    Objects.requireNonNull(integrationTypeCounts, FIELD_INTEGRATION_TYPE_COUNTS);
    Objects.requireNonNull(productsConnected, FIELD_PRODUCTS_CONNECTED);
    Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
    if (integrationsConnected < 0) {
      throw new IllegalArgumentException(
          FIELD_INTEGRATIONS_CONNECTED + " must be >= 0: " + integrationsConnected);
    }
    integrationTypeCounts = List.copyOf(integrationTypeCounts);
    productsConnected = List.copyOf(productsConnected);
  }
}
```

- [ ] **Step 6: Run it to verify it passes**

Run: `./mvnw -q test -Dtest=VendorCardSerializationTest`
Expected: PASS (all four).

- [ ] **Step 7: Format and commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/rapid7/integrationregistry/controller/dto/VendorListEntryDto.java src/main/java/com/rapid7/integrationregistry/controller/dto/VendorServiceCardDto.java src/main/java/com/rapid7/integrationregistry/controller/dto/VendorServiceCardNestedDto.java src/test/java/com/rapid7/integrationregistry/controller/dto/VendorCardSerializationTest.java
git commit -m "feat(dto): vendor cards (flat + nested) and VendorListEntryDto"
```

---

## Task 7: The four response wrappers

The top-level bodies for each route, each wrapping its payload with `unavailable_products[]` + `metadata`. Proves acceptance signal 1 (whole-response conformance) end-to-end.

**Files:**
- Create: `controller/dto/VendorServicesResponse.java`
- Create: `controller/dto/VendorServiceDetailResponse.java`
- Create: `controller/dto/VendorsResponse.java`
- Create: `controller/dto/VendorDetailResponse.java`
- Test: `controller/dto/ResponseWrapperSerializationTest.java`

- [ ] **Step 1: Write the failing test**

Create `ResponseWrapperSerializationTest.java`:
```java
package com.rapid7.integrationregistry.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class ResponseWrapperSerializationTest {

  @Autowired private JacksonTester<VendorServicesResponse> vendorServices;
  @Autowired private JacksonTester<VendorServiceDetailResponse> detail;
  @Autowired private JacksonTester<VendorsResponse> vendors;
  @Autowired private JacksonTester<VendorDetailResponse> vendorDetail;
  private final ObjectMapper mapper = new ObjectMapper();

  private ResponseMetadataDto meta() {
    return new ResponseMetadataDto(true, Instant.parse("2026-04-23T10:00:00Z"), "v1.42.0");
  }

  private VendorServiceCardDto flatCard() {
    return new VendorServiceCardDto(
        "microsoft-defender", "Microsoft Defender", "microsoft", "Microsoft", "edr", 4,
        List.of(new IntegrationTypeCountDto("SIEM Event Source", 4, 1)),
        List.of("InsightIDR"), HealthState.ERROR, Instant.parse("2026-04-22T14:30:00Z"));
  }

  @Test
  void vendorServicesResponse_shouldMatchSchema() throws Exception {
    var dto = new VendorServicesResponse(List.of(flatCard()), List.of(), meta());
    var json = vendorServices.write(dto).getJson();
    assertThat(json).contains("\"vendor_services\":[");
    assertThat(json).contains("\"unavailable_products\":[]");
    assertThat(json).contains("\"metadata\":{");
    assertThat(OpenApiSchemas.validate("VendorServicesResponse", mapper.readTree(json))).isEmpty();
  }

  @Test
  void vendorServiceDetailResponse_shouldMatchSchema() throws Exception {
    var ds =
        new DataSourceDto(
            "insightidr|product_type|microsoft-defender-endpoint",
            "Microsoft Defender for Endpoint", "SIEM Event Source", "InsightIDR",
            HealthState.HEALTHY, 0, List.of());
    var dto =
        new VendorServiceDetailResponse(
            "microsoft-defender", "Microsoft Defender", "microsoft", "Microsoft", "edr",
            HealthState.ERROR, Instant.parse("2026-04-22T14:30:00Z"),
            List.of(ds), List.of(), meta());
    var json = detail.write(dto).getJson();
    assertThat(json).contains("\"data_sources\":[");
    assertThat(OpenApiSchemas.validate("VendorServiceDetailResponse", mapper.readTree(json))).isEmpty();
  }

  @Test
  void vendorsResponse_shouldMatchSchema() throws Exception {
    var dto =
        new VendorsResponse(
            List.of(new VendorListEntryDto("microsoft", "Microsoft", 2)), List.of(), meta());
    var json = vendors.write(dto).getJson();
    assertThat(json).contains("\"vendors\":[");
    assertThat(OpenApiSchemas.validate("VendorsResponse", mapper.readTree(json))).isEmpty();
  }

  @Test
  void vendorDetailResponse_shouldMatchSchema() throws Exception {
    var nested =
        new VendorServiceCardNestedDto(
            "microsoft-defender", "Microsoft Defender", "edr", 4,
            List.of(new IntegrationTypeCountDto("SIEM Event Source", 4, 1)),
            List.of("InsightIDR"), HealthState.ERROR, Instant.parse("2026-04-22T14:30:00Z"));
    var dto =
        new VendorDetailResponse(
            "microsoft", "Microsoft", HealthState.ERROR, Instant.parse("2026-04-22T14:30:00Z"),
            List.of(nested), List.of(), meta());
    var json = vendorDetail.write(dto).getJson();
    assertThat(json).contains("\"vendor_services\":[");
    assertThat(json).doesNotContain("\"vendor_id\":\"microsoft\",\"vendor_name\""); // nested omits
    assertThat(OpenApiSchemas.validate("VendorDetailResponse", mapper.readTree(json))).isEmpty();
  }

  @Test
  void allAdapterFailureShape_shouldBeEmptyPayloadWithPopulatedUnavailable() throws Exception {
    var dto =
        new VendorServicesResponse(
            List.of(),
            List.of(new UnavailableProductDto("InsightIDR", false, UnavailableReason.TIMEOUT, null)),
            meta());
    var json = vendorServices.write(dto).getJson();
    assertThat(json).contains("\"vendor_services\":[]");
    assertThat(json).contains("\"unavailable_products\":[{");
    assertThat(OpenApiSchemas.validate("VendorServicesResponse", mapper.readTree(json))).isEmpty();
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=ResponseWrapperSerializationTest`
Expected: FAIL — wrappers do not exist.

- [ ] **Step 3: Implement VendorServicesResponse**

```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Objects;

/** Wire body for {@code GET /vendor-services} per openapi.json VendorServicesResponse. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorServicesResponse(
    List<VendorServiceCardDto> vendorServices,
    List<UnavailableProductDto> unavailableProducts,
    ResponseMetadataDto metadata) {

  static final String FIELD_VENDOR_SERVICES = "vendorServices";
  static final String FIELD_UNAVAILABLE_PRODUCTS = "unavailableProducts";
  static final String FIELD_METADATA = "metadata";

  public VendorServicesResponse {
    Objects.requireNonNull(vendorServices, FIELD_VENDOR_SERVICES);
    Objects.requireNonNull(unavailableProducts, FIELD_UNAVAILABLE_PRODUCTS);
    Objects.requireNonNull(metadata, FIELD_METADATA);
    vendorServices = List.copyOf(vendorServices);
    unavailableProducts = List.copyOf(unavailableProducts);
  }
}
```

- [ ] **Step 4: Implement VendorServiceDetailResponse (PMD suppression — wide header)**

```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Wire body for {@code GET /vendor-services/{id}} per openapi.json VendorServiceDetailResponse.
 * Vendor-service header (incl. parent vendor identity) plus {@code dataSources[]}. {@code
 * vendorCategory} is plain String; {@code lastUpdated} nullable (explicit null). */
@SuppressWarnings("PMD.ExcessiveParameterList")
// 10 fields dictated by the openapi.json VendorServiceDetailResponse wire contract.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorServiceDetailResponse(
    String vendorServiceId,
    String vendorServiceName,
    String vendorId,
    String vendorName,
    String vendorCategory,
    HealthState aggregateHealth,
    Instant lastUpdated,
    List<DataSourceDto> dataSources,
    List<UnavailableProductDto> unavailableProducts,
    ResponseMetadataDto metadata) {

  static final String FIELD_VENDOR_SERVICE_ID = "vendorServiceId";
  static final String FIELD_VENDOR_SERVICE_NAME = "vendorServiceName";
  static final String FIELD_VENDOR_ID = "vendorId";
  static final String FIELD_VENDOR_NAME = "vendorName";
  static final String FIELD_VENDOR_CATEGORY = "vendorCategory";
  static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";
  static final String FIELD_DATA_SOURCES = "dataSources";
  static final String FIELD_UNAVAILABLE_PRODUCTS = "unavailableProducts";
  static final String FIELD_METADATA = "metadata";

  public VendorServiceDetailResponse {
    Objects.requireNonNull(vendorServiceId, FIELD_VENDOR_SERVICE_ID);
    Objects.requireNonNull(vendorServiceName, FIELD_VENDOR_SERVICE_NAME);
    Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
    Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    Objects.requireNonNull(vendorCategory, FIELD_VENDOR_CATEGORY);
    Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
    Objects.requireNonNull(dataSources, FIELD_DATA_SOURCES);
    Objects.requireNonNull(unavailableProducts, FIELD_UNAVAILABLE_PRODUCTS);
    Objects.requireNonNull(metadata, FIELD_METADATA);
    dataSources = List.copyOf(dataSources);
    unavailableProducts = List.copyOf(unavailableProducts);
  }
}
```

- [ ] **Step 5: Implement VendorsResponse**

```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Objects;

/** Wire body for {@code GET /vendors} per openapi.json VendorsResponse. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorsResponse(
    List<VendorListEntryDto> vendors,
    List<UnavailableProductDto> unavailableProducts,
    ResponseMetadataDto metadata) {

  static final String FIELD_VENDORS = "vendors";
  static final String FIELD_UNAVAILABLE_PRODUCTS = "unavailableProducts";
  static final String FIELD_METADATA = "metadata";

  public VendorsResponse {
    Objects.requireNonNull(vendors, FIELD_VENDORS);
    Objects.requireNonNull(unavailableProducts, FIELD_UNAVAILABLE_PRODUCTS);
    Objects.requireNonNull(metadata, FIELD_METADATA);
    vendors = List.copyOf(vendors);
    unavailableProducts = List.copyOf(unavailableProducts);
  }
}
```

- [ ] **Step 6: Implement VendorDetailResponse**

```java
package com.rapid7.integrationregistry.controller.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Wire body for {@code GET /vendors/{vendor_id}} per openapi.json VendorDetailResponse. Vendor
 * header (with rolled-up {@code aggregateHealth} + nullable {@code lastUpdated}) plus nested
 * {@code vendorServices[]} of VendorServiceCardNestedDto. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VendorDetailResponse(
    String vendorId,
    String vendorName,
    HealthState aggregateHealth,
    Instant lastUpdated,
    List<VendorServiceCardNestedDto> vendorServices,
    List<UnavailableProductDto> unavailableProducts,
    ResponseMetadataDto metadata) {

  static final String FIELD_VENDOR_ID = "vendorId";
  static final String FIELD_VENDOR_NAME = "vendorName";
  static final String FIELD_AGGREGATE_HEALTH = "aggregateHealth";
  static final String FIELD_VENDOR_SERVICES = "vendorServices";
  static final String FIELD_UNAVAILABLE_PRODUCTS = "unavailableProducts";
  static final String FIELD_METADATA = "metadata";

  public VendorDetailResponse {
    Objects.requireNonNull(vendorId, FIELD_VENDOR_ID);
    Objects.requireNonNull(vendorName, FIELD_VENDOR_NAME);
    Objects.requireNonNull(aggregateHealth, FIELD_AGGREGATE_HEALTH);
    Objects.requireNonNull(vendorServices, FIELD_VENDOR_SERVICES);
    Objects.requireNonNull(unavailableProducts, FIELD_UNAVAILABLE_PRODUCTS);
    Objects.requireNonNull(metadata, FIELD_METADATA);
    vendorServices = List.copyOf(vendorServices);
    unavailableProducts = List.copyOf(unavailableProducts);
  }
}
```

- [ ] **Step 7: Run it to verify it passes**

Run: `./mvnw -q test -Dtest=ResponseWrapperSerializationTest`
Expected: PASS (all five).

- [ ] **Step 8: Format and commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/rapid7/integrationregistry/controller/dto/VendorServicesResponse.java src/main/java/com/rapid7/integrationregistry/controller/dto/VendorServiceDetailResponse.java src/main/java/com/rapid7/integrationregistry/controller/dto/VendorsResponse.java src/main/java/com/rapid7/integrationregistry/controller/dto/VendorDetailResponse.java src/test/java/com/rapid7/integrationregistry/controller/dto/ResponseWrapperSerializationTest.java
git commit -m "feat(dto): four read-route response wrappers"
```

---

## Task 8: Package documentation + doc amendments

Document the new package as the wire surface and correct the drift in the projection package-info and ADR-003.

**Files:**
- Modify: `src/main/java/com/rapid7/integrationregistry/controller/dto/package-info.java` (create — none exists yet in `dto`)
- Modify: `src/main/java/com/rapid7/integrationregistry/aggregator/projection/package-info.java`
- Modify: `engagements/unified-integrations-view/decisions/adr/ADR-003-vendoraggregator-projection-hub.md`

- [ ] **Step 1: Create the dto package-info**

Create `src/main/java/com/rapid7/integrationregistry/controller/dto/package-info.java`:
```java
/**
 * Wire-format DTOs for the Integration Registry read API — the public JSON surface served by the
 * controller layer for the four read routes, plus the shared envelope ({@code unavailable_products},
 * {@code metadata}) and {@code error} types.
 *
 * <p>These records are the authoritative serialization target locked by {@code
 * decisions/rfc/openapi.json}. They are deliberately separate from the {@code
 * aggregator.projection} records (the aggregator's internal output contract): the projections carry
 * internal-only fields ({@code data_source_id} on integrations, {@code vendor_services_count} on the
 * vendor-scoped view) and use Java-native enums, neither of which belong on the wire. Work Plan 02
 * assembles projection values into these DTOs; Work Plan 03 returns them.
 *
 * <p>The package is self-contained — it imports nothing from {@code aggregator}, {@code mapping},
 * {@code adapter}, or {@code coordinator} (enforced by the {@code
 * controllerLayer_shouldNotDependOnInternalLayers} ArchUnit rule). snake_case wire names come from
 * a per-record {@code @JsonNaming(SnakeCaseStrategy)}; there is no global Jackson configuration.
 */
package com.rapid7.integrationregistry.controller.dto;
```

- [ ] **Step 2: Reword the projection package-info**

In `src/main/java/com/rapid7/integrationregistry/aggregator/projection/package-info.java`, replace the opening sentence and the closing note. Change the first line from:
```
 * Read-API projection records produced by {@link
 * com.rapid7.integrationregistry.aggregator.VendorAggregator} and serialized by the controller
 * layer.
```
to:
```
 * Read-API projection records produced by {@link
 * com.rapid7.integrationregistry.aggregator.VendorAggregator} — the aggregator's internal output
 * contract consumed by {@code VendorService}, NOT the wire surface.
 *
 * <p>The public JSON wire surface is {@code com.rapid7.integrationregistry.controller.dto}. These
 * projection records are the source of each wire field's value, but they carry internal-only fields
 * (e.g. {@code dataSourceId} on {@link
 * com.rapid7.integrationregistry.aggregator.projection.IntegrationDetail}, {@code
 * vendorServicesCount} on {@link
 * com.rapid7.integrationregistry.aggregator.projection.VendorScopedView}) and Java-native enums that
 * do not appear on the wire.
```
Keep the existing `<ul>` route-mapping list (it still correctly maps each record to the route whose values it originates). Update the trailing "serialized by the controller layer" phrasing in the list intro from "maps directly to a response body or embedded object" — leave that line as-is (it remains accurate as origin-of-values).

- [ ] **Step 3: Append the ADR-003 amendment note**

Append to `engagements/unified-integrations-view/decisions/adr/ADR-003-vendoraggregator-projection-hub.md` (after the References section):
```markdown

## Amendment — 2026-06-04 (Track 09 / WP-01)

This ADR's Context describes the projection records as "the public surface of
the read API." Track 09 Work Plan 01 introduced a dedicated controller-owned
wire DTO layer (`com.rapid7.integrationregistry.controller.dto`) as the actual
JSON wire surface. The projection records remain the aggregator's **internal
output contract** consumed by `VendorService` — the source of each wire field's
value — but they are not the wire types: they carry internal-only fields
(`dataSourceId`, `vendorServicesCount`) and Java-native enums.

This does not change the decision in this ADR. The class-level PMD suppressions
on `VendorAggregator` and the projection-hub rationale stand unchanged; only the
"public surface of the read API" wording is clarified to mean `controller.dto`.
```

- [ ] **Step 4: Verify the projection package-info still compiles and its test passes**

Run: `./mvnw -q test -Dtest=ProjectionRecordsTest`
Expected: PASS (package-info is doc-only; no behavior change).

- [ ] **Step 5: Format and commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/rapid7/integrationregistry/controller/dto/package-info.java src/main/java/com/rapid7/integrationregistry/aggregator/projection/package-info.java
git commit -m "docs(dto): document wire surface; clarify projection records are internal"
```

> **Note:** ADR-003 lives in the engagement repo (a different git root than this
> service worktree). Stage and commit it separately in that repo:
> ```bash
> git -C ../../../../../../engagements/unified-integrations-view add decisions/adr/ADR-003-vendoraggregator-projection-hub.md
> git -C ../../../../../../engagements/unified-integrations-view commit -m "docs(adr-003): clarify wire surface is controller.dto (Track 09 WP-01)"
> ```
> If that relative path does not resolve, locate the engagement repo root and run
> the commit there. If the developer's engagement convention is local-only edits
> without commits, leave the file modified and note it for the close-out phase.

---

## Task 9: Full verification gate

Prove all seven acceptance signals together and that the full quality gate is green.

**Files:** none (verification only)

- [ ] **Step 1: Format the whole tree**

Run: `./mvnw -q spotless:apply`
Expected: no errors; any reformatting is staged in the next step.

- [ ] **Step 2: Full verify (Docker running for the unrelated cache tests)**

Run: `./mvnw verify`
Expected: BUILD SUCCESS. ArchUnit passes (controller.dto imports no internal layers), PMD passes (`ExcessiveParameterList` suppressed on the two wide records, no other violations), Spotless check passes, all DTO tests green.

If Docker is not available and only the T07 Testcontainers cache tests fail to start, run the DTO-scoped suite to prove this plan's work in isolation and note the Docker dependency:
```bash
./mvnw -q test -Dtest='*Dto*Test,*ResponseTest,*SerializationTest,*ValidationTest,OpenApiSchemasTest,DtoEnumTest'
```
Expected: all green.

- [ ] **Step 3: Acceptance-signal self-check (read-only)**

Confirm, by pointing at a passing test for each:
1. Four response DTOs match schema → `ResponseWrapperSerializationTest` (4 tests).
2. Internal-only fields never appear → `DataSourceSerializationTest.integration_shouldOmitInternalFields_andMatchSchema`.
3. `stale_since` present-iff-stale → `UnavailableProductSerializationTest` (2 tests).
4. Nullable fields render explicit null → `DataSourceSerializationTest.integration_shouldRenderNullableFieldsAsExplicitNull` + `VendorCardSerializationTest.flatCard_shouldRenderNullLastUpdatedAsExplicitNull`.
5. Nested omits / flat includes vendor id+name → `VendorCardSerializationTest` (flat + nested tests).
6. Supporting types match schema → `SupportingTypesSerializationTest` + `UnavailableProductSerializationTest`.
7. `./mvnw verify` green → Step 2.

- [ ] **Step 4: Commit any formatting deltas**

```bash
git add -A
git diff --cached --quiet || git commit -m "style(dto): apply google-java-format"
```

- [ ] **Step 5: Return control to execute-plan**

Do NOT invoke `superpowers:finishing-a-development-branch`. This plan runs inside an `execute-plan` pipeline; review gates (Phases 7–9) run before any PR. Report completion (all tasks checked, `verify` green) back to the orchestrator.

---

## Self-review notes

- **Spec coverage:** every type in the spec's type catalog has a creating task (enums T2; supporting T3+T4; integration/data-source T5; cards T6; wrappers T7). Helper + fixture T1. Doc amendments T8. All 7 acceptance signals mapped in Task 9 Step 3.
- **Type consistency:** field names and `FIELD_*` constants are consistent across tasks; `IntegrationDto` has no `data_source_id` everywhere; `VendorServiceCardNestedDto` omits vendor id/name everywhere; `ErrorBody` is nested in `ErrorEnvelopeDto` consistently.
- **Known risk surfaced inline:** `@JsonInclude(NON_NULL)` on a record component (Task 4) and the `networknt` sub-schema loading API (Task 1) each carry an explicit, bounded fallback so the executing agent does not improvise.
- **Cross-repo edit:** ADR-003 lives in the engagement repo; Task 8 handles it as a separate commit with a path-resolution fallback.
