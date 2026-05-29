# InsightConnect Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `InsightConnectAdapter` — the first concrete `IntegrationAdapter` — fetching ICON automation connections via Spring `RestClient`, normalizing each to a `NormalizedIntegration` with the RFC-001 five-state status mapping, pinned fixtures, and `MockRestServiceServer` contract tests.

**Architecture:** A pure `ConnectionStatusMapper` owns health derivation (5-row table, precedence, test selection, last-success). `InsightConnectAdapter` (`@Component`) owns the single HTTP call, response→`NormalizedIntegration` mapping, and HTTP-failure→adapter-exception translation. Jackson-bound DTO records mirror the real ICON wire shape. `RestClient` is built from `InsightConnectProperties` via a `@Configuration` so tests bind `MockRestServiceServer` to the builder. All new code lives in `com.rapid7.integrationregistry.adapter.insightconnect`.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Maven (`./mvnw verify` = JUnit + ArchUnit + PMD), Jackson, JUnit 5 + AssertJ + `MockRestServiceServer`.

**Reference docs:** Design spec `docs/superpowers/specs/2026-05-29-icon-adapter-design.md`; ADR-002 (RestClient choice); `TESTING.md` (test taxonomy); canonical contract-test example `src/test/java/com/rapid7/integrationregistry/testsupport/examples/SampleContractTest.java`.

**Key conventions to honor:**
- Records use compact constructors with `Objects.requireNonNull(field, FIELD_NAME)`; field-name strings are package-private `static final String FIELD_<NAME>` constants (see plan-01 records).
- Test method naming: `methodName_shouldDoX_whenY()`. Arrange-Act-Assert with explicit `// Arrange` / `// Act` / `// Assert` comments.
- Contract tests: `bindTo` the `RestClient.Builder` BEFORE `.build()`; always end with `server.verify()`.
- No swallowed exceptions (PMD empty-catch + project silent-failure gate). The `// TODO(T10)` marker annotates working code — acceptable.

**STOP condition:** After the final task, STOP and return control to `execute-plan`. Do NOT invoke `superpowers:finishing-a-development-branch` — validation gates (functional review, simplify, external review) run between "tasks done" and "PR".

---

## File Structure

**Create (main):**
- `src/main/java/com/rapid7/integrationregistry/adapter/insightconnect/package-info.java` — package doc
- `.../insightconnect/ConnectionStatusMapper.java` — pure health-derivation logic
- `.../insightconnect/InsightConnectProperties.java` — `@ConfigurationProperties` record
- `.../insightconnect/ConnectionsResponse.java` — top-level response DTO (+ `ResponseMetadata`)
- `.../insightconnect/ConnectionViewModel.java` — per-connection DTO
- `.../insightconnect/Plugin.java` — plugin sub-object DTO
- `.../insightconnect/Orchestrator.java` — orchestrator sub-object DTO
- `.../insightconnect/ConnectionTest.java` — embedded test DTO
- `.../insightconnect/InsightConnectClientConfig.java` — `@Configuration` exposing the `RestClient` bean
- `.../insightconnect/InsightConnectAdapter.java` — the `@Component` adapter

**Create (test):**
- `src/test/java/com/rapid7/integrationregistry/adapter/insightconnect/ConnectionStatusMapperTest.java`
- `.../insightconnect/InsightConnectAdapterContractTest.java`
- `src/test/resources/fixtures/insightconnect/{error,missing-data-stale,missing-data-unknown,warning,disabled,healthy,precedence-failed-and-warning,multi-connection}.json`

**Modify:**
- `src/main/resources/application.yaml` — add `integration-registry.insightconnect.*`

**Do NOT modify:** any plan-01 contract type in `adapter/` (`IntegrationAdapter`, `FetchResult`, `NormalizedIntegration`, `SourceIdentifier`, `IntegrationStatus`, `adapter/exception/*`).

---

## Task 1: Package scaffold + `ConnectionTest` DTO

The status mapper depends on `ConnectionTest`, so the DTO comes first (just enough of it for the mapper). The other DTOs follow in Task 3.

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/adapter/insightconnect/package-info.java`
- Create: `src/main/java/com/rapid7/integrationregistry/adapter/insightconnect/ConnectionTest.java`

- [ ] **Step 1: Write the package-info**

`package-info.java`:

```java
/**
 * InsightConnect adapter: fetches automation connections from
 * {@code GET /api/public/v1/connections?includeTests=1} and normalizes them to
 * {@link com.rapid7.integrationregistry.adapter.NormalizedIntegration} records.
 *
 * <p>Per ADR-002 the outbound call uses Spring {@code RestClient} (not the
 * reactive {@code WebClient} named in RFC-001). Health derivation lives in the
 * pure {@link com.rapid7.integrationregistry.adapter.insightconnect.ConnectionStatusMapper};
 * the DTO records mirror the real ICON wire shape.
 */
package com.rapid7.integrationregistry.adapter.insightconnect;
```

- [ ] **Step 2: Write `ConnectionTest`**

`ConnectionTest.java`:

```java
package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Embedded connection-test record from the ICON connections endpoint
 * (present when {@code ?includeTests=1}). Field names match the ICON wire
 * shape exactly. {@code createdAt} binds to {@link Instant} so most-recent
 * selection is a clean temporal comparison.
 *
 * <p>{@code status} values: {@code pending | success | failed | timeout}.
 * {@code isStale} may be absent in the JSON — a {@code null} is treated as
 * not-stale by {@link ConnectionStatusMapper}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionTest(
    String id,
    String connectionId,
    String status,
    Boolean isStale,
    String errorMessage,
    Instant createdAt
) {}
```

- [ ] **Step 3: Verify it compiles**

Run: `./mvnw -q -o compile`
Expected: BUILD SUCCESS (no test run yet).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/adapter/insightconnect/
git commit -m "feat(track-05/wp-02): scaffold insightconnect package + ConnectionTest DTO"
```

---

## Task 2: `ConnectionStatusMapper` (TDD — the heart of the adapter)

Pure logic, no Spring/HTTP. Write the full test first, watch it fail, implement, watch it pass.

**Files:**
- Test: `src/test/java/com/rapid7/integrationregistry/adapter/insightconnect/ConnectionStatusMapperTest.java`
- Create: `src/main/java/com/rapid7/integrationregistry/adapter/insightconnect/ConnectionStatusMapper.java`

