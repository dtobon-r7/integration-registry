# Adapter Contract Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Stop after the last task — do NOT auto-invoke `superpowers:finishing-a-development-branch`.** Control returns to the parent `execute-plan` skill for validation gates (functional review, simplify, external code review) before any PR is opened.

**Goal:** Land the `IntegrationAdapter` seam — the interface, the `FetchResult`, `NormalizedIntegration`, and `SourceIdentifier` records, the `IntegrationStatus` enum, and the three checked adapter exception types — as the migration-safety boundary RFC-001 names.

**Architecture:** Single Java package `com.rapid7.integrationregistry.adapter`, types and shapes only, no behavior. Records use compact constructors with `Objects.requireNonNull(...)` guards keyed by package-private `FIELD_<NAME>` constants. The same constants are referenced by tests so a rename in the type cannot leave a stale-message assertion. No Spring annotations, no JSON annotations, no fixtures.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Maven (`./mvnw`), JUnit 5, AssertJ (`assertThat` — already on the test classpath via `spring-boot-starter-test`), ArchUnit + PMD as build gates.

**Pre-flight context for any worker picking this up cold:**
- The branch `uiv/track-05/01-adapter-contract` is already checked out in the repo at `repos/platform/integration-registry`.
- The package `com.rapid7.integrationregistry.adapter` already exists and contains only `package-info.java`. Add the new files alongside it.
- TESTING.md governs test conventions: unit tests under `src/test/java/com/rapid7/integrationregistry/adapter/`, no Spring context for this plan, no fixtures, `methodName_shouldDoX_whenY()` naming, AAA structure with `// Arrange / // Act / // Assert` comments.
- ArchUnit's `adapterLayer_shouldNotDependOnInternalLayers` is enforced. Adapter code may depend on JDK + `org.springframework.http..`; no other internal packages.
- PMD rules to remember: `AvoidDuplicateLiterals` (already addressed by field-name constants), `CommentContent` (no `TODO|FIXME|HACK|XXX`), `EmptyCatchBlock`, `UnusedLocalVariable`, `UnusedFormalParameter`. Curated for LLM failure modes — code that compiles but contains placeholders will fail the build.
- Build gate: `./mvnw verify` runs JUnit + ArchUnit + PMD. Each task ends with a focused test run; the final task runs full `verify`.
- Spec lives at `docs/superpowers/specs/2026-05-26-01-adapter-contract-design.md` if you need to re-check field-by-field details.

**Hard non-goals (push back if any task drifts into them):**
- No adapter implementation (ICON, IDR — plan 02 / track 06)
- No status mapping logic, fixtures, or contract tests
- No fan-out, cache, retry, or timeout enforcement (track 07)
- No vendor-name resolution (T08)
- No outbound Class 3 header attachment (T10)
- No KB doc updates
- No JSON serialization annotations (T09 owns wire-form)
- No Spring context, no `@SpringBootTest`, no `@WebMvcTest`, no `@Component`/`@Service`

---

## File Structure

All new code under `src/main/java/com/rapid7/integrationregistry/adapter/` and `src/test/java/com/rapid7/integrationregistry/adapter/`. The existing `package-info.java` stays as-is.

| File | Responsibility |
|---|---|
| `adapter/IntegrationStatus.java` | The five health-state enum values |
| `adapter/SourceIdentifier.java` | Raw `(sourceType, sourceValue)` pair |
| `adapter/NormalizedIntegration.java` | Nine-field normalized record |
| `adapter/FetchResult.java` | List of integrations + fetch timestamp |
| `adapter/AdapterTimeoutException.java` | Checked exception for timeouts |
| `adapter/AdapterAuthException.java` | Checked exception for 4xx/auth |
| `adapter/AdapterUpstreamException.java` | Checked exception for 5xx/transport |
| `adapter/IntegrationAdapter.java` | The interface |

Tests, one per main type that has runtime semantics (the interface itself has none, so no `IntegrationAdapterTest`):

| File | Coverage |
|---|---|
| `adapter/IntegrationStatusTest.java` | Five values, `valueOf` resolution |
| `adapter/SourceIdentifierTest.java` | Happy path + 2 null rejections |
| `adapter/AdapterExceptionsTest.java` | Message/cause ctors + independent catchability |
| `adapter/FetchResultTest.java` | Happy path, empty list, 2 null rejections, defensive-copy immutability |
| `adapter/NormalizedIntegrationTest.java` | Happy path + 7 null rejections + 2 nullable acceptances |