- [ ] **Step 1: Write the failing test**

`ConnectionStatusMapperTest.java`:

```java
package com.rapid7.integrationregistry.adapter.insightconnect;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionStatusMapperTest {

    private final ConnectionStatusMapper mapper = new ConnectionStatusMapper();

    private static ConnectionTest test(String status, Boolean isStale, Instant createdAt) {
        return new ConnectionTest("t", "c", status, isStale, null, createdAt);
    }

    private static final Instant T1 = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-05-03T00:00:00Z");

    // ---- deriveStatus: single-signal happy paths ----

    @Test
    void deriveStatus_shouldReturnHealthy_whenOrchestratorHealthyAndTestSuccessAndNotStale() {
        // Arrange
        ConnectionTest t = test("success", false, T1);
        // Act
        IntegrationStatus status = mapper.deriveStatus("healthy", t);
        // Assert
        assertThat(status).isEqualTo(IntegrationStatus.HEALTHY);
    }

    @Test
    void deriveStatus_shouldReturnError_whenOrchestratorError() {
        // Arrange
        ConnectionTest t = test("success", false, T1);
        // Act / Assert
        assertThat(mapper.deriveStatus("error", t)).isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldReturnError_whenTestFailed() {
        assertThat(mapper.deriveStatus("healthy", test("failed", false, T1)))
            .isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldReturnError_whenTestTimeout() {
        assertThat(mapper.deriveStatus("healthy", test("timeout", false, T1)))
            .isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenTestStale() {
        assertThat(mapper.deriveStatus("healthy", test("success", true, T1)))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorUnknown() {
        assertThat(mapper.deriveStatus("unknown", test("success", false, T1)))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldReturnWarning_whenOrchestratorWarning() {
        assertThat(mapper.deriveStatus("warning", test("success", false, T1)))
            .isEqualTo(IntegrationStatus.WARNING);
    }

    @Test
    void deriveStatus_shouldReturnDisabled_whenOrchestratorStopped() {
        assertThat(mapper.deriveStatus("stopped", test("success", false, T1)))
            .isEqualTo(IntegrationStatus.DISABLED);
    }

    // ---- deriveStatus: precedence + no-test + edge cases ----

    @Test
    void deriveStatus_shouldReturnError_whenTestFailedAndOrchestratorWarning() {
        // Precedence: error > warning
        assertThat(mapper.deriveStatus("warning", test("failed", false, T1)))
            .isEqualTo(IntegrationStatus.ERROR);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorHealthyAndNoTest() {
        // No confirming healthy test => cannot confirm it works
        assertThat(mapper.deriveStatus("healthy", null))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorHealthyAndTestPending() {
        assertThat(mapper.deriveStatus("healthy", test("pending", false, T1)))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldTreatNullIsStaleAsNotStale_whenHealthySuccess() {
        // null isStale must not block healthy
        assertThat(mapper.deriveStatus("healthy", test("success", null, T1)))
            .isEqualTo(IntegrationStatus.HEALTHY);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorUnrecognized() {
        // Forward-compat: a 6th orchestrator value falls back to missing_data
        assertThat(mapper.deriveStatus("decommissioning", test("success", false, T1)))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    @Test
    void deriveStatus_shouldReturnMissingData_whenOrchestratorNull() {
        assertThat(mapper.deriveStatus(null, test("success", false, T1)))
            .isEqualTo(IntegrationStatus.MISSING_DATA);
    }

    // ---- mostRecentByCreatedAt ----

    @Test
    void mostRecentByCreatedAt_shouldReturnLatest_whenMultipleTests() {
        // Arrange — deliberately out of order
        List<ConnectionTest> tests = List.of(
            test("failed", false, T1),
            test("success", false, T3),
            test("success", false, T2));
        // Act
        ConnectionTest latest = mapper.mostRecentByCreatedAt(tests);
        // Assert
        assertThat(latest.createdAt()).isEqualTo(T3);
    }

    @Test
    void mostRecentByCreatedAt_shouldReturnNull_whenEmpty() {
        assertThat(mapper.mostRecentByCreatedAt(List.of())).isNull();
    }

    @Test
    void mostRecentByCreatedAt_shouldReturnNull_whenNull() {
        assertThat(mapper.mostRecentByCreatedAt(null)).isNull();
    }

    // ---- deriveLastSuccess ----

    @Test
    void deriveLastSuccess_shouldReturnMostRecentSuccessTimestamp_whenSuccessesExist() {
        List<ConnectionTest> tests = List.of(
            test("success", false, T1),
            test("success", false, T2));
        assertThat(mapper.deriveLastSuccess(tests)).isEqualTo(T2);
    }

    @Test
    void deriveLastSuccess_shouldReturnNull_whenNoSuccess() {
        List<ConnectionTest> tests = List.of(
            test("failed", false, T1),
            test("timeout", false, T2));
        assertThat(mapper.deriveLastSuccess(tests)).isNull();
    }

    @Test
    void deriveLastSuccess_shouldReturnLastSuccess_whenLatestOverallIsFailure() {
        // Latest overall (T3) failed, but T2 succeeded => report T2
        List<ConnectionTest> tests = List.of(
            test("success", false, T2),
            test("failed", false, T3),
            test("failed", false, T1));
        assertThat(mapper.deriveLastSuccess(tests)).isEqualTo(T2);
    }

    @Test
    void deriveLastSuccess_shouldReturnNull_whenNullOrEmpty() {
        assertThat(mapper.deriveLastSuccess(null)).isNull();
        assertThat(mapper.deriveLastSuccess(List.of())).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -o test -Dtest=ConnectionStatusMapperTest`
Expected: COMPILATION FAILURE — `ConnectionStatusMapper` does not exist.

- [ ] **Step 3: Write the implementation**

`ConnectionStatusMapper.java`:

```java
package com.rapid7.integrationregistry.adapter.insightconnect;

import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Pure health-derivation logic for ICON connections (RFC-001
 * §InsightConnectAdapter). No Spring, no HTTP, no clock — unit-testable in
 * isolation. Owns the five-state status table, the instance-level precedence
 * ({@code error > missing_data > warning > disabled > healthy}), most-recent
 * {@link ConnectionTest} selection, and {@code last_success_timestamp}
 * derivation.
 */
public class ConnectionStatusMapper {

    private static final Logger log = LoggerFactory.getLogger(ConnectionStatusMapper.class);

    private static final String ORCH_HEALTHY = "healthy";
    private static final String ORCH_ERROR = "error";
    private static final String ORCH_WARNING = "warning";
    private static final String ORCH_STOPPED = "stopped";
    private static final String ORCH_UNKNOWN = "unknown";

    private static final String TEST_SUCCESS = "success";
    private static final String TEST_FAILED = "failed";
    private static final String TEST_TIMEOUT = "timeout";

    /**
     * Derive the instance status from the orchestrator status and the most
     * recent connection test (which may be {@code null} when no tests exist).
     * First match wins, in precedence order. An unrecognized or {@code null}
     * orchestrator value falls back to {@link IntegrationStatus#MISSING_DATA}
     * with a WARN log — one anomalous connection must not fail the fetch.
     */
    public IntegrationStatus deriveStatus(String orchestratorStatus, ConnectionTest mostRecentTest) {
        boolean stale = mostRecentTest != null && Boolean.TRUE.equals(mostRecentTest.isStale());
        String testStatus = mostRecentTest == null ? null : mostRecentTest.status();

        // 1. error
        if (TEST_FAILED.equals(testStatus) || TEST_TIMEOUT.equals(testStatus)
                || ORCH_ERROR.equals(orchestratorStatus)) {
            return IntegrationStatus.ERROR;
        }
        // 2. missing_data (stale test or unknown orchestrator)
        if (stale || ORCH_UNKNOWN.equals(orchestratorStatus)) {
            return IntegrationStatus.MISSING_DATA;
        }
        // 3. warning
        if (ORCH_WARNING.equals(orchestratorStatus)) {
            return IntegrationStatus.WARNING;
        }
        // 4. disabled
        if (ORCH_STOPPED.equals(orchestratorStatus)) {
            return IntegrationStatus.DISABLED;
        }
        // 5. healthy (requires a confirming successful, non-stale test)
        if (ORCH_HEALTHY.equals(orchestratorStatus)
                && TEST_SUCCESS.equals(testStatus)) {
            return IntegrationStatus.HEALTHY;
        }
        // 6. missing_data tail: healthy orchestrator with no confirming test,
        //    or any unrecognized / null orchestrator value.
        if (!ORCH_HEALTHY.equals(orchestratorStatus)) {
            log.warn("Unrecognized ICON orchestrator status '{}'; mapping to missing_data",
                     orchestratorStatus);
        }
        return IntegrationStatus.MISSING_DATA;
    }

    /**
     * The most recent test by {@code createdAt}, or {@code null} when the list
     * is null/empty. Tests with a null {@code createdAt} sort earliest.
     */
    public ConnectionTest mostRecentByCreatedAt(List<ConnectionTest> tests) {
        if (tests == null || tests.isEmpty()) {
            return null;
        }
        return tests.stream()
            .max(Comparator.comparing(ConnectionTest::createdAt,
                     Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(null);
    }

    /**
     * Timestamp of the most recent successful test, or {@code null} when none
     * succeeded (or the list is null/empty). Independent of
     * {@link #mostRecentByCreatedAt} — a connection whose latest test failed
     * still reports its last success.
     */
    public Instant deriveLastSuccess(List<ConnectionTest> tests) {
        if (tests == null || tests.isEmpty()) {
            return null;
        }
        return tests.stream()
            .filter(t -> TEST_SUCCESS.equals(t.status()) && t.createdAt() != null)
            .map(ConnectionTest::createdAt)
            .max(Comparator.naturalOrder())
            .orElse(null);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -o test -Dtest=ConnectionStatusMapperTest`
Expected: PASS — all `ConnectionStatusMapperTest` cases green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/adapter/insightconnect/ConnectionStatusMapper.java \
        src/test/java/com/rapid7/integrationregistry/adapter/insightconnect/ConnectionStatusMapperTest.java
git commit -m "feat(track-05/wp-02): ConnectionStatusMapper with RFC-001 status table + precedence"
```

---

## Task 3: Remaining response DTOs

The Jackson-bound records mirroring the rest of the wire shape. No behavior, so a compile check suffices (Task 5's contract tests exercise the binding).

**Files:**
- Create: `.../insightconnect/Plugin.java`
- Create: `.../insightconnect/Orchestrator.java`
- Create: `.../insightconnect/ConnectionViewModel.java`
- Create: `.../insightconnect/ConnectionsResponse.java`

- [ ] **Step 1: Write `Plugin`**

```java
package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Plugin sub-object of {@link ConnectionViewModel}. {@code name} is the source
 * identifier used for vendor mapping ({@code source_type = "plugin_name"});
 * {@code pluginVendor} is the plugin author ("rapid7"), NOT the third-party
 * vendor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Plugin(String name, String pluginVendor, String pluginVersion) {}
```

- [ ] **Step 2: Write `Orchestrator`**

```java
package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Orchestrator sub-object of {@link ConnectionViewModel}. {@code status}
 * values: {@code healthy | error | warning | stopped | unknown}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Orchestrator(String id, String name, String status, String version) {}
```

- [ ] **Step 3: Write `ConnectionViewModel`**

```java
package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A single ICON automation connection. Field names match the wire shape
 * exactly ({@code connectionTests}, not {@code connection_test}).
 * {@code configurationUrl} is nullable — today's API does not return it, but
 * the adapter prefers it when present (RFC-001 forward-compat clause).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionViewModel(
    String id,
    String name,
    Plugin plugin,
    Orchestrator orchestrator,
    Boolean isCloud,
    String createdAt,
    String updatedAt,
    List<ConnectionTest> connectionTests,
    String configurationUrl
) {}
```

- [ ] **Step 4: Write `ConnectionsResponse`**

```java
package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Top-level response from {@code GET /api/public/v1/connections}. The adapter
 * reads {@code data}; {@code metadata.total} is carried for completeness.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionsResponse(List<ConnectionViewModel> data, ResponseMetadata metadata) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResponseMetadata(Integer total) {}
}
```

- [ ] **Step 5: Verify it compiles**

Run: `./mvnw -q -o compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/adapter/insightconnect/
git commit -m "feat(track-05/wp-02): ICON response DTOs (ConnectionViewModel, Plugin, Orchestrator, ConnectionsResponse)"
```

---

## Task 4: `InsightConnectProperties` + config namespace + `RestClient` bean

**Files:**
- Create: `.../insightconnect/InsightConnectProperties.java`
- Create: `.../insightconnect/InsightConnectClientConfig.java`
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Write `InsightConnectProperties`**

`InsightConnectProperties.java`:

```java
package com.rapid7.integrationregistry.adapter.insightconnect;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration properties for the InsightConnect adapter, bound from the
 * {@code integration-registry.insightconnect.*} property tree.
 *
 * <p>{@code baseUrl} (scheme+host of the connections API) and {@code iconBase}
 * (base for {@code configuration_url} deep-links) have no defaults — the
 * deploy environment supplies them; absent config fails fast at binding.
 * {@code timeout} defaults to 5 seconds (a starting value; the T07 coordinator
 * owns the fan-out deadline).
 *
 * <p>Activated via {@code @EnableConfigurationProperties} on
 * {@link InsightConnectClientConfig}.
 */