**Task ordering rationale:** dependencies dictate the sequence. `IntegrationStatus` and the exceptions have no dependencies, so they go first. `SourceIdentifier` is needed by `NormalizedIntegration`. `NormalizedIntegration` is needed by `FetchResult`. `FetchResult` and the exceptions are needed by `IntegrationAdapter`. The final task is full-build verification.

---

## Task 1: `IntegrationStatus` enum

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/adapter/IntegrationStatus.java`
- Test: `src/test/java/com/rapid7/integrationregistry/adapter/IntegrationStatusTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/adapter/IntegrationStatusTest.java`:

```java
package com.rapid7.integrationregistry.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationStatusTest {

    @Test
    void values_shouldContainExactlyFiveStates_whenInspected() {
        // Arrange
        // (no setup — IntegrationStatus.values() is a class-level invariant)

        // Act
        IntegrationStatus[] all = IntegrationStatus.values();

        // Assert
        assertThat(all).containsExactly(
            IntegrationStatus.HEALTHY,
            IntegrationStatus.WARNING,
            IntegrationStatus.ERROR,
            IntegrationStatus.MISSING_DATA,
            IntegrationStatus.DISABLED
        );
    }

    @Test
    void valueOf_shouldResolveAllFiveConstants_whenLookedUpByName() {
        // Arrange
        String[] names = {"HEALTHY", "WARNING", "ERROR", "MISSING_DATA", "DISABLED"};

        // Act + Assert
        for (String name : names) {
            assertThat(IntegrationStatus.valueOf(name).name()).isEqualTo(name);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=IntegrationStatusTest`
Expected: FAIL with compilation error (`IntegrationStatus` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/adapter/IntegrationStatus.java`:

```java
package com.rapid7.integrationregistry.adapter;

public enum IntegrationStatus {
    HEALTHY, WARNING, ERROR, MISSING_DATA, DISABLED
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=IntegrationStatusTest`
Expected: PASS — both tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/adapter/IntegrationStatus.java src/test/java/com/rapid7/integrationregistry/adapter/IntegrationStatusTest.java
git commit -m "feat(track-05/wp-01): add IntegrationStatus enum"
```

---

## Task 2: `SourceIdentifier` record

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/adapter/SourceIdentifier.java`
- Test: `src/test/java/com/rapid7/integrationregistry/adapter/SourceIdentifierTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/adapter/SourceIdentifierTest.java`:

```java
package com.rapid7.integrationregistry.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SourceIdentifierTest {

    @Test
    void constructor_shouldBuildRecord_whenBothFieldsProvided() {
        // Arrange
        String sourceType = "plugin_name";
        String sourceValue = "jira";

        // Act
        SourceIdentifier identifier = new SourceIdentifier(sourceType, sourceValue);

        // Assert
        assertThat(identifier.sourceType()).isEqualTo(sourceType);
        assertThat(identifier.sourceValue()).isEqualTo(sourceValue);
    }

    @Test
    void constructor_shouldThrowNPE_whenSourceTypeNull() {
        // Arrange
        String sourceValue = "jira";

        // Act + Assert
        assertThatNullPointerException()
            .isThrownBy(() -> new SourceIdentifier(null, sourceValue))
            .withMessage(SourceIdentifier.FIELD_SOURCE_TYPE);
    }

    @Test
    void constructor_shouldThrowNPE_whenSourceValueNull() {
        // Arrange
        String sourceType = "plugin_name";

        // Act + Assert
        assertThatNullPointerException()
            .isThrownBy(() -> new SourceIdentifier(sourceType, null))
            .withMessage(SourceIdentifier.FIELD_SOURCE_VALUE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=SourceIdentifierTest`
Expected: FAIL with compilation error (`SourceIdentifier` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/adapter/SourceIdentifier.java`:

```java
package com.rapid7.integrationregistry.adapter;

import java.util.Objects;

public record SourceIdentifier(String sourceType, String sourceValue) {

    static final String FIELD_SOURCE_TYPE = "sourceType";
    static final String FIELD_SOURCE_VALUE = "sourceValue";

    public SourceIdentifier {
        Objects.requireNonNull(sourceType, FIELD_SOURCE_TYPE);
        Objects.requireNonNull(sourceValue, FIELD_SOURCE_VALUE);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=SourceIdentifierTest`
Expected: PASS — all three tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/adapter/SourceIdentifier.java src/test/java/com/rapid7/integrationregistry/adapter/SourceIdentifierTest.java
git commit -m "feat(track-05/wp-01): add SourceIdentifier record with null guards"
```

---

## Task 3: Three adapter exception classes

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/adapter/AdapterTimeoutException.java`
- Create: `src/main/java/com/rapid7/integrationregistry/adapter/AdapterAuthException.java`
- Create: `src/main/java/com/rapid7/integrationregistry/adapter/AdapterUpstreamException.java`
- Test: `src/test/java/com/rapid7/integrationregistry/adapter/AdapterExceptionsTest.java`

**Note on PMD:** The three exception classes have identical bodies modulo the type name. `AvoidDuplicateLiterals` does NOT flag identical class structures, only identical literal strings — and these classes contain no string literals at all. `IdenticalCatchBranches` is about catch blocks, not class structure. Three separate classes is required by the spec (independent catchability) and is PMD-clean.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/adapter/AdapterExceptionsTest.java`:

```java
package com.rapid7.integrationregistry.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class AdapterExceptionsTest {

    @Test
    void messageCtor_shouldRoundTripMessage_whenAdapterTimeoutExceptionThrown() {
        // Arrange
        String message = "ICON read timeout after 5s";

        // Act
        AdapterTimeoutException thrown = new AdapterTimeoutException(message);

        // Assert
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isNull();
    }

    @Test
    void messageCtor_shouldRoundTripMessage_whenAdapterAuthExceptionThrown() {
        // Arrange
        String message = "401 from ICON";

        // Act
        AdapterAuthException thrown = new AdapterAuthException(message);

        // Assert
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isNull();
    }

    @Test
    void messageCtor_shouldRoundTripMessage_whenAdapterUpstreamExceptionThrown() {
        // Arrange
        String message = "ICON 503";

        // Act
        AdapterUpstreamException thrown = new AdapterUpstreamException(message);

        // Assert
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isNull();
    }

    @Test
    void messageCauseCtor_shouldRoundTripBoth_whenAdapterTimeoutExceptionThrown() {
        // Arrange
        String message = "ICON read timeout";
        Throwable cause = new RuntimeException("socket read deadline");

        // Act
        AdapterTimeoutException thrown = new AdapterTimeoutException(message, cause);

        // Assert
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isSameAs(cause);
    }

    @Test
    void messageCauseCtor_shouldRoundTripBoth_whenAdapterAuthExceptionThrown() {
        // Arrange
        String message = "401 from ICON";
        Throwable cause = new RuntimeException("WebClientResponseException 401");

        // Act
        AdapterAuthException thrown = new AdapterAuthException(message, cause);

        // Assert
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isSameAs(cause);
    }

    @Test
    void messageCauseCtor_shouldRoundTripBoth_whenAdapterUpstreamExceptionThrown() {
        // Arrange
        String message = "ICON 503";
        Throwable cause = new RuntimeException("WebClientResponseException 503");

        // Act
        AdapterUpstreamException thrown = new AdapterUpstreamException(message, cause);

        // Assert
        assertThat(thrown.getMessage()).isEqualTo(message);
        assertThat(thrown.getCause()).isSameAs(cause);
    }

    @Test
    void independentlyCatchable_shouldDistinguishEachType_whenThrownInSeparateBlocks() {
        // Arrange
        // Each try/catch block catches exactly one of the three types. If a future
        // refactor introduced a common parent above Exception, the wrong block would
        // catch and this test would fail.

        // Act + Assert — Timeout
        try {
            throw new AdapterTimeoutException("timeout");
        } catch (AdapterAuthException | AdapterUpstreamException unexpected) {
            fail("AdapterTimeoutException leaked into a non-timeout catch", unexpected);
        } catch (AdapterTimeoutException expected) {
            assertThat(expected).isInstanceOf(AdapterTimeoutException.class);
        }

        // Act + Assert — Auth
        try {
            throw new AdapterAuthException("auth");
        } catch (AdapterTimeoutException | AdapterUpstreamException unexpected) {
            fail("AdapterAuthException leaked into a non-auth catch", unexpected);
        } catch (AdapterAuthException expected) {
            assertThat(expected).isInstanceOf(AdapterAuthException.class);
        }

        // Act + Assert — Upstream
        try {
            throw new AdapterUpstreamException("upstream");
        } catch (AdapterTimeoutException | AdapterAuthException unexpected) {
            fail("AdapterUpstreamException leaked into a non-upstream catch", unexpected);
        } catch (AdapterUpstreamException expected) {
            assertThat(expected).isInstanceOf(AdapterUpstreamException.class);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AdapterExceptionsTest`
Expected: FAIL with compilation errors — none of the three exception classes exist.

- [ ] **Step 3: Write minimal implementation — `AdapterTimeoutException`**

Create `src/main/java/com/rapid7/integrationregistry/adapter/AdapterTimeoutException.java`:

```java
package com.rapid7.integrationregistry.adapter;

public class AdapterTimeoutException extends Exception {

    public AdapterTimeoutException(String message) {
        super(message);
    }

    public AdapterTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Write minimal implementation — `AdapterAuthException`**

Create `src/main/java/com/rapid7/integrationregistry/adapter/AdapterAuthException.java`:

```java
package com.rapid7.integrationregistry.adapter;

public class AdapterAuthException extends Exception {

    public AdapterAuthException(String message) {
        super(message);
    }

    public AdapterAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 5: Write minimal implementation — `AdapterUpstreamException`**

Create `src/main/java/com/rapid7/integrationregistry/adapter/AdapterUpstreamException.java`:

```java
package com.rapid7.integrationregistry.adapter;

public class AdapterUpstreamException extends Exception {

    public AdapterUpstreamException(String message) {
        super(message);
    }

    public AdapterUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=AdapterExceptionsTest`
Expected: PASS — all seven tests green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/adapter/AdapterTimeoutException.java src/main/java/com/rapid7/integrationregistry/adapter/AdapterAuthException.java src/main/java/com/rapid7/integrationregistry/adapter/AdapterUpstreamException.java src/test/java/com/rapid7/integrationregistry/adapter/AdapterExceptionsTest.java
git commit -m "feat(track-05/wp-01): add three checked adapter exception types"
```

---

## Task 4: `NormalizedIntegration` record

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/adapter/NormalizedIntegration.java`
- Test: `src/test/java/com/rapid7/integrationregistry/adapter/NormalizedIntegrationTest.java`

**Why this comes before `FetchResult`:** `FetchResult` carries a `List<NormalizedIntegration>`, so `NormalizedIntegration` must compile first. `NormalizedIntegration` itself depends only on already-built `SourceIdentifier` (Task 2) and `IntegrationStatus` (Task 1).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/adapter/NormalizedIntegrationTest.java`:

```java
package com.rapid7.integrationregistry.adapter;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class NormalizedIntegrationTest {

    private static final String INTEGRATION_ID = "c_456";
    private static final SourceIdentifier SOURCE_IDENTIFIER =
        new SourceIdentifier("plugin_name", "jira");
    private static final String PRODUCT_NAME = "InsightConnect";
    private static final String INTEGRATION_TYPE = "Automation Plugin";
    private static final String INTEGRATION_LABEL = null;
    private static final IntegrationStatus STATUS = IntegrationStatus.HEALTHY;
    private static final Instant LAST_SUCCESS = Instant.parse("2026-05-26T10:00:00Z");
    private static final String CONFIGURATION_URL =
        "https://icon.example/automation/connections/c_456";
    private static final String CUSTOMER_ACCOUNT_ID = "org_abc";

    @Test
    void constructor_shouldBuildRecord_whenAllFieldsProvided() {
        // Arrange
        // (constants above)

        // Act
        NormalizedIntegration record = new NormalizedIntegration(
            INTEGRATION_ID,
            SOURCE_IDENTIFIER,
            PRODUCT_NAME,
            INTEGRATION_TYPE,
            "my-jira",            // integrationLabel populated this time
            STATUS,
            LAST_SUCCESS,
            CONFIGURATION_URL,
            CUSTOMER_ACCOUNT_ID
        );

        // Assert
        assertThat(record.integrationId()).isEqualTo(INTEGRATION_ID);
        assertThat(record.sourceIdentifier()).isEqualTo(SOURCE_IDENTIFIER);
        assertThat(record.productName()).isEqualTo(PRODUCT_NAME);
        assertThat(record.integrationType()).isEqualTo(INTEGRATION_TYPE);
        assertThat(record.integrationLabel()).isEqualTo("my-jira");
        assertThat(record.status()).isEqualTo(STATUS);
        assertThat(record.lastSuccessTimestamp()).isEqualTo(LAST_SUCCESS);
        assertThat(record.configurationUrl()).isEqualTo(CONFIGURATION_URL);
        assertThat(record.customerAccountId()).isEqualTo(CUSTOMER_ACCOUNT_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenIntegrationIdNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                null, SOURCE_IDENTIFIER, PRODUCT_NAME, INTEGRATION_TYPE,
                INTEGRATION_LABEL, STATUS, LAST_SUCCESS, CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID))
            .withMessage(NormalizedIntegration.FIELD_INTEGRATION_ID);
    }

    @Test
    void constructor_shouldThrowNPE_whenSourceIdentifierNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                INTEGRATION_ID, null, PRODUCT_NAME, INTEGRATION_TYPE,
                INTEGRATION_LABEL, STATUS, LAST_SUCCESS, CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID))
            .withMessage(NormalizedIntegration.FIELD_SOURCE_IDENTIFIER);
    }

    @Test
    void constructor_shouldThrowNPE_whenProductNameNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                INTEGRATION_ID, SOURCE_IDENTIFIER, null, INTEGRATION_TYPE,
                INTEGRATION_LABEL, STATUS, LAST_SUCCESS, CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID))
            .withMessage(NormalizedIntegration.FIELD_PRODUCT_NAME);
    }

    @Test
    void constructor_shouldThrowNPE_whenIntegrationTypeNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                INTEGRATION_ID, SOURCE_IDENTIFIER, PRODUCT_NAME, null,
                INTEGRATION_LABEL, STATUS, LAST_SUCCESS, CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID))
            .withMessage(NormalizedIntegration.FIELD_INTEGRATION_TYPE);
    }

    @Test
    void constructor_shouldThrowNPE_whenStatusNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                INTEGRATION_ID, SOURCE_IDENTIFIER, PRODUCT_NAME, INTEGRATION_TYPE,
                INTEGRATION_LABEL, null, LAST_SUCCESS, CONFIGURATION_URL,
                CUSTOMER_ACCOUNT_ID))
            .withMessage(NormalizedIntegration.FIELD_STATUS);
    }

    @Test
    void constructor_shouldThrowNPE_whenConfigurationUrlNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                INTEGRATION_ID, SOURCE_IDENTIFIER, PRODUCT_NAME, INTEGRATION_TYPE,
                INTEGRATION_LABEL, STATUS, LAST_SUCCESS, null,
                CUSTOMER_ACCOUNT_ID))
            .withMessage(NormalizedIntegration.FIELD_CONFIGURATION_URL);
    }

    @Test
    void constructor_shouldThrowNPE_whenCustomerAccountIdNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> new NormalizedIntegration(
                INTEGRATION_ID, SOURCE_IDENTIFIER, PRODUCT_NAME, INTEGRATION_TYPE,
                INTEGRATION_LABEL, STATUS, LAST_SUCCESS, CONFIGURATION_URL,
                null))
            .withMessage(NormalizedIntegration.FIELD_CUSTOMER_ACCOUNT_ID);
    }

    @Test
    void constructor_shouldAcceptNullIntegrationLabel_whenSourceProductHasNoPerInstanceName() {
        // Arrange
        // (RFC: ICON has no per-instance customer-given name, so integrationLabel is null)

        // Act
        NormalizedIntegration record = new NormalizedIntegration(
            INTEGRATION_ID, SOURCE_IDENTIFIER, PRODUCT_NAME, INTEGRATION_TYPE,
            null, STATUS, LAST_SUCCESS, CONFIGURATION_URL, CUSTOMER_ACCOUNT_ID);

        // Assert
        assertThat(record.integrationLabel()).isNull();
    }

    @Test
    void constructor_shouldAcceptNullLastSuccessTimestamp_whenNoSuccessfulActivityRecorded() {
        // Arrange
        // (RFC: null when no successful activity recorded)

        // Act
        NormalizedIntegration record = new NormalizedIntegration(
            INTEGRATION_ID, SOURCE_IDENTIFIER, PRODUCT_NAME, INTEGRATION_TYPE,
            INTEGRATION_LABEL, STATUS, null, CONFIGURATION_URL, CUSTOMER_ACCOUNT_ID);

        // Assert
        assertThat(record.lastSuccessTimestamp()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=NormalizedIntegrationTest`
Expected: FAIL with compilation error (`NormalizedIntegration` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/adapter/NormalizedIntegration.java`:

```java
package com.rapid7.integrationregistry.adapter;

import java.time.Instant;
import java.util.Objects;

public record NormalizedIntegration(
    String integrationId,
    SourceIdentifier sourceIdentifier,
    String productName,
    String integrationType,
    String integrationLabel,
    IntegrationStatus status,
    Instant lastSuccessTimestamp,
    String configurationUrl,
    String customerAccountId
) {

    static final String FIELD_INTEGRATION_ID = "integrationId";
    static final String FIELD_SOURCE_IDENTIFIER = "sourceIdentifier";
    static final String FIELD_PRODUCT_NAME = "productName";
    static final String FIELD_INTEGRATION_TYPE = "integrationType";
    static final String FIELD_STATUS = "status";
    static final String FIELD_CONFIGURATION_URL = "configurationUrl";
    static final String FIELD_CUSTOMER_ACCOUNT_ID = "customerAccountId";

    public NormalizedIntegration {
        Objects.requireNonNull(integrationId, FIELD_INTEGRATION_ID);
        Objects.requireNonNull(sourceIdentifier, FIELD_SOURCE_IDENTIFIER);
        Objects.requireNonNull(productName, FIELD_PRODUCT_NAME);
        Objects.requireNonNull(integrationType, FIELD_INTEGRATION_TYPE);
        Objects.requireNonNull(status, FIELD_STATUS);
        Objects.requireNonNull(configurationUrl, FIELD_CONFIGURATION_URL);
        Objects.requireNonNull(customerAccountId, FIELD_CUSTOMER_ACCOUNT_ID);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=NormalizedIntegrationTest`
Expected: PASS — all ten tests green.

**PMD note:** PMD's `ExcessiveParameterList` rule defaults to a threshold of 10. This record has nine parameters — under the limit. If PMD flags it, the limit was tightened locally and is the wrong call here; the field set comes verbatim from RFC-001. Do not split the record into a builder to silence the rule.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/adapter/NormalizedIntegration.java src/test/java/com/rapid7/integrationregistry/adapter/NormalizedIntegrationTest.java
git commit -m "feat(track-05/wp-01): add NormalizedIntegration record with field-name constants"
```

---

## Task 5: `FetchResult` record

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/adapter/FetchResult.java`
- Test: `src/test/java/com/rapid7/integrationregistry/adapter/FetchResultTest.java`

**Why the defensive copy matters:** records are only shallow-immutable. Without `List.copyOf` in the compact constructor, a caller could mutate the backing list after construction and silently break downstream consumers that relied on `FetchResult` being immutable. `List.copyOf` also rejects null elements — fail-fast aligned with the rest of the null discipline.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/adapter/FetchResultTest.java`:

```java
package com.rapid7.integrationregistry.adapter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class FetchResultTest {

    private static final NormalizedIntegration SAMPLE = new NormalizedIntegration(
        "c_456",
        new SourceIdentifier("plugin_name", "jira"),
        "InsightConnect",
        "Automation Plugin",
        null,
        IntegrationStatus.HEALTHY,
        Instant.parse("2026-05-26T10:00:00Z"),
        "https://icon.example/automation/connections/c_456",
        "org_abc"
    );

    private static final Instant FETCHED_AT = Instant.parse("2026-05-26T10:00:00Z");

    @Test
    void constructor_shouldBuildRecord_whenIntegrationsListAndTimestampProvided() {
        // Arrange
        List<NormalizedIntegration> integrations = List.of(SAMPLE);

        // Act
        FetchResult result = new FetchResult(integrations, FETCHED_AT);

        // Assert
        assertThat(result.integrations()).containsExactly(SAMPLE);
        assertThat(result.fetchedAt()).isEqualTo(FETCHED_AT);
    }

    @Test
    void constructor_shouldAcceptEmptyList_whenNoIntegrationsReturned() {
        // Arrange
        List<NormalizedIntegration> empty = List.of();

        // Act
        FetchResult result = new FetchResult(empty, FETCHED_AT);

        // Assert
        assertThat(result.integrations()).isEmpty();
        assertThat(result.fetchedAt()).isEqualTo(FETCHED_AT);
    }

    @Test
    void constructor_shouldThrowNPE_whenIntegrationsListNull() {
        // Act + Assert
        assertThatNullPointerException()
            .isThrownBy(() -> new FetchResult(null, FETCHED_AT))
            .withMessage(FetchResult.FIELD_INTEGRATIONS);
    }

    @Test
    void constructor_shouldThrowNPE_whenFetchedAtNull() {
        // Arrange
        List<NormalizedIntegration> integrations = List.of(SAMPLE);

        // Act + Assert
        assertThatNullPointerException()
            .isThrownBy(() -> new FetchResult(integrations, null))
            .withMessage(FetchResult.FIELD_FETCHED_AT);
    }

    @Test
    void integrations_shouldBeImmutable_whenSourceListMutatedAfterConstruction() {
        // Arrange
        List<NormalizedIntegration> mutable = new ArrayList<>();
        mutable.add(SAMPLE);
        FetchResult result = new FetchResult(mutable, FETCHED_AT);

        // Act
        mutable.clear();

        // Assert — record's view is unaffected by post-construction mutation of the source list
        assertThat(result.integrations()).containsExactly(SAMPLE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=FetchResultTest`
Expected: FAIL with compilation error (`FetchResult` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/adapter/FetchResult.java`:

```java
package com.rapid7.integrationregistry.adapter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record FetchResult(
    List<NormalizedIntegration> integrations,
    Instant fetchedAt
) {

    static final String FIELD_INTEGRATIONS = "integrations";
    static final String FIELD_FETCHED_AT = "fetchedAt";

    public FetchResult {
        Objects.requireNonNull(integrations, FIELD_INTEGRATIONS);
        Objects.requireNonNull(fetchedAt, FIELD_FETCHED_AT);
        integrations = List.copyOf(integrations);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=FetchResultTest`
Expected: PASS — all five tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/adapter/FetchResult.java src/test/java/com/rapid7/integrationregistry/adapter/FetchResultTest.java
git commit -m "feat(track-05/wp-01): add FetchResult record with defensive list copy"
```

---

## Task 6: `IntegrationAdapter` interface

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/adapter/IntegrationAdapter.java`

**Why no test for the interface:** the interface has no runtime semantics — it only declares method signatures and `throws` clauses. Compilation IS the test (the package exists, all referenced types compile, ArchUnit allows the dependencies). A dedicated test would have to either implement the interface (defeating the purpose of "no behavior in this PR") or use reflection to assert signature shape (testing the compiler). Skipped deliberately.

- [ ] **Step 1: Write the interface**

Create `src/main/java/com/rapid7/integrationregistry/adapter/IntegrationAdapter.java`:

```java
package com.rapid7.integrationregistry.adapter;

import org.springframework.http.HttpHeaders;

public interface IntegrationAdapter {

    String productName();

    FetchResult fetch(String orgId, HttpHeaders authHeaders)
        throws AdapterTimeoutException,
               AdapterAuthException,
               AdapterUpstreamException;
}
```

- [ ] **Step 2: Verify the package compiles and ArchUnit still passes**

Run: `./mvnw test -Dtest=LayerDependencyRulesTest`
Expected: PASS — adapter layer dependency rules continue to hold (the new dependency on `org.springframework.http.HttpHeaders` is not blocked by `adapterLayer_shouldNotDependOnInternalLayers`; that rule blocks only the five internal package patterns).

- [ ] **Step 3: Run all adapter-package tests to confirm nothing regressed**

Run: `./mvnw test -Dtest='IntegrationStatusTest,SourceIdentifierTest,AdapterExceptionsTest,NormalizedIntegrationTest,FetchResultTest,LayerDependencyRulesTest'`
Expected: PASS — every test from Tasks 1–5 plus the architecture rules.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/adapter/IntegrationAdapter.java
git commit -m "feat(track-05/wp-01): add IntegrationAdapter interface"
```

---

## Task 7: Full-build verification checkpoint

**Files:** none — pure verification.

This is the gate that proves the work plan's Acceptance signals: every test green, ArchUnit green, PMD green.

- [ ] **Step 1: Run full verify**

Run: `./mvnw verify`
Expected:
- `BUILD SUCCESS`
- All JUnit tests pass: the five new test classes from Tasks 1–5 plus the existing `LayerDependencyRulesTest`, `LayerDependencyViolationDetectionTest`, and `IntegrationRegistryApplicationTests`.
- ArchUnit reports zero violations (the new types respect `adapterLayer_shouldNotDependOnInternalLayers`).
- PMD reports zero violations across both `src/main/java` and `src/test/java`.

- [ ] **Step 2: If `./mvnw verify` fails, diagnose and fix**

Common failure modes specific to this plan:
- **PMD `AvoidDuplicateLiterals`** — only fires if a literal is repeated 4+ times in the same file. The `FIELD_<NAME>` constants prevent this. If it fires, look for an inadvertent string repeated in test setup.
- **PMD `UnusedFormalParameter`** on the interface — should not fire on interface methods, but if it does on a stub somewhere, the stub shouldn't exist.
- **PMD `CommentContent`** — would fire if any comment contains `TODO|FIXME|HACK|XXX`. Remove any such comment; if you genuinely need to mark deferred work, capture it as a follow-up work plan via the `work-plans` skill instead.
- **ArchUnit failure** — if `adapterLayer_shouldNotDependOnInternalLayers` flags something, you imported a type from `..controller..`, `..service..`, `..coordinator..`, `..aggregator..`, or `..mapping..`. None of those should be needed for any type in this plan.

Diagnose, fix, re-run. Do not bypass the gate.

- [ ] **Step 3: Confirm git state**

```bash
git status
git log --oneline main..HEAD
```

Expected:
- `git status` reports a clean working tree (no uncommitted changes).
- `git log` shows six commits on the branch (one per Task 1–6).

- [ ] **Step 4: Stop**

Do **not** invoke `superpowers:finishing-a-development-branch`. The implementation is done; control returns to the parent `execute-plan` skill for the functional review gate (Phase 7), simplify gate (Phase 8), external code review (Phase 9), and close-out (Phase 10).

---

## Self-review notes

**Spec coverage check:**
- §Architecture (single package, 8 files) → mapped to File Structure section.
- §Components: interface → Task 6; FetchResult → Task 5; SourceIdentifier → Task 2; NormalizedIntegration → Task 4; IntegrationStatus → Task 1; three exceptions → Task 3.
- §Data flow / §Error handling → no implementation needed (informational); reflected in Task 6 interface and Task 3 exception types.
- §Testing: all five test classes accounted for, every named test method appears in a task.
- §Build gate (`./mvnw verify` green) → Task 7.
- §Field-name constant convention → enforced in Tasks 2, 4, 5.

**Type-consistency check:**
- `SourceIdentifier.FIELD_SOURCE_TYPE`, `FIELD_SOURCE_VALUE` — used in Task 2 test, defined in Task 2 source.
- `NormalizedIntegration.FIELD_INTEGRATION_ID` (and the other six) — used in Task 4 test, defined in Task 4 source.
- `FetchResult.FIELD_INTEGRATIONS`, `FIELD_FETCHED_AT` — used in Task 5 test, defined in Task 5 source.
- `IntegrationAdapter.fetch` signature — referenced as `(String orgId, HttpHeaders authHeaders) throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException` in Task 6, matching all upstream type definitions.

**Placeholder scan:** no TODO/TBD/FIXME, no "similar to Task N", no "implement appropriate X". Every code block is complete.