@ConfigurationProperties("integration-registry.insightconnect")
public record InsightConnectProperties(
    String baseUrl,
    String iconBase,
    Duration timeout
) {

    private static final String FIELD_BASE_URL = "baseUrl";
    private static final String FIELD_ICON_BASE = "iconBase";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    public InsightConnectProperties {
        Objects.requireNonNull(baseUrl, FIELD_BASE_URL);
        Objects.requireNonNull(iconBase, FIELD_ICON_BASE);
        if (timeout == null) {
            timeout = DEFAULT_TIMEOUT;
        }
    }
}
```

- [ ] **Step 2: Write `InsightConnectClientConfig`**

`InsightConnectClientConfig.java`:

```java
package com.rapid7.integrationregistry.adapter.insightconnect;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration for the InsightConnect adapter. Activates
 * {@link InsightConnectProperties} and exposes a {@link RestClient} bean built
 * from a {@link RestClient.Builder} with the configured base URL and per-call
 * connect/read timeout.
 *
 * <p>The builder is configured then built here; contract tests construct their
 * own builder and bind a {@code MockRestServiceServer} to it (see
 * {@code InsightConnectAdapterContractTest}).
 */
@Configuration
@EnableConfigurationProperties(InsightConnectProperties.class)
public class InsightConnectClientConfig {

    @Bean
    public RestClient insightConnectRestClient(InsightConnectProperties properties) {
        ClientHttpRequestFactorySettings settings =
            ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.timeout())
                .withReadTimeout(properties.timeout());
        return RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .build();
    }
}
```

> **Implementation note for the worker:** Spring Boot 4 provides `ClientHttpRequestFactorySettings` and `ClientHttpRequestFactoryBuilder` in `org.springframework.boot.web.client`. If the exact import path differs in 4.0.6, resolve it by checking the available class (the goal is: a `RestClient` with connect+read timeout from `properties.timeout()` and base URL from `properties.baseUrl()`). Do not substitute `RestTemplate`.

- [ ] **Step 3: Modify `application.yaml`**

In `src/main/resources/application.yaml`, under the existing top-level `integration-registry:` key (sibling to `vendor-mapping:`), add:

```yaml
integration-registry:
  vendor-mapping:
    cache-dir: ${java.io.tmpdir}/integration-registry/vendor-mapping
    # bundle-version, s3-bucket, s3-key-prefix come from the deploy environment.
    # Absent here so missing config fails fast at property binding (no stale defaults).
  insightconnect:
    # base-url and icon-base come from the deploy environment (no defaults —
    # absent config fails fast at binding).
    timeout: 5s
```

> **Worker note:** keep the existing `vendor-mapping:` block exactly as-is; only ADD the `insightconnect:` sub-key under `integration-registry:`. Do not duplicate the `integration-registry:` key.

- [ ] **Step 4: Verify the context still loads**

Run: `./mvnw -q -o test -Dtest=IntegrationRegistryApplicationTests`
Expected: PASS. (This is the existing context-load smoke test. If it fails because `base-url`/`icon-base` are now required and absent in the `test` profile, add them to `src/test/resources/application-test.yaml`:)

```yaml
integration-registry:
  insightconnect:
    base-url: http://icon.test.local
    icon-base: http://icon.test.local
    timeout: 1s
```

Re-run until PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/adapter/insightconnect/InsightConnectProperties.java \
        src/main/java/com/rapid7/integrationregistry/adapter/insightconnect/InsightConnectClientConfig.java \
        src/main/resources/application.yaml src/test/resources/application-test.yaml
git commit -m "feat(track-05/wp-02): InsightConnect config properties + RestClient bean"
```

---

## Task 5: `InsightConnectAdapter` + fixtures + contract tests (happy paths)

This is the big task: the adapter, the status-path fixtures, and the contract tests that assert full `NormalizedIntegration` output. Write fixtures + tests first, then the adapter.

**Files:**
- Create: `src/test/resources/fixtures/insightconnect/healthy.json` (+ the other 6 single-connection fixtures and `multi-connection.json`)
- Test: `src/test/java/com/rapid7/integrationregistry/adapter/insightconnect/InsightConnectAdapterContractTest.java`
- Create: `.../insightconnect/InsightConnectAdapter.java`

- [ ] **Step 1: Write the fixtures**

`src/test/resources/fixtures/insightconnect/healthy.json`:

```json
{
  "data": [
    {
      "id": "c1a2b3c4-0001-0001-0001-000000000001",
      "name": "Jira Production",
      "plugin": { "name": "jira", "pluginVendor": "rapid7", "pluginVersion": "11.3.0" },
      "orchestrator": { "id": "orch-1", "name": "Cloud Orchestrator", "status": "healthy", "version": "3.1.4" },
      "connectionTests": [
        { "id": "ct-1", "connectionId": "c1a2b3c4-0001-0001-0001-000000000001", "status": "success", "isStale": false, "errorMessage": null, "createdAt": "2026-05-19T10:00:00Z" }
      ]
    }
  ],
  "metadata": { "total": 1 }
}
```

`src/test/resources/fixtures/insightconnect/error.json` — orchestrator healthy but test failed:

```json
{
  "data": [
    {
      "id": "c1a2b3c4-0002-0002-0002-000000000002",
      "name": "Jira Error",
      "plugin": { "name": "jira", "pluginVendor": "rapid7", "pluginVersion": "11.3.0" },
      "orchestrator": { "id": "orch-1", "name": "Cloud Orchestrator", "status": "healthy", "version": "3.1.4" },
      "connectionTests": [
        { "id": "ct-2", "connectionId": "c1a2b3c4-0002-0002-0002-000000000002", "status": "failed", "isStale": false, "errorMessage": "boom", "createdAt": "2026-05-19T10:00:00Z" }
      ]
    }
  ],
  "metadata": { "total": 1 }
}
```

`src/test/resources/fixtures/insightconnect/missing-data-stale.json` — stale test:

```json
{
  "data": [
    {
      "id": "c1a2b3c4-0003-0003-0003-000000000003",
      "name": "Jira Stale",
      "plugin": { "name": "jira", "pluginVendor": "rapid7", "pluginVersion": "11.3.0" },
      "orchestrator": { "id": "orch-1", "name": "Cloud Orchestrator", "status": "healthy", "version": "3.1.4" },
      "connectionTests": [
        { "id": "ct-3", "connectionId": "c1a2b3c4-0003-0003-0003-000000000003", "status": "success", "isStale": true, "errorMessage": null, "createdAt": "2026-05-10T08:00:00Z" }
      ]
    }
  ],
  "metadata": { "total": 1 }
}
```

`src/test/resources/fixtures/insightconnect/missing-data-unknown.json` — orchestrator unknown:

```json
{
  "data": [
    {
      "id": "c1a2b3c4-0004-0004-0004-000000000004",
      "name": "Jira Unknown",
      "plugin": { "name": "jira", "pluginVendor": "rapid7", "pluginVersion": "11.3.0" },
      "orchestrator": { "id": "orch-1", "name": "Cloud Orchestrator", "status": "unknown", "version": "3.1.4" },
      "connectionTests": [
        { "id": "ct-4", "connectionId": "c1a2b3c4-0004-0004-0004-000000000004", "status": "success", "isStale": false, "errorMessage": null, "createdAt": "2026-05-18T08:00:00Z" }
      ]
    }
  ],
  "metadata": { "total": 1 }
}
```

`src/test/resources/fixtures/insightconnect/warning.json`:

```json
{
  "data": [
    {
      "id": "c1a2b3c4-0005-0005-0005-000000000005",
      "name": "Jira Warning",
      "plugin": { "name": "jira", "pluginVendor": "rapid7", "pluginVersion": "11.3.0" },
      "orchestrator": { "id": "orch-1", "name": "Cloud Orchestrator", "status": "warning", "version": "3.1.4" },
      "connectionTests": [
        { "id": "ct-5", "connectionId": "c1a2b3c4-0005-0005-0005-000000000005", "status": "success", "isStale": false, "errorMessage": null, "createdAt": "2026-05-18T08:00:00Z" }
      ]
    }
  ],
  "metadata": { "total": 1 }
}
```

`src/test/resources/fixtures/insightconnect/disabled.json`:

```json
{
  "data": [
    {
      "id": "c1a2b3c4-0006-0006-0006-000000000006",
      "name": "Jira Disabled",
      "plugin": { "name": "jira", "pluginVendor": "rapid7", "pluginVersion": "11.3.0" },
      "orchestrator": { "id": "orch-1", "name": "Cloud Orchestrator", "status": "stopped", "version": "3.1.4" },
      "connectionTests": [
        { "id": "ct-6", "connectionId": "c1a2b3c4-0006-0006-0006-000000000006", "status": "success", "isStale": false, "errorMessage": null, "createdAt": "2026-05-18T08:00:00Z" }
      ]
    }
  ],
  "metadata": { "total": 1 }
}
```

`src/test/resources/fixtures/insightconnect/precedence-failed-and-warning.json` — failed test AND warning orchestrator → error:

```json
{
  "data": [
    {
      "id": "c1a2b3c4-0007-0007-0007-000000000007",
      "name": "Jira Precedence",
      "plugin": { "name": "jira", "pluginVendor": "rapid7", "pluginVersion": "11.3.0" },
      "orchestrator": { "id": "orch-1", "name": "Cloud Orchestrator", "status": "warning", "version": "3.1.4" },
      "connectionTests": [
        { "id": "ct-7", "connectionId": "c1a2b3c4-0007-0007-0007-000000000007", "status": "failed", "isStale": false, "errorMessage": "boom", "createdAt": "2026-05-18T08:00:00Z" }
      ]
    }
  ],
  "metadata": { "total": 1 }
}
```

`src/test/resources/fixtures/insightconnect/multi-connection.json` — jira healthy + jira warning/stale + microsoft-defender healthy:

```json
{
  "data": [
    {
      "id": "c1a2b3c4-0001-0001-0001-000000000001",
      "name": "Jira Production",
      "plugin": { "name": "jira", "pluginVendor": "rapid7", "pluginVersion": "11.3.0" },
      "orchestrator": { "id": "orch-1", "name": "Cloud Orchestrator", "status": "healthy", "version": "3.1.4" },
      "connectionTests": [
        { "id": "ct-1", "connectionId": "c1a2b3c4-0001-0001-0001-000000000001", "status": "success", "isStale": false, "errorMessage": null, "createdAt": "2026-05-19T10:00:00Z" }
      ]
    },
    {
      "id": "c1a2b3c4-0002-0002-0002-000000000002",
      "name": "Jira Staging",
      "plugin": { "name": "jira", "pluginVendor": "rapid7", "pluginVersion": "11.3.0" },
      "orchestrator": { "id": "orch-1", "name": "Cloud Orchestrator", "status": "warning", "version": "3.1.4" },
      "connectionTests": [
        { "id": "ct-2", "connectionId": "c1a2b3c4-0002-0002-0002-000000000002", "status": "failed", "isStale": true, "errorMessage": "timed out", "createdAt": "2026-05-10T08:00:00Z" }
      ]
    },
    {
      "id": "c1a2b3c4-0003-0003-0003-000000000003",
      "name": "Microsoft Defender SIEM",
      "plugin": { "name": "microsoft-defender", "pluginVendor": "rapid7", "pluginVersion": "2.1.0" },
      "orchestrator": { "id": "orch-1", "name": "Cloud Orchestrator", "status": "healthy", "version": "3.1.4" },
      "connectionTests": [
        { "id": "ct-3", "connectionId": "c1a2b3c4-0003-0003-0003-000000000003", "status": "success", "isStale": false, "errorMessage": null, "createdAt": "2026-05-19T09:30:00Z" }
      ]
    }
  ],
  "metadata": { "total": 3 }
}
```

> Note: connection `0002` in `multi-connection.json` has a failed AND stale test — precedence puts it at `error` (error > missing_data). The contract test asserts this.

- [ ] **Step 2: Write the contract test (happy paths)**

`InsightConnectAdapterContractTest.java`:

```java
package com.rapid7.integrationregistry.adapter.insightconnect;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.testsupport.FixtureLoader;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class InsightConnectAdapterContractTest {

    private static final String BASE_URL = "https://icon.test.local";
    private static final String ICON_BASE = "https://icon.test.local";
    private static final String CONNECTIONS_URL =
        BASE_URL + "/api/public/v1/connections?includeTests=1";
    private static final String ORG_ID = "org-123";

    /**
     * Builds an adapter whose RestClient is bound to a MockRestServiceServer.
     * bindTo mutates the builder's request factory in place, so the client is
     * built AFTER bindTo (mirrors SampleContractTest).
     */
    private record Harness(InsightConnectAdapter adapter, MockRestServiceServer server) {}

    private static Harness harness() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        InsightConnectProperties props =
            new InsightConnectProperties(BASE_URL, ICON_BASE, java.time.Duration.ofSeconds(5));
        InsightConnectAdapter adapter =
            new InsightConnectAdapter(client, new ConnectionStatusMapper(), props);
        return new Harness(adapter, server);
    }

    private static void stub(MockRestServiceServer server, String fixture) {
        server.expect(requestTo(CONNECTIONS_URL))
              .andExpect(method(GET))
              .andRespond(withSuccess(FixtureLoader.read("insightconnect/" + fixture),
                                      MediaType.APPLICATION_JSON));
    }

    @Test
    void productName_shouldReturnInsightConnect() {
        assertThat(harness().adapter().productName()).isEqualTo("InsightConnect");
    }

    @Test
    void fetch_shouldReturnHealthyIntegration_whenOrchestratorHealthyAndTestSuccess() {
        // Arrange
        Harness h = harness();
        stub(h.server(), "healthy.json");
        // Act
        FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
        // Assert
        assertThat(result.integrations()).hasSize(1);
        NormalizedIntegration n = result.integrations().get(0);
        assertThat(n.status()).isEqualTo(IntegrationStatus.HEALTHY);
        assertThat(n.sourceIdentifier().sourceType()).isEqualTo("plugin_name");
        assertThat(n.sourceIdentifier().sourceValue()).isEqualTo("jira");
        assertThat(n.productName()).isEqualTo("InsightConnect");
        assertThat(n.integrationType()).isEqualTo("Automation Plugin");
        assertThat(n.integrationLabel()).isNull();
        assertThat(n.customerAccountId()).isEqualTo(ORG_ID);
        assertThat(n.configurationUrl())
            .isEqualTo(ICON_BASE + "/automation/connections/c1a2b3c4-0001-0001-0001-000000000001");
        assertThat(n.lastSuccessTimestamp()).isEqualTo(Instant.parse("2026-05-19T10:00:00Z"));
        h.server().verify();
    }

    @Test
    void fetch_shouldReturnError_whenTestFailed() {
        Harness h = harness();
        stub(h.server(), "error.json");
        FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
        assertThat(result.integrations().get(0).status()).isEqualTo(IntegrationStatus.ERROR);
        assertThat(result.integrations().get(0).lastSuccessTimestamp()).isNull();
        h.server().verify();
    }

    @Test
    void fetch_shouldReturnMissingData_whenTestStale() {
        Harness h = harness();
        stub(h.server(), "missing-data-stale.json");
        FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
        assertThat(result.integrations().get(0).status()).isEqualTo(IntegrationStatus.MISSING_DATA);
        h.server().verify();
    }

    @Test
    void fetch_shouldReturnMissingData_whenOrchestratorUnknown() {
        Harness h = harness();
        stub(h.server(), "missing-data-unknown.json");
        FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
        assertThat(result.integrations().get(0).status()).isEqualTo(IntegrationStatus.MISSING_DATA);
        h.server().verify();
    }

    @Test
    void fetch_shouldReturnWarning_whenOrchestratorWarning() {
        Harness h = harness();
        stub(h.server(), "warning.json");
        FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
        assertThat(result.integrations().get(0).status()).isEqualTo(IntegrationStatus.WARNING);
        h.server().verify();
    }

    @Test
    void fetch_shouldReturnDisabled_whenOrchestratorStopped() {
        Harness h = harness();
        stub(h.server(), "disabled.json");
        FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
        assertThat(result.integrations().get(0).status()).isEqualTo(IntegrationStatus.DISABLED);
        h.server().verify();
    }

    @Test
    void fetch_shouldReturnError_whenTestFailedAndOrchestratorWarning() {
        Harness h = harness();
        stub(h.server(), "precedence-failed-and-warning.json");
        FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
        assertThat(result.integrations().get(0).status()).isEqualTo(IntegrationStatus.ERROR);
        h.server().verify();
    }

    @Test
    void fetch_shouldNormalizeAllConnections_whenMultipleReturned() {
        // Arrange
        Harness h = harness();
        stub(h.server(), "multi-connection.json");
        // Act
        FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
        // Assert — order-independent
        assertThat(result.integrations()).hasSize(3);
        assertThat(result.integrations())
            .extracting(n -> n.sourceIdentifier().sourceValue())
            .containsExactlyInAnyOrder("jira", "jira", "microsoft-defender");
        // The jira/warning/stale/failed connection resolves to ERROR (precedence)
        assertThat(result.integrations())
            .filteredOn(n -> n.integrationId().equals("c1a2b3c4-0002-0002-0002-000000000002"))
            .singleElement()
            .satisfies(n -> assertThat(n.status()).isEqualTo(IntegrationStatus.ERROR));
        h.server().verify();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -o test -Dtest=InsightConnectAdapterContractTest`
Expected: COMPILATION FAILURE — `InsightConnectAdapter` does not exist / no matching constructor.

- [ ] **Step 4: Write `InsightConnectAdapter` (happy path)**

`InsightConnectAdapter.java`:

```java
package com.rapid7.integrationregistry.adapter.insightconnect;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationAdapter;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

/**
 * InsightConnect adapter. Fetches automation connections from
 * {@code GET /api/public/v1/connections?includeTests=1} and normalizes each to
 * a {@link NormalizedIntegration} (RFC-001 §InsightConnectAdapter).
 *
 * <p>Per ADR-002 the outbound call uses {@link RestClient}. Async fan-out is
 * the T07 coordinator's concern; this adapter makes one blocking call.
 */
@Component
public class InsightConnectAdapter implements IntegrationAdapter {

    static final String PRODUCT_NAME = "InsightConnect";
    static final String INTEGRATION_TYPE = "Automation Plugin";
    static final String SOURCE_TYPE = "plugin_name";
    private static final String CONNECTIONS_PATH = "/api/public/v1/connections?includeTests=1";

    private final RestClient restClient;
    private final ConnectionStatusMapper statusMapper;
    private final InsightConnectProperties properties;

    public InsightConnectAdapter(RestClient insightConnectRestClient,
                                 ConnectionStatusMapper statusMapper,
                                 InsightConnectProperties properties) {
        this.restClient = insightConnectRestClient;
        this.statusMapper = statusMapper;
        this.properties = properties;
    }

    @Override
    public String productName() {
        return PRODUCT_NAME;
    }

    @Override
    public FetchResult fetch(String orgId, HttpHeaders authHeaders)
            throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {

        ConnectionsResponse response = call(authHeaders);
        List<ConnectionViewModel> connections =
            response == null || response.data() == null ? List.of() : response.data();

        List<NormalizedIntegration> integrations = connections.stream()
            .map(cvm -> normalize(cvm, orgId))
            .toList();

        return new FetchResult(integrations, Instant.now());
    }

    private ConnectionsResponse call(HttpHeaders authHeaders)
            throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException {
        try {
            return restClient.get()
                .uri(CONNECTIONS_PATH)
                // TODO(T10): replace hand-rolled identity-header forwarding with
                // the canonical Class3HeaderAttacher once track 10 lands.
                .headers(h -> h.addAll(authHeaders))
                .retrieve()
                .body(ConnectionsResponse.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                throw new AdapterAuthException(
                    "InsightConnect auth failure: " + e.getStatusCode(), e);
            }
            throw new AdapterUpstreamException(
                "InsightConnect 4xx: " + e.getStatusCode(), e);
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            throw new AdapterUpstreamException(
                "InsightConnect 5xx: " + e.getStatusCode(), e);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new AdapterTimeoutException(
                "InsightConnect request failed (timeout/transport): " + e.getMessage(), e);
        } catch (org.springframework.web.client.RestClientException e) {
            // Body unparseable or other client-side failure — treat as upstream-broken.
            throw new AdapterUpstreamException(
                "InsightConnect response could not be processed: " + e.getMessage(), e);
        }
    }

    private NormalizedIntegration normalize(ConnectionViewModel cvm, String orgId) {
        ConnectionTest mostRecent = statusMapper.mostRecentByCreatedAt(cvm.connectionTests());
        String orchestratorStatus = cvm.orchestrator() == null ? null : cvm.orchestrator().status();
        IntegrationStatus status = statusMapper.deriveStatus(orchestratorStatus, mostRecent);
        Instant lastSuccess = statusMapper.deriveLastSuccess(cvm.connectionTests());
        String pluginName = cvm.plugin() == null ? null : cvm.plugin().name();

        return new NormalizedIntegration(
            cvm.id(),
            new SourceIdentifier(SOURCE_TYPE, pluginName),
            PRODUCT_NAME,
            INTEGRATION_TYPE,
            null,                       // integration_label: ICON has no per-instance name
            status,
            lastSuccess,
            configurationUrl(cvm),
            orgId);
    }

    private String configurationUrl(ConnectionViewModel cvm) {
        String apiUrl = cvm.configurationUrl();
        if (apiUrl != null && !apiUrl.isBlank()) {
            return apiUrl;
        }
        return properties.iconBase() + "/automation/connections/" + cvm.id();
    }
}
```

> **Worker note:** the catch order matters — `HttpClientErrorException` and `HttpServerErrorException` both extend `RestClientResponseException` (which extends `RestClientException`), and `ResourceAccessException` also extends `RestClientException`. Catch the specific subtypes first and the broad `RestClientException` last, exactly as written. `pluginName` may be null if a malformed connection omits `plugin` — `SourceIdentifier` requires non-null `sourceValue`, so a malformed record will throw `NullPointerException` during normalization. This is acceptable for the contract (fixtures always carry `plugin.name`); a defensive skip-and-warn is out of scope for this plan.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -o test -Dtest=InsightConnectAdapterContractTest`
Expected: PASS — all happy-path + multi-connection cases green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/adapter/insightconnect/InsightConnectAdapter.java \
        src/test/java/com/rapid7/integrationregistry/adapter/insightconnect/InsightConnectAdapterContractTest.java \
        src/test/resources/fixtures/insightconnect/
git commit -m "feat(track-05/wp-02): InsightConnectAdapter fetch + status-path fixtures + contract tests"
```

---

## Task 6: configuration_url preference + exception-mapping tests

Extend the contract test with the API-returned-URL preference and the four exception-mapping cases. The adapter already implements these (Task 5); this task proves them.

**Files:**
- Modify: `.../insightconnect/InsightConnectAdapterContractTest.java`

- [ ] **Step 1: Add the configuration_url-preference test**

Append to `InsightConnectAdapterContractTest`:

```java
    @Test
    void fetch_shouldPreferApiReturnedConfigurationUrl_whenPresent() {
        // Arrange — inline body carrying an explicit configurationUrl
        Harness h = harness();
        String body = """
            { "data": [ {
                "id": "c-9",
                "name": "Jira With URL",
                "plugin": { "name": "jira", "pluginVendor": "rapid7", "pluginVersion": "11.3.0" },
                "orchestrator": { "id": "o", "name": "Orch", "status": "healthy", "version": "3" },
                "configurationUrl": "https://custom.example/connections/c-9",
                "connectionTests": [
                    { "id": "ct", "connectionId": "c-9", "status": "success", "isStale": false, "errorMessage": null, "createdAt": "2026-05-19T10:00:00Z" }
                ]
            } ], "metadata": { "total": 1 } }
            """;
        h.server().expect(requestTo(CONNECTIONS_URL)).andExpect(method(GET))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        // Act
        FetchResult result = h.adapter().fetch(ORG_ID, new HttpHeaders());
        // Assert
        assertThat(result.integrations().get(0).configurationUrl())
            .isEqualTo("https://custom.example/connections/c-9");
        h.server().verify();
    }
```

- [ ] **Step 2: Add the exception-mapping tests**

Add these imports at the top of the test file:

```java
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.http.HttpStatus;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

Append these tests:

```java
    @Test
    void fetch_shouldThrowUpstream_whenServerReturns503() {
        Harness h = harness();
        h.server().expect(requestTo(CONNECTIONS_URL)).andExpect(method(GET))
            .andRespond(MockRestResponseCreators.withServerError());
        assertThatThrownBy(() -> h.adapter().fetch(ORG_ID, new HttpHeaders()))
            .isInstanceOf(AdapterUpstreamException.class)
            .satisfies(ex -> {
                assertThat(((AdapterUpstreamException) ex).reasonCode()).isEqualTo("upstream_5xx");
                assertThat(ex.getCause()).isNotNull();
            });
        h.server().verify();
    }

    @Test
    void fetch_shouldThrowAuth_whenServerReturns401() {
        Harness h = harness();
        h.server().expect(requestTo(CONNECTIONS_URL)).andExpect(method(GET))
            .andRespond(MockRestResponseCreators.withUnauthorizedRequest());
        assertThatThrownBy(() -> h.adapter().fetch(ORG_ID, new HttpHeaders()))
            .isInstanceOf(AdapterAuthException.class)
            .satisfies(ex -> assertThat(((AdapterAuthException) ex).reasonCode()).isEqualTo("auth_failure"));
        h.server().verify();
    }

    @Test
    void fetch_shouldThrowAuth_whenServerReturns403() {
        Harness h = harness();
        h.server().expect(requestTo(CONNECTIONS_URL)).andExpect(method(GET))
            .andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> h.adapter().fetch(ORG_ID, new HttpHeaders()))
            .isInstanceOf(AdapterAuthException.class);
        h.server().verify();
    }

    @Test
    void fetch_shouldThrowUpstream_whenServerReturns404() {
        Harness h = harness();
        h.server().expect(requestTo(CONNECTIONS_URL)).andExpect(method(GET))
            .andRespond(MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> h.adapter().fetch(ORG_ID, new HttpHeaders()))
            .isInstanceOf(AdapterUpstreamException.class);
        h.server().verify();
    }
```

> **Worker note on the timeout case:** `MockRestServiceServer` cannot easily simulate a socket timeout (it has no real transport). The `ResourceAccessException` → `AdapterTimeoutException` path is covered structurally by the catch block and verified by code review, not a `MockRestServiceServer` stub. Do NOT attempt a real-socket timeout test here — it would be flaky. If a unit-level test is desired, it can be added later by injecting a `RestClient` whose request factory throws `ResourceAccessException`; that is out of scope for this plan.

- [ ] **Step 3: Run the full contract test**

Run: `./mvnw -q -o test -Dtest=InsightConnectAdapterContractTest`
Expected: PASS — all cases including the four exception-mapping tests.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/adapter/insightconnect/InsightConnectAdapterContractTest.java
git commit -m "test(track-05/wp-02): configuration_url preference + exception-mapping contract tests"
```

---

## Task 7: Full verify + final checkpoint

- [ ] **Step 1: Run the full build**

Run: `./mvnw -q -o verify`
Expected: BUILD SUCCESS — JUnit (all `insightconnect` tests + existing suite), ArchUnit (adapter package respects layer boundaries), PMD (no empty catch, no unused code, no placeholder stubs). The `// TODO(T10)` marker is on working code and does not trip PMD's placeholder rules.

- [ ] **Step 2: If PMD or ArchUnit flags anything, fix inline and re-run**

Common possibilities and resolutions:
- PMD `AvoidDuplicateLiterals` on repeated status strings in the mapper → already centralized as constants; if it fires on test code, that's acceptable per the ruleset (or annotate the specific method with `@SuppressWarnings("PMD.AvoidDuplicateLiterals")` with a one-line justification).
- ArchUnit complaint that `adapter.insightconnect` depends on a forbidden layer → it must not import `controller`/`service`/`coordinator`/`aggregator`/`mapping`. The design imports only `adapter` contract types + Spring web; if a stray import appears, remove it.

Re-run `./mvnw -q -o verify` until green.

- [ ] **Step 3: STOP — return control to execute-plan**

Do NOT invoke `superpowers:finishing-a-development-branch`. All tasks are checked off and `mvn verify` is clean. Report completion to `execute-plan`, which runs the functional-review, simplify, and external-review gates before any PR.

---

## Self-Review (completed by writing-plans)

**Spec coverage:** Every spec section maps to a task — status table + precedence + last-success + most-recent selection + no-test + unrecognized-orchestrator (Task 2); DTOs/wire shape (Tasks 1, 3); config + RestClient bean (Task 4); fetch + mapping + configuration_url + exception translation (Tasks 5, 6); fixtures per status path + multi-connection (Task 5); ArchUnit/PMD/verify (Task 7). The "other 4xx → upstream" and "unrecognized orchestrator → missing_data + WARN" decisions are implemented (Tasks 5, 2) and tested (Tasks 6, 2).

**Placeholder scan:** No TBD/TODO-as-stub. The single `// TODO(T10)` is a documented integration marker on working code, explicitly called out in the spec and the no-placeholder note.

**Type consistency:** `ConnectionStatusMapper` method names (`deriveStatus`, `deriveLastSuccess`, `mostRecentByCreatedAt`) are identical across Task 2 (def + test) and Task 5 (call site). `NormalizedIntegration` constructor argument order matches the plan-01 record (`integrationId, sourceIdentifier, productName, integrationType, integrationLabel, status, lastSuccessTimestamp, configurationUrl, customerAccountId`). `InsightConnectAdapter` constructor signature is identical in the config bean wiring (implicit), the test harness, and the class definition.
