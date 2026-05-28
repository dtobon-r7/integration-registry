# Boot-time S3 Loader with Readiness Gate and Disk Cache — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Stop after the last task — do NOT auto-invoke `superpowers:finishing-a-development-branch`.** Control returns to the parent `execute-plan` skill for validation gates (functional review, simplify, external code review) before any PR is opened.

**Goal:** Land the runtime lifecycle of vendor mapping for T04 — `VendorMappingProperties`, `VendorMappingSnapshotHolder`, `S3VendorMappingBundleLoader`, `BundleLoadListener`, `LoggingVendorMappingSnapshot`, and `VendorMappingConfiguration` (all under a new sub-package `com.rapid7.integrationregistry.mapping.loader`), plus the new `BundleLoadException` in `mapping/exception/`, plus the `application.yaml` config block, plus two new POM dependencies (`software.amazon.awssdk:s3`, `org.apache.commons:commons-compress`), plus one new ArchUnit rule (`mappingCoreLayer_shouldNotDependOnFrameworks`) — so the registry can boot, fetch and validate its bundle, build the snapshot, gate `/actuator/health/readiness` on a successful first load, and emit WARN logs on every unknown-triplet lookup.

**Architecture:** Seven new Java types under a new sub-package `com.rapid7.integrationregistry.mapping.loader`, plus one new exception in `mapping/exception/`, plus one new ArchUnit rule, plus an `application.yaml` block, plus two new POM dependencies. The `BundleLoadListener` is an `ApplicationListener<ApplicationStartedEvent>` that overrides Spring Boot 4's default `ACCEPTING_TRAFFIC` auto-publish by emitting `REFUSING_TRAFFIC` first, performing the load (cache-then-S3), and only on success publishing `ACCEPTING_TRAFFIC`. The `S3VendorMappingBundleLoader` reads cache-first with self-healing on corruption, atomic-rename writes after a fresh S3 fetch, then gunzip + untar (commons-compress) → `BundleParser.parse(...)`. The snapshot is exposed as a Spring bean via `VendorMappingSnapshotHolder` (an `AtomicReference`-backed wrapper that throws `IllegalStateException` pre-load and delegates post-load), wrapped at load time in a `LoggingVendorMappingSnapshot` decorator that emits the WARN-on-unknown log. A new ArchUnit rule prevents `mapping/` core (excluding `loader/` and `exception/` sub-packages) from importing Spring Framework or AWS SDK.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Maven (`./mvnw`), JUnit 5 + AssertJ + Mockito (already on the classpath via `spring-boot-starter-test`), `software.amazon.awssdk:s3:2.32.5` (compile scope, new), `org.apache.commons:commons-compress:1.27.1` (compile scope, new), Logback + SLF4J (transitively via Spring Boot), ArchUnit + PMD as build gates.

**Pre-flight context for any worker picking this up cold:**
- The branch `worktree-track-04-plan-03` is checked out in the worktree at `repos/platform/integration-registry/.claude/worktrees/track-04-plan-03`. Run all commands from that worktree path. The worktree currently sits at `f1fb1fc` (= `main` HEAD), no commits yet.
- **Plan 01 and Plan 02 are merged** (PR #2 and PR #3 to `main`). They ship the contract layer and the stateless data layer this plan consumes:
  - `src/main/java/com/rapid7/integrationregistry/mapping/VendorMappingSnapshot.java` — interface this plan exposes via the holder bean
  - `src/main/java/com/rapid7/integrationregistry/mapping/VendorResolution.java` — record + `unknown()` singleton (used by the WARN-on-unknown decorator)
  - `src/main/java/com/rapid7/integrationregistry/mapping/BundleParser.java` — `parse(InputStream) throws BundleParseException` returning a snapshot; this plan calls it after gunzip+untar
  - `src/main/java/com/rapid7/integrationregistry/mapping/MapBackedVendorMappingSnapshot.java` — package-private impl produced by the parser
  - `src/main/java/com/rapid7/integrationregistry/mapping/exception/BundleParseException.java` — checked exception caught and rewrapped by this plan as `BundleLoadException.parseFailed(...)`
  - `src/main/resources/vendor-mapping/bundle/mvp-seed.yaml` — the canonical seed bundle integration tests rebuild as `.tgz` bytes
  - `src/main/resources/vendor-mapping/schema/v1.json` — the JSON Schema the parser validates against (this plan does not touch it)
- The worktree's `pom.xml` does NOT yet have `software.amazon.awssdk:s3` or `org.apache.commons:commons-compress`. Task 1 adds them.
- The worktree's `application.yaml` has empty `local`/`staging`/`production` profile blocks. Task 9 adds a default `integration-registry.vendor-mapping.cache-dir` entry; per-profile pinned-version / bucket / key-prefix overrides are deployment-time concerns and are NOT set in this plan.
- TESTING.md governs test conventions. Unit tests under `src/test/java/com/rapid7/integrationregistry/mapping/loader/`; integration tests in the same package; the `mapping/exception/` test goes alongside `BundleParseExceptionTest`. `methodName_shouldDoX_whenY()` naming, AAA structure with `// Arrange / // Act / // Assert` comments, ~70% unit / ~30% Spring-context integration.
- `LayerDependencyRules` already covers cross-layer boundaries. The new rule `mappingCoreLayer_shouldNotDependOnFrameworks` (Task 1) restricts `..mapping..` (excluding `..mapping.loader..` and `..mapping.exception..`) from depending on `org.springframework..` or `software.amazon.awssdk..`. Register it in `LayerDependencyRulesTest` alongside the existing rules.
- ADR-001 governs exception design. `BundleLoadException` is **payload-style** (the optional `Path` is the structured payload): `public class extends Exception`, **not** `final`, `private static final long serialVersionUID = 1L`, named static factories (`s3FetchFailed`, `cacheReadFailed`, `cacheWriteFailed`, `archiveExtractFailed`, `parseFailed`), `path()` returning `Optional<Path>`. The test must include an `independentlyCatchable_shouldNotShareParentWithOtherExceptions_whenThrown` mirror of `BundleParseExceptionTest`.
- PMD curated rules to remember: `AvoidDuplicateLiterals` (literals appearing 4+ times in one file → extract a `private static final String` constant), `CommentContent` (no `TODO|FIXME|HACK|XXX`), `EmptyCatchBlock` (every `catch` does something — log, rethrow, or wrap), `MutableStaticState` (only on non-final mutable static fields), `UnusedFormalParameter`, `UnusedLocalVariable`, `AvoidInstantiatingObjectsInLoops` (suppress with a justified comment when allocation IS the work).
- Build gate: `./mvnw verify` runs JUnit + ArchUnit + PMD. Each task ends with a focused test run; the final task runs full `verify`.
- Spec lives at `docs/superpowers/specs/2026-05-28-03-s3-loader-readiness-gate-design.md` if you need to re-check field-by-field code blocks, the failure-mode → exception-factory → outcome table, or the per-test fixture contract.

**Hard non-goals (push back if any task drifts into them):**
- No hot-reload from S3 / polling refresh — RFC defers as non-breaking forward extension only.
- No cross-replica snapshot consistency — each replica is self-contained; consistency is achieved by the regionally-identical pinned version.
- No S3 publish pipeline (T11) — this plan consumes the `vendor-mapping-vX.Y.Z.tgz` artifact name; it does NOT produce the artifact.
- No deploy-manifest pin convention documentation (T11).
- No aggregator / controller wiring (T08, T09) — this plan exposes a `VendorMappingSnapshot` bean only.
- No surfacing across read routes (T08, T09 own response shapes).
- No `RestTestClient` dependency — TESTING.md notes it lands in T07. Readiness assertions go through the `ApplicationAvailability` bean directly.
- No structured-logging library beyond Logback's defaults — observability tooling is out of scope.
- No changes to Plan 01 / Plan 02 code in `mapping/` core. The decorator is a new wrapper, not a modification of `MapBackedVendorMappingSnapshot`.
- No new `application-local.yaml` / `application-staging.yaml` / `application-production.yaml` files — Task 9 adds keys to the existing default block; profile-specific values are set at deploy time via env vars / SPRING_APPLICATION_JSON.
- No changes to the JSON Schema or to `BundleParser`. If a parse path appears wrong, fix the loader's call site, not the parser.

---

## File Structure

All new code under `src/main/java/com/rapid7/integrationregistry/mapping/loader/` (new sub-package), `src/main/java/com/rapid7/integrationregistry/mapping/exception/`, and the corresponding `src/test/...` mirrors. `src/main/resources/application.yaml` and `pom.xml` are modified in their respective tasks. `LayerDependencyRules.java` and `LayerDependencyRulesTest.java` are modified in Task 1.

| File | Responsibility | Created in |
|---|---|---|
| `pom.xml` | Add `software.amazon.awssdk:s3:2.32.5` (compile) and `org.apache.commons:commons-compress:1.27.1` (compile) | Task 1 |
| `architecture/LayerDependencyRules.java` | Add `mappingCoreLayer_shouldNotDependOnFrameworks` rule | Task 1 |
| `architecture/LayerDependencyRulesTest.java` | Register the new rule | Task 1 |
| `mapping/exception/BundleLoadException.java` | Payload-style checked exception with five static factories; carries `Optional<Path>` | Task 2 |
| `mapping/loader/VendorMappingProperties.java` | `@ConfigurationProperties("integration-registry.vendor-mapping")` record with derived helpers `bundleObjectKey()` / `cacheFilePath()` | Task 3 |
| `mapping/loader/VendorMappingSnapshotHolder.java` | Package-private `AtomicReference`-backed wrapper implementing `VendorMappingSnapshot`; one-shot `set(...)`; pre-load reads throw `IllegalStateException` | Task 4 |
| `mapping/loader/LoggingVendorMappingSnapshot.java` | Package-private decorator implementing `VendorMappingSnapshot`; WARN log on unknown lookup with `mapping_version` | Task 5 |
| `mapping/loader/S3VendorMappingBundleLoader.java` | Package-private final class; cache-first with self-healing fallthrough; atomic-rename write; gunzip + untar; `BundleParser.parse(...)` orchestration | Task 7 |
| `mapping/loader/BundleLoadListener.java` | `ApplicationListener<ApplicationStartedEvent>`; readiness publish lifecycle; structured ERROR/INFO logging | Task 8 |
| `mapping/loader/VendorMappingConfiguration.java` | `@Configuration` + `@EnableConfigurationProperties` defining the bean graph (S3Client, BundleParser, holder, loader, listener) | Task 9 |
| `mapping/loader/package-info.java` | Javadoc summary naming the package's role | Task 9 |
| `src/main/resources/application.yaml` | Add `integration-registry.vendor-mapping.cache-dir` default | Task 9 |

Test support:

| File | Responsibility | Created in |
|---|---|---|
| `testsupport/BundleArchiveBuilder.java` | Static helper `byte[] tgzOf(byte[], String)` returning a single-entry gzipped tarball; used by all tests that exercise the loader's gunzip+untar path | Task 6 |

Tests:

| File | Coverage | Created in |
|---|---|---|
| `mapping/exception/BundleLoadExceptionTest.java` | One test per factory (message + cause + payload); `independentlyCatchable_shouldNotShareParentWithOtherExceptions_whenThrown`; ADR-001 invariants via reflection (`!Modifier.isFinal`, `serialVersionUID` field present) | Task 2 |
| `mapping/loader/VendorMappingPropertiesTest.java` | `bundleObjectKey()` composes correctly; `cacheFilePath()` composes correctly; null-cacheDir defaults to `${java.io.tmpdir}/...`; null-guard tests for required fields | Task 3 |
| `mapping/loader/VendorMappingSnapshotHolderTest.java` | `lookup` and `mappingVersion` throw `IllegalStateException` pre-load; delegate post-load; `set` is one-shot; null-snapshot rejected | Task 4 |
| `mapping/loader/LoggingVendorMappingSnapshotTest.java` | WARN logged exactly once per unknown lookup with all expected fields; no log on known lookup; delegation correctness | Task 5 |
| `mapping/loader/S3VendorMappingBundleLoaderTest.java` | Cache-hit reads disk skipping S3; cache-miss fetches S3 and writes disk; cache-corrupt deletes + falls through to S3; failure-mode mapping for S3 throw, empty tarball, wrong-name entry, invalid YAML, cache-read I/O error | Task 7 |
| `mapping/loader/BundleLoadListenerTest.java` | Event order REFUSING→ACCEPTING on success; only REFUSING on failure; holder population on success; structured ERROR log fields on failure | Task 8 |
| `mapping/loader/VendorMappingBootIntegrationTest.java` | `@SpringBootTest` + `@MockitoBean S3Client`; three nested classes (valid bundle / S3 throws / invalid bundle) asserting `ApplicationAvailability` state and (where applicable) the loaded snapshot's lookup correctness for all 4 MVP triplets | Task 10 |
| `mapping/loader/VendorMappingDiskCacheIntegrationTest.java` | `@SpringBootTest` + pre-seeded cache file; asserts readiness ACCEPTING and `verify(s3Client, never()).getObject(...)` | Task 11 |

**Task ordering rationale:** dependencies dictate the sequence.

1. **Task 1** — POM deps + ArchUnit rule first. Without compile-scope `software.amazon.awssdk:s3` and `org.apache.commons:commons-compress`, the loader's main-source imports won't compile. The new ArchUnit rule lands here too because it must pass against the pre-existing `mapping/` core (which is currently Spring-free and AWS-free) BEFORE the loader sub-package is introduced — so the rule's "before" state is provably green.
2. **Task 2** — `BundleLoadException` has zero internal dependencies (only `java.nio.file.Path` + `java.util.Optional`). Land it before the loader so the loader's tests can assert against its type and shape.
3. **Task 3** — `VendorMappingProperties` is a pure record with derived helpers; no dependencies on this plan's other types.
4. **Task 4** — `VendorMappingSnapshotHolder` depends only on `VendorMappingSnapshot` (Plan 01) and `VendorResolution` (Plan 01).
5. **Task 5** — `LoggingVendorMappingSnapshot` depends only on `VendorMappingSnapshot` (Plan 01) and `VendorResolution.unknown()` (Plan 01).
6. **Task 6** — `BundleArchiveBuilder` test helper depends only on commons-compress (Task 1). Lands before Task 7 so the loader unit tests can use it.
7. **Task 7** — `S3VendorMappingBundleLoader` depends on `VendorMappingProperties` (Task 3), `BundleLoadException` (Task 2), `BundleParser` (Plan 02), and `BundleArchiveBuilder` for tests (Task 6).
8. **Task 8** — `BundleLoadListener` depends on `S3VendorMappingBundleLoader` (Task 7), `VendorMappingSnapshotHolder` (Task 4), `LoggingVendorMappingSnapshot` (Task 5), `BundleLoadException` (Task 2), `VendorMappingProperties` (Task 3).
9. **Task 9** — `VendorMappingConfiguration` wires every bean from Tasks 3–8; `application.yaml` gets the cache-dir default; `package-info.java` lands now since all referenced types exist.
10. **Task 10** — Spring-context boot integration test: needs every production type from Tasks 1–9 to be present.
11. **Task 11** — Disk-cache integration test: same dependencies; isolates the cache-hit assertion in its own class to keep `@DynamicPropertySource` setup readable.
12. **Task 12** — full `./mvnw verify` checkpoint.

---

## Task 1: Add POM dependencies and the `mappingCoreLayer_shouldNotDependOnFrameworks` ArchUnit rule

**Files:**
- Modify: `pom.xml`
- Modify: `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java`
- Modify: `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRulesTest.java`

**Why this is its own commit:** the dependency change and the ArchUnit rule are tightly coupled — the rule is the contract that says "AWS SDK and Spring imports must stay out of `..mapping..` core." Landing them together makes the boundary intent reviewable on its own, and the rule provably passes against the current (loader-free) state of the codebase before any new import is added.

- [ ] **Step 1: Inspect the current `<dependencies>` block**

Run: `grep -n -A4 'json-schema-validator\|jackson-dataformat-yaml\|software.amazon.awssdk\|commons-compress' pom.xml`

Expected: prints the existing `json-schema-validator` and `jackson-dataformat-yaml` blocks (added in Plan 02). No `software.amazon.awssdk` or `commons-compress` entries.

- [ ] **Step 2: Modify `pom.xml`**

In `pom.xml`, add two new dependency blocks immediately after the existing `jackson-dataformat-yaml` block, before the closing `</dependencies>` tag:

```xml
		<dependency>
			<groupId>software.amazon.awssdk</groupId>
			<artifactId>s3</artifactId>
			<version>2.32.5</version>
		</dependency>
		<dependency>
			<groupId>org.apache.commons</groupId>
			<artifactId>commons-compress</artifactId>
			<version>1.27.1</version>
		</dependency>
```

Both are `compile` scope (the default — do NOT add `<scope>`). The AWS SDK v2 modular structure means we explicitly pin `s3` rather than `aws-sdk-java`; transitive deps (`software.amazon.awssdk:auth`, `regions`, `http-client-spi`, `apache-client`, etc.) flow from there. Versions are pinned because no BOM manages them.

- [ ] **Step 3: Verify resolution**

Run: `./mvnw -q dependency:list 2>&1 | grep -E 'software.amazon.awssdk:s3|commons-compress'`

Expected output (one line per artifact, scope `compile`; the `s3` artifact may also appear via transitive AWS SDK deps but the explicit declaration is what we own):

```
[INFO]    org.apache.commons:commons-compress:jar:1.27.1:compile -- module ...
[INFO]    software.amazon.awssdk:s3:jar:2.32.5:compile -- module ...
```

If either artifact is missing or shows `:test`, the pom edit is wrong; double-check Step 2.

- [ ] **Step 4: Verify the existing build still compiles**

Run: `./mvnw -q compile test-compile`
Expected: `BUILD SUCCESS`. The new deps don't have any code referencing them yet.

- [ ] **Step 5: Add the ArchUnit rule**

Modify `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java`. Add a new `static final ArchRule` field after `adapterLayer_shouldNotDependOnInternalLayers` and before the private no-arg constructor:

```java
    static final ArchRule mappingCoreLayer_shouldNotDependOnFrameworks =
            noClasses().that().resideInAPackage("..mapping..")
                    .and().resideOutsideOfPackages("..mapping.loader..", "..mapping.exception..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..", "software.amazon.awssdk..");
```

Java imports already in the file cover `ArchRule` and `ArchRuleDefinition.noClasses` — no new import needed.

- [ ] **Step 6: Register the rule in `LayerDependencyRulesTest`**

Modify `src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRulesTest.java`. Append a new `@ArchTest` field after `adapterLayer_shouldNotDependOnInternalLayers`:

```java
    @ArchTest
    static final ArchRule mappingCoreLayer_shouldNotDependOnFrameworks =
            LayerDependencyRules.mappingCoreLayer_shouldNotDependOnFrameworks;
```

- [ ] **Step 7: Run the architecture tests**

Run: `./mvnw test -Dtest=LayerDependencyRulesTest`
Expected: PASS — all seven `@ArchTest` rules green, including the new one. The current `mapping/` core (parser, snapshot, enums, records) imports only JDK + `com.fasterxml.jackson.*` + `com.networknt.schema.*`, so the rule passes trivially against the pre-loader state.

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRules.java src/test/java/com/rapid7/integrationregistry/architecture/LayerDependencyRulesTest.java
git commit -m "build(track-04/wp-03): add aws-sdk-s3 + commons-compress; add mapping-core framework-import ban"
```

---

## Task 2: `BundleLoadException` payload-style checked exception

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/exception/BundleLoadException.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/exception/BundleLoadExceptionTest.java`

**Why this comes before the loader:** the loader's tests in Task 7 assert against this type's factories and `path()` accessor. Having it land first lets every downstream task start from a real failing test, not a compilation error.

ADR-001 invariants for this type: `public class extends Exception` (checked), **not** `final`, `private static final long serialVersionUID = 1L`. Five named static factories (`s3FetchFailed`, `cacheReadFailed`, `cacheWriteFailed`, `archiveExtractFailed`, `parseFailed`). Single private constructor `(String message, Throwable cause, Path path)`. Public `path()` accessor returning `Optional<Path>`. The `independentlyCatchable_shouldNotShareParentWithOtherExceptions_whenThrown` test mirrors `BundleParseExceptionTest`'s pattern.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/exception/BundleLoadExceptionTest.java`:

```java
package com.rapid7.integrationregistry.mapping.exception;

import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BundleLoadExceptionTest {

    @Test
    void s3FetchFailed_shouldCarryCauseAndEmptyPath_whenInvoked() {
        // Arrange
        IOException cause = new IOException("connection reset");

        // Act
        BundleLoadException ex = BundleLoadException.s3FetchFailed(cause);

        // Assert
        assertThat(ex).isInstanceOf(Exception.class);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage())
            .contains("Vendor mapping bundle could not be fetched from S3")
            .contains("connection reset");
        assertThat(ex.path()).isEmpty();
    }

    @Test
    void cacheReadFailed_shouldCarryCauseAndPath_whenInvoked() {
        // Arrange
        Path cachePath = Path.of("/tmp/integration-registry/vendor-mapping/vendor-mapping-v1.0.0.tgz");
        IOException cause = new IOException("permission denied");

        // Act
        BundleLoadException ex = BundleLoadException.cacheReadFailed(cachePath, cause);

        // Assert
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage())
            .contains("Vendor mapping disk cache could not be read")
            .contains(cachePath.toString())
            .contains("permission denied");
        assertThat(ex.path()).contains(cachePath);
    }

    @Test
    void cacheWriteFailed_shouldCarryCauseAndPath_whenInvoked() {
        // Arrange
        Path cachePath = Path.of("/tmp/integration-registry/vendor-mapping/vendor-mapping-v1.0.0.tgz");
        IOException cause = new IOException("disk full");

        // Act
        BundleLoadException ex = BundleLoadException.cacheWriteFailed(cachePath, cause);

        // Assert
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage())
            .contains("Vendor mapping disk cache could not be written")
            .contains(cachePath.toString())
            .contains("disk full");
        assertThat(ex.path()).contains(cachePath);
    }

    @Test
    void archiveExtractFailed_shouldCarryCauseAndEmptyPath_whenInvoked() {
        // Arrange
        IllegalStateException cause = new IllegalStateException("tarball is empty");

        // Act
        BundleLoadException ex = BundleLoadException.archiveExtractFailed(cause);

        // Assert
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage())
            .contains("Vendor mapping bundle archive could not be extracted")
            .contains("tarball is empty");
        assertThat(ex.path()).isEmpty();
    }

    @Test
    void parseFailed_shouldCarryCauseAndEmptyPath_whenInvoked() {
        // Arrange
        IOException cause = new IOException("schema validation failed");

        // Act
        BundleLoadException ex = BundleLoadException.parseFailed(cause);

        // Assert
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage())
            .contains("Vendor mapping bundle could not be parsed")
            .contains("schema validation failed");
        assertThat(ex.path()).isEmpty();
    }

    @Test
    void path_shouldReturnOptional_alwaysNonNull() {
        // Arrange
        BundleLoadException withPath = BundleLoadException.cacheReadFailed(
            Path.of("/tmp/x"), new IOException("io"));
        BundleLoadException withoutPath = BundleLoadException.s3FetchFailed(new IOException("io"));

        // Act / Assert
        assertThat(withPath.path()).isInstanceOf(Optional.class).isPresent();
        assertThat(withoutPath.path()).isInstanceOf(Optional.class).isEmpty();
    }

    @Test
    void serialVersionUID_shouldBePresentAndPrivateStaticFinalLong_whenInspected() throws Exception {
        // Arrange
        Field field = BundleLoadException.class.getDeclaredField("serialVersionUID");

        // Act / Assert
        assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        assertThat(field.getType()).isEqualTo(long.class);
    }

    @Test
    void classModifier_shouldNotBeFinal_perAdr001SharedInvariant() {
        // Arrange / Act
        int modifiers = BundleLoadException.class.getModifiers();

        // Assert — ADR-001: exception classes are "not final" so future
        // refactors can subclass without a breaking change.
        assertThat(Modifier.isFinal(modifiers)).isFalse();
    }

    @Test
    void independentlyCatchable_shouldNotShareParentWithOtherExceptions_whenThrown() {
        // Arrange
        // If a future refactor introduces a shared parent above Exception
        // (e.g., a "RegistryException" abstract class) for either family,
        // these isNotInstanceOf assertions will fail. The two exception
        // families (mapping.exception.* and adapter.exception.*) are
        // deliberately independent — see ADR-001.

        // Act / Assert
        BundleLoadException caught = BundleLoadException.s3FetchFailed(new IOException("test"));
        assertThat(caught)
            .isNotInstanceOf(AdapterAuthException.class)
            .isNotInstanceOf(AdapterTimeoutException.class)
            .isNotInstanceOf(AdapterUpstreamException.class)
            .isNotInstanceOf(BundleParseException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=BundleLoadExceptionTest`
Expected: FAIL with compilation error (`BundleLoadException` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/exception/BundleLoadException.java`:

```java
package com.rapid7.integrationregistry.mapping.exception;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Thrown by the runtime bundle loader when the vendor-mapping bundle cannot be
 * retrieved, decoded, or parsed at boot. Caught by the boot-time listener,
 * which maps it to a readiness-probe-down state plus a structured ERROR log
 * entry built from {@link #getMessage()}, the cause, and {@link #path()} when
 * present.
 *
 * <p>This is the <em>payload-style</em> exception per ADR-001: the structured
 * payload here is the optional {@link #path()} (populated for cache I/O
 * failures), and the underlying {@link Throwable} cause discriminates the
 * remaining failure modes via the named static factories. Contrast with the
 * <em>marker-style</em> adapter exceptions in
 * {@code com.rapid7.integrationregistry.adapter.exception} which carry only
 * message and cause. See ADR-001 for the convention.
 */
public class BundleLoadException extends Exception {

    private static final long serialVersionUID = 1L;

    private final Optional<Path> path;

    private BundleLoadException(String message, Throwable cause, Path path) {
        super(message, cause);
        this.path = Optional.ofNullable(path);
    }

    public static BundleLoadException s3FetchFailed(Throwable cause) {
        return new BundleLoadException(
            "Vendor mapping bundle could not be fetched from S3: " + cause.getMessage(),
            cause, null);
    }

    public static BundleLoadException cacheReadFailed(Path path, Throwable cause) {
        return new BundleLoadException(
            "Vendor mapping disk cache could not be read at " + path + ": " + cause.getMessage(),
            cause, path);
    }

    public static BundleLoadException cacheWriteFailed(Path path, Throwable cause) {
        return new BundleLoadException(
            "Vendor mapping disk cache could not be written at " + path + ": " + cause.getMessage(),
            cause, path);
    }

    public static BundleLoadException archiveExtractFailed(Throwable cause) {
        return new BundleLoadException(
            "Vendor mapping bundle archive could not be extracted: " + cause.getMessage(),
            cause, null);
    }

    public static BundleLoadException parseFailed(Throwable cause) {
        return new BundleLoadException(
            "Vendor mapping bundle could not be parsed: " + cause.getMessage(),
            cause, null);
    }

    public Optional<Path> path() {
        return path;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=BundleLoadExceptionTest`
Expected: PASS — all nine tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/exception/BundleLoadException.java src/test/java/com/rapid7/integrationregistry/mapping/exception/BundleLoadExceptionTest.java
git commit -m "feat(track-04/wp-03): add BundleLoadException payload-style checked exception"
```

---

## Task 3: `VendorMappingProperties` configuration record

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingProperties.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingPropertiesTest.java`

**Why this comes early:** the loader, listener, and Spring `@Configuration` all consume this type. Landing it first lets every later task instantiate a real `VendorMappingProperties(...)` from a test fixture without mocking.

The record has four components: `bundleVersion` (String, required), `s3Bucket` (String, required), `s3KeyPrefix` (String, required, expected with trailing slash), `cacheDir` (Path, default `${java.io.tmpdir}/integration-registry/vendor-mapping`). Compact constructor enforces null-checks on the required fields and substitutes the default cacheDir when null. Two derived helpers: `bundleObjectKey()` returns `s3KeyPrefix + "vendor-mapping-" + bundleVersion + ".tgz"`; `cacheFilePath()` returns `cacheDir.resolve("vendor-mapping-" + bundleVersion + ".tgz")`.

The `@ConfigurationProperties("integration-registry.vendor-mapping")` annotation is on the record; activation via `@EnableConfigurationProperties` lands in Task 9 on `VendorMappingConfiguration`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingPropertiesTest.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class VendorMappingPropertiesTest {

    private static VendorMappingProperties fixture(Path cacheDir) {
        return new VendorMappingProperties(
            "v1.0.0",
            "test-bucket",
            "registry/mappings/",
            cacheDir);
    }

    @Test
    void bundleObjectKey_shouldComposeKey_whenAllFieldsSet() {
        // Arrange
        VendorMappingProperties props = fixture(Path.of("/tmp"));

        // Act
        String key = props.bundleObjectKey();

        // Assert
        assertThat(key).isEqualTo("registry/mappings/vendor-mapping-v1.0.0.tgz");
    }

    @Test
    void cacheFilePath_shouldComposePath_whenAllFieldsSet() {
        // Arrange
        VendorMappingProperties props = fixture(Path.of("/tmp/integration-registry/vendor-mapping"));

        // Act
        Path cacheFile = props.cacheFilePath();

        // Assert
        assertThat(cacheFile).isEqualTo(
            Path.of("/tmp/integration-registry/vendor-mapping/vendor-mapping-v1.0.0.tgz"));
    }

    @Test
    void properties_shouldDefaultCacheDir_whenNullPassed() {
        // Arrange
        Path expected = Path.of(System.getProperty("java.io.tmpdir"),
                                "integration-registry", "vendor-mapping");

        // Act
        VendorMappingProperties props = fixture(null);

        // Assert
        assertThat(props.cacheDir()).isEqualTo(expected);
    }

    @Test
    void properties_shouldThrowNpe_whenBundleVersionNull() {
        // Arrange / Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorMappingProperties(null, "b", "p/", Path.of("/tmp")))
            .withMessage("bundleVersion");
    }

    @Test
    void properties_shouldThrowNpe_whenS3BucketNull() {
        // Arrange / Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorMappingProperties("v", null, "p/", Path.of("/tmp")))
            .withMessage("s3Bucket");
    }

    @Test
    void properties_shouldThrowNpe_whenS3KeyPrefixNull() {
        // Arrange / Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> new VendorMappingProperties("v", "b", null, Path.of("/tmp")))
            .withMessage("s3KeyPrefix");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=VendorMappingPropertiesTest`
Expected: FAIL with compilation error (`VendorMappingProperties` does not exist; `mapping/loader/` package does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingProperties.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Configuration properties for the runtime bundle loader. Bound from the
 * {@code integration-registry.vendor-mapping.*} property tree.
 *
 * <p>Required fields ({@code bundleVersion}, {@code s3Bucket},
 * {@code s3KeyPrefix}) have no defaults — the deploy environment supplies
 * them via env vars / per-profile overrides. {@code cacheDir} defaults to
 * {@code ${java.io.tmpdir}/integration-registry/vendor-mapping}.
 *
 * <p>Activated via {@code @EnableConfigurationProperties(VendorMappingProperties.class)}
 * on the loader's {@code @Configuration} class.
 */
@ConfigurationProperties("integration-registry.vendor-mapping")
public record VendorMappingProperties(
    String bundleVersion,
    String s3Bucket,
    String s3KeyPrefix,
    Path cacheDir
) {

    private static final String FIELD_BUNDLE_VERSION = "bundleVersion";
    private static final String FIELD_S3_BUCKET = "s3Bucket";
    private static final String FIELD_S3_KEY_PREFIX = "s3KeyPrefix";

    public VendorMappingProperties {
        Objects.requireNonNull(bundleVersion, FIELD_BUNDLE_VERSION);
        Objects.requireNonNull(s3Bucket, FIELD_S3_BUCKET);
        Objects.requireNonNull(s3KeyPrefix, FIELD_S3_KEY_PREFIX);
        if (cacheDir == null) {
            cacheDir = Path.of(System.getProperty("java.io.tmpdir"),
                               "integration-registry", "vendor-mapping");
        }
    }

    /**
     * Composite S3 object key under the configured bucket — e.g.
     * {@code registry/mappings/vendor-mapping-v1.0.0.tgz}.
     */
    public String bundleObjectKey() {
        return s3KeyPrefix + "vendor-mapping-" + bundleVersion + ".tgz";
    }

    /**
     * Resolved disk-cache filename — e.g.
     * {@code <cacheDir>/vendor-mapping-v1.0.0.tgz}. Per-version filename
     * prevents cross-version cache reuse.
     */
    public Path cacheFilePath() {
        return cacheDir.resolve("vendor-mapping-" + bundleVersion + ".tgz");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=VendorMappingPropertiesTest`
Expected: PASS — all six tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingProperties.java src/test/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingPropertiesTest.java
git commit -m "feat(track-04/wp-03): add VendorMappingProperties configuration record"
```

---

## Task 4: `VendorMappingSnapshotHolder` — `AtomicReference`-backed bean

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingSnapshotHolder.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingSnapshotHolderTest.java`

**Why this is package-private:** the holder is an implementation detail of the loader sub-package. Callers (the future aggregator T08) autowire `VendorMappingSnapshot` (the interface), not the holder type. The Spring `@Configuration` (Task 9) exposes the holder as a `VendorMappingSnapshot` bean.

`final class implements VendorMappingSnapshot`. Single private field `AtomicReference<VendorMappingSnapshot> ref`. Method `set(VendorMappingSnapshot)` is one-shot — null is rejected; calling twice throws `IllegalStateException`. Pre-population reads of `lookup` and `mappingVersion` throw `IllegalStateException` with the explicit "snapshot not yet loaded — readiness should have prevented this call" message. Post-population reads delegate to the loaded snapshot.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingSnapshotHolderTest.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class VendorMappingSnapshotHolderTest {

    private static VendorMappingSnapshot stubSnapshot(String version, VendorResolution resolution) {
        return new VendorMappingSnapshot() {
            @Override
            public VendorResolution lookup(ProductName p, SourceType s, String v) {
                return resolution;
            }
            @Override
            public String mappingVersion() {
                return version;
            }
        };
    }

    @Test
    void lookup_shouldThrowIllegalState_whenNotYetSet() {
        // Arrange
        VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();

        // Act / Assert
        assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> holder.lookup(
                ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "x"))
            .withMessageContaining("snapshot not yet loaded");
    }

    @Test
    void mappingVersion_shouldThrowIllegalState_whenNotYetSet() {
        // Arrange
        VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();

        // Act / Assert
        assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(holder::mappingVersion)
            .withMessageContaining("snapshot not yet loaded");
    }

    @Test
    void lookup_shouldDelegate_whenSet() {
        // Arrange
        VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();
        VendorResolution expected = new VendorResolution(
            "microsoft-defender", "Microsoft Defender",
            VendorCategory.EDR, "microsoft", "Microsoft");
        holder.set(stubSnapshot("v1.0.0", expected));

        // Act
        VendorResolution actual = holder.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "any-value");

        // Assert
        assertThat(actual).isSameAs(expected);
    }

    @Test
    void mappingVersion_shouldDelegate_whenSet() {
        // Arrange
        VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();
        holder.set(stubSnapshot("v9.9.9", VendorResolution.unknown()));

        // Act
        String version = holder.mappingVersion();

        // Assert
        assertThat(version).isEqualTo("v9.9.9");
    }

    @Test
    void set_shouldThrowIllegalState_whenAlreadySet() {
        // Arrange
        VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();
        holder.set(stubSnapshot("v1.0.0", VendorResolution.unknown()));

        // Act / Assert
        assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> holder.set(stubSnapshot("v2.0.0", VendorResolution.unknown())))
            .withMessageContaining("already set");
    }

    @Test
    void set_shouldThrowNpe_whenNullSnapshot() {
        // Arrange
        VendorMappingSnapshotHolder holder = new VendorMappingSnapshotHolder();

        // Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> holder.set(null))
            .withMessage("snapshot");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=VendorMappingSnapshotHolderTest`
Expected: FAIL with compilation error (`VendorMappingSnapshotHolder` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingSnapshotHolder.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot {@link AtomicReference}-backed wrapper exposed as the
 * {@link VendorMappingSnapshot} bean. The runtime loader populates the
 * reference exactly once during boot via {@link #set(VendorMappingSnapshot)};
 * pre-population reads throw {@link IllegalStateException}.
 *
 * <p>The readiness gate is the upstream contract that prevents pre-load reads
 * in production. The {@code IllegalStateException} is a defensive failure
 * mode for the case where the gate is bypassed (e.g., a misconfigured probe).
 */
final class VendorMappingSnapshotHolder implements VendorMappingSnapshot {

    private static final String NOT_LOADED_MESSAGE =
        "snapshot not yet loaded — readiness should have prevented this call";
    private static final String FIELD_SNAPSHOT = "snapshot";

    private final AtomicReference<VendorMappingSnapshot> ref = new AtomicReference<>();

    void set(VendorMappingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, FIELD_SNAPSHOT);
        if (!ref.compareAndSet(null, snapshot)) {
            throw new IllegalStateException("snapshot already set; holder is one-shot");
        }
    }

    @Override
    public VendorResolution lookup(ProductName productName, SourceType sourceType, String sourceValue) {
        return current().lookup(productName, sourceType, sourceValue);
    }

    @Override
    public String mappingVersion() {
        return current().mappingVersion();
    }

    private VendorMappingSnapshot current() {
        VendorMappingSnapshot snapshot = ref.get();
        if (snapshot == null) {
            throw new IllegalStateException(NOT_LOADED_MESSAGE);
        }
        return snapshot;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=VendorMappingSnapshotHolderTest`
Expected: PASS — all six tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingSnapshotHolder.java src/test/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingSnapshotHolderTest.java
git commit -m "feat(track-04/wp-03): add VendorMappingSnapshotHolder one-shot bean wrapper"
```

---

## Task 5: `LoggingVendorMappingSnapshot` decorator

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/loader/LoggingVendorMappingSnapshot.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/loader/LoggingVendorMappingSnapshotTest.java`

**Why a decorator and not a Plan 02 modification:** Plan 02's `MapBackedVendorMappingSnapshot` is framework-agnostic (no SLF4J dep, no logger field). Adding logging there would couple the data layer to the runtime layer and force a Spring/SLF4J import inside `..mapping..` core (which the new ArchUnit rule from Task 1 forbids). The decorator wraps the parsed snapshot at load time (in Task 8's listener) and is package-private to `loader/`.

The decorator implements `VendorMappingSnapshot`. Constructor takes a non-null delegate. `lookup(...)` calls the delegate; if the result equals `VendorResolution.unknown()`, log WARN with `product`, `source_type`, `source_value`, `mapping_version`. `mappingVersion()` delegates.

The test uses Logback's `ListAppender` to capture log events at the SLF4J logger named after `LoggingVendorMappingSnapshot`. Logback ships transitively via Spring Boot's logging starter.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/loader/LoggingVendorMappingSnapshotTest.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class LoggingVendorMappingSnapshotTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(LoggingVendorMappingSnapshot.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private static VendorMappingSnapshot stubSnapshot(String version, VendorResolution resolution) {
        return new VendorMappingSnapshot() {
            @Override
            public VendorResolution lookup(ProductName p, SourceType s, String v) {
                return resolution;
            }
            @Override
            public String mappingVersion() {
                return version;
            }
        };
    }

    @Test
    void constructor_shouldThrowNpe_whenDelegateNull() {
        // Arrange / Act / Assert
        assertThatNullPointerException()
            .isThrownBy(() -> new LoggingVendorMappingSnapshot(null))
            .withMessage("delegate");
    }

    @Test
    void lookup_shouldLogWarn_whenUnderlyingReturnsUnknown() {
        // Arrange
        LoggingVendorMappingSnapshot decorator = new LoggingVendorMappingSnapshot(
            stubSnapshot("v1.0.0", VendorResolution.unknown()));

        // Act
        VendorResolution result = decorator.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "mystery-source");

        // Assert
        assertThat(result).isSameAs(VendorResolution.unknown());
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        String formatted = event.getFormattedMessage();
        assertThat(formatted)
            .contains("Unknown vendor mapping triplet")
            .contains("INSIGHT_IDR")
            .contains("PRODUCT_TYPE")
            .contains("mystery-source")
            .contains("v1.0.0");
    }

    @Test
    void lookup_shouldNotLog_whenUnderlyingReturnsKnown() {
        // Arrange
        VendorResolution known = new VendorResolution(
            "microsoft-defender", "Microsoft Defender",
            VendorCategory.EDR, "microsoft", "Microsoft");
        LoggingVendorMappingSnapshot decorator = new LoggingVendorMappingSnapshot(
            stubSnapshot("v1.0.0", known));

        // Act
        VendorResolution result = decorator.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");

        // Assert
        assertThat(result).isSameAs(known);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void mappingVersion_shouldDelegate_always() {
        // Arrange
        LoggingVendorMappingSnapshot decorator = new LoggingVendorMappingSnapshot(
            stubSnapshot("v1.42.0", VendorResolution.unknown()));

        // Act
        String version = decorator.mappingVersion();

        // Assert
        assertThat(version).isEqualTo("v1.42.0");
        assertThat(appender.list).isEmpty();   // mappingVersion() never logs
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=LoggingVendorMappingSnapshotTest`
Expected: FAIL with compilation error (`LoggingVendorMappingSnapshot` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/loader/LoggingVendorMappingSnapshot.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Decorator that emits a WARN log on every unmapped-triplet lookup, including
 * the current {@code mapping_version}. The runtime loader wraps the parsed
 * {@link VendorMappingSnapshot} in this decorator before exposing it via the
 * holder bean — Plan 02's {@code MapBackedVendorMappingSnapshot} is left pure
 * (no logger, no Spring) so the layering boundary stays intact.
 */
final class LoggingVendorMappingSnapshot implements VendorMappingSnapshot {

    private static final Logger log = LoggerFactory.getLogger(LoggingVendorMappingSnapshot.class);
    private static final String FIELD_DELEGATE = "delegate";

    private final VendorMappingSnapshot delegate;

    LoggingVendorMappingSnapshot(VendorMappingSnapshot delegate) {
        this.delegate = Objects.requireNonNull(delegate, FIELD_DELEGATE);
    }

    @Override
    public VendorResolution lookup(ProductName productName, SourceType sourceType, String sourceValue) {
        VendorResolution resolution = delegate.lookup(productName, sourceType, sourceValue);
        if (VendorResolution.unknown().equals(resolution)) {
            log.warn("Unknown vendor mapping triplet: product={}, source_type={}, source_value={} (mapping_version={})",
                     productName, sourceType, sourceValue, delegate.mappingVersion());
        }
        return resolution;
    }

    @Override
    public String mappingVersion() {
        return delegate.mappingVersion();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=LoggingVendorMappingSnapshotTest`
Expected: PASS — all four tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/loader/LoggingVendorMappingSnapshot.java src/test/java/com/rapid7/integrationregistry/mapping/loader/LoggingVendorMappingSnapshotTest.java
git commit -m "feat(track-04/wp-03): add LoggingVendorMappingSnapshot WARN-on-unknown decorator"
```

---

## Task 6: `BundleArchiveBuilder` test helper for `.tgz` byte construction

**Files:**
- Create: `src/test/java/com/rapid7/integrationregistry/testsupport/BundleArchiveBuilder.java`

**Why a test-source helper, not a production utility:** the registry never builds tarballs in production (T11's CI publish pipeline does). This helper exists only so unit and integration tests can stub `S3Client.getObject(...)` with realistic `.tgz` bytes built from the existing `mvp-seed.yaml` resource — without committing a binary fixture. It lives under `testsupport/` alongside `FixtureLoader.java`.

The helper has one public static method: `byte[] tgzOf(byte[] yamlContent, String entryName)`. It pipes the YAML bytes through `TarArchiveOutputStream` (single entry with `entryName`, size = `yamlContent.length`) into `GzipCompressorOutputStream` into a `ByteArrayOutputStream`, returning the byte array.

- [ ] **Step 1: Write the helper**

Create `src/test/java/com/rapid7/integrationregistry/testsupport/BundleArchiveBuilder.java`:

```java
package com.rapid7.integrationregistry.testsupport;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Builds a single-entry gzipped tarball ({@code .tgz}) for tests that stub
 * the S3 fetch with realistic bytes. Mirrors what T11's bundle-publish
 * pipeline produces: one tar entry named {@code vendor-mapping.yaml}
 * carrying the YAML bytes, gzipped.
 *
 * <p>Tests typically read {@code src/main/resources/vendor-mapping/bundle/mvp-seed.yaml}
 * via {@code getResourceAsStream}, then call {@link #tgzOf(byte[], String)}
 * with {@code "vendor-mapping.yaml"} as the entry name.
 */
public final class BundleArchiveBuilder {

    private BundleArchiveBuilder() {}

    public static byte[] tgzOf(byte[] yamlContent, String entryName) {
        try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
             GzipCompressorOutputStream gzipOut = new GzipCompressorOutputStream(byteOut);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {

            TarArchiveEntry entry = new TarArchiveEntry(entryName);
            entry.setSize(yamlContent.length);
            tarOut.putArchiveEntry(entry);
            tarOut.write(yamlContent);
            tarOut.closeArchiveEntry();
            tarOut.finish();
            // Close streams in reverse order via try-with-resources; finish() flushes
            // tar bytes into gzip; gzip's close (via try-with-resources) finalizes
            // gzip frame into the byte buffer.
            tarOut.close();
            gzipOut.close();
            return byteOut.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to build .tgz fixture", ex);
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./mvnw -q test-compile`
Expected: `BUILD SUCCESS`. No test consumes the helper yet.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/testsupport/BundleArchiveBuilder.java
git commit -m "test(track-04/wp-03): add BundleArchiveBuilder testsupport helper for .tgz bytes"
```

---

## Task 7: `S3VendorMappingBundleLoader` — cache-first loader with self-healing fallthrough

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/loader/S3VendorMappingBundleLoader.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/loader/S3VendorMappingBundleLoaderTest.java`

**Why this is the longest task:** the loader carries the most behavior of any single type — cache check, S3 fetch, atomic-rename write, gunzip+untar, parser delegation, plus seven distinct failure paths each mapped to a different `BundleLoadException` factory. The test file exercises every branch with `@TempDir` + Mockito + `BundleArchiveBuilder`. It's still one task because all the branches share state and helpers; splitting them would create duplicate test setup.

The loader is `final class` (package-private), constructor injects `S3Client`, `BundleParser`, `VendorMappingProperties`. Single public method `load() throws BundleLoadException`. Internal helpers: `fetchFromS3()`, `writeCacheAtomically(Path, byte[])`, `parseFromBytes(byte[])`, `deleteCorruptCache(Path)`. Cache-corruption fallthrough catches `BundleLoadException` from `parseFromBytes` (archive-extract or parse failures); cache-read I/O errors do NOT fall through (they propagate as `cacheReadFailed`).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/loader/S3VendorMappingBundleLoaderTest.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.BundleParser;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import com.rapid7.integrationregistry.testsupport.BundleArchiveBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3VendorMappingBundleLoaderTest {

    private static final String BUCKET = "test-bucket";
    private static final String KEY_PREFIX = "registry/mappings/";
    private static final String VERSION = "v1.0.0";
    private static final String EXPECTED_KEY = "registry/mappings/vendor-mapping-v1.0.0.tgz";
    private static final String ENTRY_NAME = "vendor-mapping.yaml";

    @TempDir
    Path tempDir;

    private S3Client s3Client;
    private BundleParser parser;
    private VendorMappingProperties properties;
    private S3VendorMappingBundleLoader loader;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        parser = new BundleParser();
        properties = new VendorMappingProperties(VERSION, BUCKET, KEY_PREFIX, tempDir);
        loader = new S3VendorMappingBundleLoader(s3Client, parser, properties);
    }

    private static byte[] readMvpSeed() throws IOException {
        try (InputStream stream = S3VendorMappingBundleLoaderTest.class.getResourceAsStream(
                "/vendor-mapping/bundle/mvp-seed.yaml")) {
            assertThat(stream).as("mvp-seed.yaml present on classpath").isNotNull();
            return stream.readAllBytes();
        }
    }

    private static ResponseBytes<GetObjectResponse> responseBytesOf(byte[] body) {
        return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), body);
    }

    @Test
    void load_shouldReadFromDisk_whenCacheExists() throws Exception {
        // Arrange
        byte[] tgz = BundleArchiveBuilder.tgzOf(readMvpSeed(), ENTRY_NAME);
        Path cacheFile = properties.cacheFilePath();
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, tgz);

        // Act
        VendorMappingSnapshot snapshot = loader.load();

        // Assert
        assertThat(snapshot.mappingVersion()).isEqualTo("v1.0.0");
        assertThat(snapshot.lookup(
            ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint")
            .vendorServiceId()).isEqualTo("microsoft-defender");
        verify(s3Client, never()).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
    }

    @Test
    void load_shouldFetchS3_whenCacheMissing() throws Exception {
        // Arrange
        byte[] tgz = BundleArchiveBuilder.tgzOf(readMvpSeed(), ENTRY_NAME);
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
            .thenReturn(responseBytesOf(tgz));

        // Act
        VendorMappingSnapshot snapshot = loader.load();

        // Assert
        assertThat(snapshot.mappingVersion()).isEqualTo("v1.0.0");
        Path cacheFile = properties.cacheFilePath();
        assertThat(cacheFile).exists();
        assertThat(Files.readAllBytes(cacheFile)).isEqualTo(tgz);
    }

    @Test
    void load_shouldFallthroughToS3_whenCacheCorrupted() throws Exception {
        // Arrange — write garbage bytes to the cache (not a valid gzip).
        Path cacheFile = properties.cacheFilePath();
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, "this is not a tarball".getBytes(StandardCharsets.UTF_8));

        byte[] freshTgz = BundleArchiveBuilder.tgzOf(readMvpSeed(), ENTRY_NAME);
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
            .thenReturn(responseBytesOf(freshTgz));

        // Act
        VendorMappingSnapshot snapshot = loader.load();

        // Assert
        assertThat(snapshot.mappingVersion()).isEqualTo("v1.0.0");
        verify(s3Client).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
        // Cache was replaced with the fresh fetch.
        assertThat(Files.readAllBytes(cacheFile)).isEqualTo(freshTgz);
    }

    @Test
    void load_shouldThrowS3FetchFailed_whenS3Throws() {
        // Arrange
        SdkClientException sdkEx = SdkClientException.create("connection reset");
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
            .thenThrow(sdkEx);

        // Act / Assert
        BundleLoadException thrown = assertThatExceptionOfType(BundleLoadException.class)
            .isThrownBy(() -> loader.load())
            .actual();
        assertThat(thrown.getMessage()).contains("could not be fetched from S3");
        assertThat(thrown.getCause()).isSameAs(sdkEx);
        assertThat(thrown.path()).isEmpty();
    }

    @Test
    void load_shouldThrowArchiveExtractFailed_whenTarballEmpty() {
        // Arrange — gzip a tar with zero entries.
        byte[] emptyTgz = BundleArchiveBuilder.tgzOf(new byte[0], "placeholder.dummy");
        // BundleArchiveBuilder always writes the entry, even if empty content.
        // For an entry-less tarball, build by hand:
        byte[] handBuiltEmptyTgz = handBuildEmptyTgz();
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
            .thenReturn(responseBytesOf(handBuiltEmptyTgz));

        // Act / Assert
        BundleLoadException thrown = assertThatExceptionOfType(BundleLoadException.class)
            .isThrownBy(() -> loader.load())
            .actual();
        assertThat(thrown.getMessage()).contains("archive could not be extracted");
        assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getCause().getMessage()).contains("empty");
        // Sanity check that the helper-built tgz isn't accidentally empty too:
        assertThat(emptyTgz.length).isPositive();
    }

    private static byte[] handBuildEmptyTgz() {
        try (java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();
             org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream gzipOut =
                 new org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(byteOut);
             org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tarOut =
                 new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzipOut)) {
            tarOut.finish();
            tarOut.close();
            gzipOut.close();
            return byteOut.toByteArray();
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    @Test
    void load_shouldThrowArchiveExtractFailed_whenEntryNameWrong() throws Exception {
        // Arrange — tarball with a single entry named "wrong-name.yaml"
        byte[] tgz = BundleArchiveBuilder.tgzOf(readMvpSeed(), "wrong-name.yaml");
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
            .thenReturn(responseBytesOf(tgz));

        // Act / Assert
        BundleLoadException thrown = assertThatExceptionOfType(BundleLoadException.class)
            .isThrownBy(() -> loader.load())
            .actual();
        assertThat(thrown.getMessage()).contains("archive could not be extracted");
        assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getCause().getMessage())
            .contains("expected entry vendor-mapping.yaml")
            .contains("wrong-name.yaml");
    }

    @Test
    void load_shouldThrowParseFailed_whenYamlInvalid() throws Exception {
        // Arrange — tarball whose yaml content is structurally invalid against the schema
        // (source_value contains the reserved '|' character).
        String invalidYaml = """
            apiVersion: registry.rapid7.com/v1
            kind: VendorMapping
            metadata:
              mapping_version: v1.0.0
            spec:
              vendors:
                - id: microsoft
                  name: Microsoft
                  services:
                    - id: microsoft-defender
                      name: Microsoft Defender
                      category: edr
                      data_sources:
                        - product: InsightIDR
                          source_type: product_type
                          source_value: "has|pipe"
                          display_name: Bad
            """;
        byte[] tgz = BundleArchiveBuilder.tgzOf(invalidYaml.getBytes(StandardCharsets.UTF_8), ENTRY_NAME);
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
            .thenReturn(responseBytesOf(tgz));

        // Act / Assert
        BundleLoadException thrown = assertThatExceptionOfType(BundleLoadException.class)
            .isThrownBy(() -> loader.load())
            .actual();
        assertThat(thrown.getMessage()).contains("could not be parsed");
        assertThat(thrown.getCause())
            .isInstanceOf(com.rapid7.integrationregistry.mapping.exception.BundleParseException.class);
    }

    @Test
    void load_shouldThrowCacheReadFailed_whenIoErrorOnRead() throws Exception {
        // Arrange — make the cache file a directory (so Files.readAllBytes throws IOException
        // on read; this is platform-portable: a directory is not a regular file).
        Path cacheFile = properties.cacheFilePath();
        Files.createDirectories(cacheFile);   // create as a directory, not a file

        // Act / Assert
        BundleLoadException thrown = assertThatExceptionOfType(BundleLoadException.class)
            .isThrownBy(() -> loader.load())
            .actual();
        assertThat(thrown.getMessage()).contains("disk cache could not be read");
        assertThat(thrown.path()).contains(cacheFile);
        verify(s3Client, never()).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
    }

    @Test
    void load_shouldUseExpectedBucketAndKey_whenFetchingS3() throws Exception {
        // Arrange
        byte[] tgz = BundleArchiveBuilder.tgzOf(readMvpSeed(), ENTRY_NAME);
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
            .thenReturn(responseBytesOf(tgz));
        org.mockito.ArgumentCaptor<GetObjectRequest> reqCaptor =
            org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);

        // Act
        loader.load();

        // Assert
        verify(s3Client).getObject(reqCaptor.capture(), any(ResponseTransformer.class));
        GetObjectRequest req = reqCaptor.getValue();
        assertThat(req.bucket()).isEqualTo(BUCKET);
        assertThat(req.key()).isEqualTo(EXPECTED_KEY);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=S3VendorMappingBundleLoaderTest`
Expected: FAIL with compilation error (`S3VendorMappingBundleLoader` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/loader/S3VendorMappingBundleLoader.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.BundleParser;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import com.rapid7.integrationregistry.mapping.exception.BundleParseException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Cache-first runtime loader that produces a {@link VendorMappingSnapshot} at
 * boot. The cache is a same-version-restart optimization (per RFC-001
 * §Bundle lifecycle): cache hit reads the local copy; cache corruption
 * (bad gzip / wrong tar entry / parse failure) deletes the file and falls
 * through to S3; cache-read I/O errors propagate as {@link BundleLoadException}.
 *
 * <p>Cache writes are atomic: bytes are written to a sibling temp file and
 * then renamed via {@link Files#move(Path, Path, java.nio.file.CopyOption...)}
 * with {@code ATOMIC_MOVE}, so a crash mid-write leaves either the previous
 * cache or no cache, never a partial file.
 *
 * <p>The bundle artifact is a {@code .tgz} (gzipped tarball) with a single
 * entry named {@code vendor-mapping.yaml}; the YAML is then handed to the
 * parser. Multi-entry tarballs and non-standard entry names are rejected.
 */
final class S3VendorMappingBundleLoader {

    private static final Logger log = LoggerFactory.getLogger(S3VendorMappingBundleLoader.class);
    private static final String BUNDLE_ENTRY_NAME = "vendor-mapping.yaml";
    private static final String FIELD_S3_CLIENT = "s3Client";
    private static final String FIELD_PARSER = "parser";
    private static final String FIELD_PROPERTIES = "properties";
    private static final String TEMP_PREFIX = "vendor-mapping-";
    private static final String TEMP_SUFFIX = ".tgz.partial";

    private final S3Client s3Client;
    private final BundleParser parser;
    private final VendorMappingProperties properties;

    S3VendorMappingBundleLoader(S3Client s3Client, BundleParser parser, VendorMappingProperties properties) {
        this.s3Client = Objects.requireNonNull(s3Client, FIELD_S3_CLIENT);
        this.parser = Objects.requireNonNull(parser, FIELD_PARSER);
        this.properties = Objects.requireNonNull(properties, FIELD_PROPERTIES);
    }

    VendorMappingSnapshot load() throws BundleLoadException {
        Path cacheFile = properties.cacheFilePath();
        if (Files.exists(cacheFile)) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(cacheFile);
            } catch (IOException ioe) {
                throw BundleLoadException.cacheReadFailed(cacheFile, ioe);
            }
            try {
                return parseFromBytes(bytes);
            } catch (BundleLoadException corruptCache) {
                log.warn("vendor mapping disk cache corrupted at {}, deleting and falling through to S3 — {}",
                         cacheFile, corruptCache.getMessage());
                deleteCorruptCache(cacheFile);
                // fall through to S3 fetch
            }
        }
        byte[] bytes = fetchFromS3();
        writeCacheAtomically(cacheFile, bytes);
        return parseFromBytes(bytes);
    }

    private byte[] fetchFromS3() throws BundleLoadException {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.s3Bucket())
                .key(properties.bundleObjectKey())
                .build();
            return s3Client.getObject(request, ResponseTransformer.toBytes()).asByteArray();
        } catch (SdkException ex) {
            throw BundleLoadException.s3FetchFailed(ex);
        }
    }

    private void writeCacheAtomically(Path cacheFile, byte[] bytes) throws BundleLoadException {
        try {
            Files.createDirectories(cacheFile.getParent());
            Path temp = Files.createTempFile(cacheFile.getParent(), TEMP_PREFIX, TEMP_SUFFIX);
            Files.write(temp, bytes);
            Files.move(temp, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw BundleLoadException.cacheWriteFailed(cacheFile, ex);
        }
    }

    private VendorMappingSnapshot parseFromBytes(byte[] tgzBytes) throws BundleLoadException {
        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(tgzBytes);
             GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(byteIn);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
            TarArchiveEntry entry = tarIn.getNextEntry();
            if (entry == null) {
                throw BundleLoadException.archiveExtractFailed(
                    new IllegalStateException("tarball is empty"));
            }
            if (!BUNDLE_ENTRY_NAME.equals(entry.getName())) {
                throw BundleLoadException.archiveExtractFailed(
                    new IllegalStateException("expected entry " + BUNDLE_ENTRY_NAME
                                              + " but found " + entry.getName()));
            }
            return parser.parse(tarIn);
        } catch (IOException ex) {
            throw BundleLoadException.archiveExtractFailed(ex);
        } catch (BundleParseException ex) {
            throw BundleLoadException.parseFailed(ex);
        } catch (IllegalStateException ex) {
            // BundleParser.parse(...) throws IllegalStateException for schema/enum-sync drift
            // (per its Javadoc) and for missing schema resource. Both are bundle-load failures.
            throw BundleLoadException.parseFailed(ex);
        }
    }

    private void deleteCorruptCache(Path cacheFile) {
        try {
            Files.deleteIfExists(cacheFile);
        } catch (IOException ioe) {
            log.warn("failed to delete corrupt cache file at {}; will overwrite via atomic move", cacheFile, ioe);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=S3VendorMappingBundleLoaderTest`
Expected: PASS — all nine tests green.

If `load_shouldThrowArchiveExtractFailed_whenTarballEmpty` fails because the hand-built empty tgz contains an EOF tar block that `getNextEntry()` actually returns as null (which is what we want), confirm by debug-printing `entry == null`. The test expects `null` to trigger the empty-tarball branch.

If `load_shouldThrowCacheReadFailed_whenIoErrorOnRead` fails on Linux but passes on macOS (or vice versa), the trick of "cache file is a directory" depends on `Files.readAllBytes` throwing `IOException` for a directory. If your platform throws a different exception type, replace with another reliable I/O failure: create a regular file then `chmod 000` it; or on JDK 25 use `Files.readAllBytes` on a non-existent file inside a non-existent parent (would throw `NoSuchFileException`). Adjust to a method that reliably triggers `IOException` on the platform.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/loader/S3VendorMappingBundleLoader.java src/test/java/com/rapid7/integrationregistry/mapping/loader/S3VendorMappingBundleLoaderTest.java
git commit -m "feat(track-04/wp-03): add S3VendorMappingBundleLoader cache-first loader"
```

---

## Task 8: `BundleLoadListener` — readiness-publishing `ApplicationListener`

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/loader/BundleLoadListener.java`
- Test: `src/test/java/com/rapid7/integrationregistry/mapping/loader/BundleLoadListenerTest.java`

**Why this is structurally important:** Spring Boot 4 auto-publishes `ReadinessState.ACCEPTING_TRAFFIC` when `ApplicationStartedEvent` fires. The listener overrides this by emitting `REFUSING_TRAFFIC` first, then performing the load, and only on success publishing `ACCEPTING_TRAFFIC`. A naive `ApplicationRunner` that publishes only on success would race the framework's auto-publish.

`final class implements ApplicationListener<ApplicationStartedEvent>`. Constructor injects `S3VendorMappingBundleLoader`, `VendorMappingSnapshotHolder`, `VendorMappingProperties`, `ApplicationEventPublisher`. `onApplicationEvent(...)` runs the four-step lifecycle: publish REFUSING, load, populate-and-decorate-and-set, publish ACCEPTING. On failure, log structured ERROR with `failure_class`, `bundle_version`, `s3_bucket`, `s3_key`; return without publishing ACCEPTING.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/loader/BundleLoadListenerTest.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BundleLoadListenerTest {

    private S3VendorMappingBundleLoader loader;
    private VendorMappingSnapshotHolder holder;
    private VendorMappingProperties properties;
    private ApplicationEventPublisher events;
    private BundleLoadListener listener;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        loader = mock(S3VendorMappingBundleLoader.class);
        holder = mock(VendorMappingSnapshotHolder.class);
        properties = new VendorMappingProperties("v1.0.0", "test-bucket", "registry/mappings/", Path.of("/tmp"));
        events = mock(ApplicationEventPublisher.class);
        listener = new BundleLoadListener(loader, holder, properties, events);

        logger = (Logger) LoggerFactory.getLogger(BundleLoadListener.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private static VendorMappingSnapshot stubSnapshot(String version) {
        return new VendorMappingSnapshot() {
            @Override
            public VendorResolution lookup(ProductName p, SourceType s, String v) {
                return VendorResolution.unknown();
            }
            @Override
            public String mappingVersion() {
                return version;
            }
        };
    }

    @Test
    void onApplicationEvent_shouldPublishRefusing_thenAccepting_whenLoadSucceeds() throws Exception {
        // Arrange
        when(loader.load()).thenReturn(stubSnapshot("v1.0.0"));

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert
        InOrder eventOrder = inOrder(events, holder);
        ArgumentCaptor<AvailabilityChangeEvent> firstEvent = ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
        ArgumentCaptor<AvailabilityChangeEvent> secondEvent = ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
        eventOrder.verify(events).publishEvent(firstEvent.capture());
        eventOrder.verify(holder).set(any(VendorMappingSnapshot.class));
        eventOrder.verify(events).publishEvent(secondEvent.capture());

        assertThat(firstEvent.getValue().getState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        assertThat(secondEvent.getValue().getState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void onApplicationEvent_shouldPublishRefusing_only_whenLoadFails() throws Exception {
        // Arrange
        when(loader.load()).thenThrow(BundleLoadException.s3FetchFailed(new IOException("boom")));

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert
        ArgumentCaptor<AvailabilityChangeEvent> capture = ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
        verify(events).publishEvent(capture.capture());   // exactly once
        assertThat(capture.getValue().getState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        verifyNoInteractions(holder);
    }

    @Test
    void onApplicationEvent_shouldPopulateHolder_whenLoadSucceeds() throws Exception {
        // Arrange
        VendorMappingSnapshot loaded = stubSnapshot("v1.0.0");
        when(loader.load()).thenReturn(loaded);

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert — the holder receives a decorator wrapping the loaded snapshot,
        // so the captured argument is a LoggingVendorMappingSnapshot whose
        // mappingVersion() delegates to the loaded snapshot's "v1.0.0".
        ArgumentCaptor<VendorMappingSnapshot> setCaptor =
            ArgumentCaptor.forClass(VendorMappingSnapshot.class);
        verify(holder).set(setCaptor.capture());
        VendorMappingSnapshot setSnapshot = setCaptor.getValue();
        assertThat(setSnapshot).isInstanceOf(LoggingVendorMappingSnapshot.class);
        assertThat(setSnapshot.mappingVersion()).isEqualTo("v1.0.0");
    }

    @Test
    void onApplicationEvent_shouldNotPopulateHolder_whenLoadFails() throws Exception {
        // Arrange
        when(loader.load()).thenThrow(BundleLoadException.s3FetchFailed(new IOException("boom")));

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert
        verify(holder, never()).set(any());
    }

    @Test
    void onApplicationEvent_shouldLogStructuredError_whenLoadFails() throws Exception {
        // Arrange
        when(loader.load()).thenThrow(BundleLoadException.s3FetchFailed(new IOException("connection reset")));

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert
        ILoggingEvent errorEvent = appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected ERROR log event"));
        String formatted = errorEvent.getFormattedMessage();
        assertThat(formatted)
            .contains("Vendor mapping bundle load failed")
            .contains("readiness will remain REFUSING_TRAFFIC")
            .contains("BundleLoadException")
            .contains("v1.0.0")
            .contains("test-bucket")
            .contains("registry/mappings/vendor-mapping-v1.0.0.tgz");
    }

    @Test
    void onApplicationEvent_shouldLogInfo_whenLoadSucceeds() throws Exception {
        // Arrange
        when(loader.load()).thenReturn(stubSnapshot("v1.0.0"));

        // Act
        listener.onApplicationEvent(mock(ApplicationStartedEvent.class));

        // Assert
        ILoggingEvent infoEvent = appender.list.stream()
            .filter(e -> e.getLevel() == Level.INFO)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected INFO log event"));
        String formatted = infoEvent.getFormattedMessage();
        assertThat(formatted)
            .contains("Vendor mapping bundle loaded")
            .contains("mapping_version=v1.0.0")
            .contains("bundle_version=v1.0.0");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=BundleLoadListenerTest`
Expected: FAIL with compilation error (`BundleLoadListener` does not exist).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/rapid7/integrationregistry/mapping/loader/BundleLoadListener.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.exception.BundleLoadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;

/**
 * Boot-time listener that wires the runtime lifecycle of vendor mapping.
 *
 * <p>Spring Boot 4 auto-publishes {@link ReadinessState#ACCEPTING_TRAFFIC}
 * when {@link ApplicationStartedEvent} fires. This listener overrides that
 * default by:
 *
 * <ol>
 *   <li>Publishing {@link ReadinessState#REFUSING_TRAFFIC} immediately on the
 *       same event.</li>
 *   <li>Loading the bundle (cache-first, S3 fallback).</li>
 *   <li>On success: wrapping the loaded snapshot in a
 *       {@link LoggingVendorMappingSnapshot} decorator, populating the
 *       {@link VendorMappingSnapshotHolder}, then publishing
 *       {@link ReadinessState#ACCEPTING_TRAFFIC}.</li>
 *   <li>On failure: logging a structured ERROR with failure class and
 *       bundle/S3 coordinates; readiness stays at
 *       {@code REFUSING_TRAFFIC} indefinitely so the replica is held out
 *       of rotation rather than serving with an empty snapshot.</li>
 * </ol>
 */
final class BundleLoadListener implements ApplicationListener<ApplicationStartedEvent> {

    private static final Logger log = LoggerFactory.getLogger(BundleLoadListener.class);

    private final S3VendorMappingBundleLoader loader;
    private final VendorMappingSnapshotHolder holder;
    private final VendorMappingProperties properties;
    private final ApplicationEventPublisher events;

    BundleLoadListener(S3VendorMappingBundleLoader loader,
                       VendorMappingSnapshotHolder holder,
                       VendorMappingProperties properties,
                       ApplicationEventPublisher events) {
        this.loader = loader;
        this.holder = holder;
        this.properties = properties;
        this.events = events;
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        VendorMappingSnapshot loaded;
        try {
            loaded = loader.load();
        } catch (BundleLoadException ex) {
            log.error("Vendor mapping bundle load failed; readiness will remain REFUSING_TRAFFIC. "
                    + "failure_class={} bundle_version={} s3_bucket={} s3_key={} cause={}",
                    ex.getClass().getSimpleName(),
                    properties.bundleVersion(),
                    properties.s3Bucket(),
                    properties.bundleObjectKey(),
                    ex.getMessage(), ex);
            return;
        }
        VendorMappingSnapshot decorated = new LoggingVendorMappingSnapshot(loaded);
        holder.set(decorated);
        log.info("Vendor mapping bundle loaded; mapping_version={} bundle_version={}",
                 decorated.mappingVersion(), properties.bundleVersion());
        AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=BundleLoadListenerTest`
Expected: PASS — all six tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/rapid7/integrationregistry/mapping/loader/BundleLoadListener.java src/test/java/com/rapid7/integrationregistry/mapping/loader/BundleLoadListenerTest.java
git commit -m "feat(track-04/wp-03): add BundleLoadListener with REFUSING-then-ACCEPTING readiness lifecycle"
```

---

## Task 9: Spring `@Configuration`, `application.yaml` defaults, `package-info.java`

**Files:**
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingConfiguration.java`
- Create: `src/main/java/com/rapid7/integrationregistry/mapping/loader/package-info.java`
- Modify: `src/main/resources/application.yaml`

**Why these three together:** the `@Configuration` wires every bean from Tasks 3–8 into the Spring context. `application.yaml` carries the `cache-dir` default. `package-info.java` documents the package's role and references the production types only after they all exist. None has its own dedicated unit test — the integration tests in Tasks 10 and 11 exercise the wiring end-to-end.

The `@Configuration` is `public class` (not final — Spring CGLIB proxies `@Configuration` classes by default). `@EnableConfigurationProperties(VendorMappingProperties.class)` activates the properties record. The `S3Client` bean is annotated `@ConditionalOnMissingBean` so integration tests can override with `@MockitoBean`. The holder is exposed both as `VendorMappingSnapshotHolder` (for the listener) and as `VendorMappingSnapshot` (for downstream consumers like T08).

- [ ] **Step 1: Create `VendorMappingConfiguration.java`**

Create `src/main/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingConfiguration.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.BundleParser;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring configuration for the vendor-mapping runtime loader. Activates
 * {@link VendorMappingProperties} and wires the bean graph: {@link S3Client}
 * (default credentials chain + region; overridable in tests via
 * {@code @MockitoBean}), {@link BundleParser} (Plan 02), the snapshot holder
 * (exposed both as {@link VendorMappingSnapshotHolder} for the listener and
 * as {@link VendorMappingSnapshot} for downstream consumers), the
 * {@link S3VendorMappingBundleLoader}, and the {@link BundleLoadListener}.
 */
@Configuration
@EnableConfigurationProperties(VendorMappingProperties.class)
public class VendorMappingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public S3Client s3Client() {
        return S3Client.create();
    }

    @Bean
    public BundleParser bundleParser() {
        return new BundleParser();
    }

    @Bean
    public VendorMappingSnapshotHolder vendorMappingSnapshotHolder() {
        return new VendorMappingSnapshotHolder();
    }

    @Bean
    public VendorMappingSnapshot vendorMappingSnapshot(VendorMappingSnapshotHolder holder) {
        return holder;
    }

    @Bean
    public S3VendorMappingBundleLoader bundleLoader(
            S3Client s3Client, BundleParser parser, VendorMappingProperties properties) {
        return new S3VendorMappingBundleLoader(s3Client, parser, properties);
    }

    @Bean
    public BundleLoadListener bundleLoadListener(
            S3VendorMappingBundleLoader loader,
            VendorMappingSnapshotHolder holder,
            VendorMappingProperties properties,
            ApplicationEventPublisher events) {
        return new BundleLoadListener(loader, holder, properties, events);
    }
}
```

- [ ] **Step 2: Create `package-info.java`**

Create `src/main/java/com/rapid7/integrationregistry/mapping/loader/package-info.java`:

```java
/**
 * Runtime lifecycle of vendor mapping — Spring-wired loader that fetches the
 * pinned bundle from S3 at boot, caches on local disk for same-version
 * restarts, builds the immutable
 * {@link com.rapid7.integrationregistry.mapping.VendorMappingSnapshot}, exposes
 * it as a bean, and gates {@code /actuator/health/readiness} on successful
 * load.
 *
 * <p>This is the only sub-package within
 * {@code com.rapid7.integrationregistry.mapping} that imports Spring Framework
 * or the AWS SDK — the core data layer
 * ({@code com.rapid7.integrationregistry.mapping}) stays framework-agnostic.
 * The boundary is enforced by the
 * {@code mappingCoreLayer_shouldNotDependOnFrameworks} ArchUnit rule.
 *
 * <p>See RFC-001 §Vendor mapping → Bundle lifecycle and §Operational notes
 * for the ground truth this package wires up.
 */
package com.rapid7.integrationregistry.mapping.loader;
```

- [ ] **Step 3: Modify `application.yaml`**

Modify `src/main/resources/application.yaml`. Locate the existing `management:` block (the section that exposes the health actuator endpoint). Append a new `integration-registry` block immediately before the first `---` profile separator. The new content:

```yaml
integration-registry:
  vendor-mapping:
    cache-dir: ${java.io.tmpdir}/integration-registry/vendor-mapping
    # bundle-version, s3-bucket, s3-key-prefix come from the deploy environment
    # via env vars (e.g. INTEGRATION_REGISTRY_VENDOR_MAPPING_BUNDLE_VERSION) or
    # per-profile overrides set at deploy time. They are intentionally absent
    # from this default so that boots without a configured bundle version fail
    # fast at property binding rather than silently picking up a stale default.
```

The full top-of-file block becomes:

```yaml
spring:
  application:
    name: integration-registry

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health

integration-registry:
  vendor-mapping:
    cache-dir: ${java.io.tmpdir}/integration-registry/vendor-mapping

---
spring:
  config:
    activate:
      on-profile: local

# ... (remaining profile blocks unchanged)
```

- [ ] **Step 4: Verify the existing test set still passes**

Run: `./mvnw -q test -Dtest='!*IntegrationTest'`
Expected: PASS for every unit test from Tasks 1–8 plus all pre-existing tests. The new `@Configuration` class has no unit test of its own (covered by the integration tests in Tasks 10/11), but its presence must not break loading of the existing application context (`IntegrationRegistryApplicationTests`).

If `IntegrationRegistryApplicationTests` (the auto-generated `@SpringBootTest` from project scaffolding) fails because Spring cannot bind `VendorMappingProperties` (missing required fields `bundleVersion`, `s3Bucket`, `s3KeyPrefix`), add a `@TestPropertySource` block on that test class providing dummy values, OR convert the test to a `@SpringBootTest(properties = {...})` with the same. Inspect first:

Run: `cat src/test/java/com/rapid7/integrationregistry/IntegrationRegistryApplicationTests.java`

If the test simply does `@SpringBootTest void contextLoads() {}`, edit it to:

```java
@SpringBootTest(properties = {
    "integration-registry.vendor-mapping.bundle-version=v1.0.0",
    "integration-registry.vendor-mapping.s3-bucket=test-bucket",
    "integration-registry.vendor-mapping.s3-key-prefix=test/mappings/"
})
class IntegrationRegistryApplicationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    software.amazon.awssdk.services.s3.S3Client s3Client;

    @org.junit.jupiter.api.Test
    void contextLoads() {}
}
```

The `@MockitoBean S3Client` is required because `S3Client.create()` will fail at bean creation if no AWS region/credentials are configured in the test environment.

Also: with the Spring context loading, the `BundleLoadListener` will fire on `ApplicationStartedEvent` and try to load the bundle — and fail because `S3Client` is mocked with no stubs. That's fine for `contextLoads()` (it asserts only that the context starts; the listener's failure is logged but doesn't break startup). If the test now passes with a noisy WARN/ERROR log, that's expected. If it fails because of the listener's failure handling somehow propagating, debug the listener — it must NEVER let an exception escape `onApplicationEvent`.

- [ ] **Step 5: Commit**

```bash
git add \
  src/main/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingConfiguration.java \
  src/main/java/com/rapid7/integrationregistry/mapping/loader/package-info.java \
  src/main/resources/application.yaml \
  src/test/java/com/rapid7/integrationregistry/IntegrationRegistryApplicationTests.java
git commit -m "feat(track-04/wp-03): wire VendorMappingConfiguration beans and yaml defaults"
```

If you did NOT need to modify `IntegrationRegistryApplicationTests.java`, omit it from the `git add`.

---

## Task 10: `VendorMappingBootIntegrationTest` — Spring-context boot scenarios

**Files:**
- Create: `src/test/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingBootIntegrationTest.java`

**Why this is one test class with three nested scenarios:** the three scenarios (valid bundle / S3 throws / invalid bundle) share Spring context overhead and the same `@DynamicPropertySource` setup. `@Nested` keeps them in one class without re-bootstrapping the application context. Each nested class stubs `S3Client.getObject(...)` differently and asserts on the resulting readiness state plus snapshot lookup behavior.

The test uses `@SpringBootTest`, `@MockitoBean S3Client`, `@TempDir` for cache-dir isolation, and `@DynamicPropertySource` to register the temp dir into `integration-registry.vendor-mapping.cache-dir`. The `ApplicationAvailability` bean is autowired and consulted directly — no HTTP-layer assertions.

- [ ] **Step 1: Write the test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingBootIntegrationTest.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rapid7.integrationregistry.mapping.ProductName;
import com.rapid7.integrationregistry.mapping.SourceType;
import com.rapid7.integrationregistry.mapping.VendorCategory;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.mapping.VendorResolution;
import com.rapid7.integrationregistry.testsupport.BundleArchiveBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class VendorMappingBootIntegrationTest {

    @TempDir
    static Path sharedTempDir;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("integration-registry.vendor-mapping.bundle-version", () -> "v1.0.0");
        registry.add("integration-registry.vendor-mapping.s3-bucket", () -> "test-bucket");
        registry.add("integration-registry.vendor-mapping.s3-key-prefix", () -> "registry/mappings/");
        registry.add("integration-registry.vendor-mapping.cache-dir", () -> sharedTempDir.toString());
    }

    @MockitoBean
    S3Client s3Client;

    @Autowired
    ApplicationAvailability availability;

    @Autowired
    VendorMappingSnapshot vendorMappingSnapshot;

    private static byte[] mvpSeedYaml;

    @BeforeAll
    static void loadSeed() throws IOException {
        try (InputStream stream = VendorMappingBootIntegrationTest.class.getResourceAsStream(
                "/vendor-mapping/bundle/mvp-seed.yaml")) {
            assertThat(stream).as("mvp-seed.yaml present on classpath").isNotNull();
            mvpSeedYaml = stream.readAllBytes();
        }
    }

    private static ResponseBytes<GetObjectResponse> responseBytesOf(byte[] body) {
        return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), body);
    }

    /**
     * Each nested scenario stubs the S3 mock differently AND triggers the
     * {@code BundleLoadListener} synchronously by re-publishing
     * {@link org.springframework.boot.context.event.ApplicationStartedEvent}
     * inside the test (because the framework only fires that event once at
     * actual application startup, before our test class finishes wiring
     * the stubs). To re-trigger the listener with the test stub in place,
     * we autowire the listener bean and call
     * {@code listener.onApplicationEvent(...)} directly.
     */
    @Nested
    class WhenS3ReturnsValidBundle {

        @Autowired
        BundleLoadListener listener;

        @BeforeEach
        void stubS3WithValidBundle() {
            byte[] tgz = BundleArchiveBuilder.tgzOf(mvpSeedYaml, "vendor-mapping.yaml");
            when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenReturn(responseBytesOf(tgz));
            // Re-trigger the listener with the stub in place.
            listener.onApplicationEvent(
                new org.springframework.boot.context.event.ApplicationStartedEvent(
                    new org.springframework.boot.SpringApplication(),
                    new String[]{},
                    null,
                    java.time.Duration.ZERO));
        }

        @Test
        void readiness_shouldBeAcceptingTraffic() {
            assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        }

        @Test
        void liveness_shouldBeCorrect() {
            assertThat(availability.getLivenessState()).isEqualTo(LivenessState.CORRECT);
        }

        @Test
        void snapshot_shouldResolveAllFourMvpTriplets() {
            // Microsoft Defender via InsightIDR
            VendorResolution idrDefender = vendorMappingSnapshot.lookup(
                ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "microsoft-defender-endpoint");
            assertThat(idrDefender.vendorServiceId()).isEqualTo("microsoft-defender");
            assertThat(idrDefender.vendorServiceName()).isEqualTo("Microsoft Defender");
            assertThat(idrDefender.vendorCategory()).isEqualTo(VendorCategory.EDR);
            assertThat(idrDefender.vendorId()).isEqualTo("microsoft");
            assertThat(idrDefender.vendorName()).isEqualTo("Microsoft");

            // Microsoft Defender via InsightConnect
            VendorResolution iconDefender = vendorMappingSnapshot.lookup(
                ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "microsoft-defender");
            assertThat(iconDefender.vendorServiceId()).isEqualTo("microsoft-defender");

            // Jira via InsightConnect
            VendorResolution jira = vendorMappingSnapshot.lookup(
                ProductName.INSIGHT_CONNECT, SourceType.PLUGIN_NAME, "jira");
            assertThat(jira.vendorServiceId()).isEqualTo("jira");
            assertThat(jira.vendorId()).isEqualTo("atlassian");

            // Negative control: unmapped triplet returns the synthetic record.
            VendorResolution mystery = vendorMappingSnapshot.lookup(
                ProductName.INSIGHT_IDR, SourceType.PRODUCT_TYPE, "no-such-source");
            assertThat(mystery).isSameAs(VendorResolution.unknown());
        }

        @Test
        void mappingVersion_shouldBeV100() {
            assertThat(vendorMappingSnapshot.mappingVersion()).isEqualTo("v1.0.0");
        }
    }

    @Nested
    class WhenS3Throws {

        @Autowired
        BundleLoadListener listener;

        private ListAppender<ILoggingEvent> appender;
        private Logger errorLogger;

        @BeforeEach
        void stubS3WithFailureAndAttachAppender() {
            errorLogger = (Logger) LoggerFactory.getLogger(BundleLoadListener.class);
            appender = new ListAppender<>();
            appender.start();
            errorLogger.addAppender(appender);

            when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenThrow(SdkClientException.create("connection reset"));

            listener.onApplicationEvent(
                new org.springframework.boot.context.event.ApplicationStartedEvent(
                    new org.springframework.boot.SpringApplication(),
                    new String[]{},
                    null,
                    java.time.Duration.ZERO));
        }

        @AfterEach
        void detachAppender() {
            errorLogger.detachAppender(appender);
        }

        @Test
        void readiness_shouldBeRefusingTraffic() {
            assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        }

        @Test
        void liveness_shouldStillBeCorrect() {
            // Replica is alive (not killed) but out of rotation.
            assertThat(availability.getLivenessState()).isEqualTo(LivenessState.CORRECT);
        }

        @Test
        void errorLog_shouldContainFailureClassAndS3Coordinates() {
            ILoggingEvent err = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected ERROR log event"));
            assertThat(err.getFormattedMessage())
                .contains("BundleLoadException")
                .contains("test-bucket")
                .contains("registry/mappings/vendor-mapping-v1.0.0.tgz");
        }
    }

    @Nested
    class WhenS3ReturnsInvalidBundle {

        @Autowired
        BundleLoadListener listener;

        private ListAppender<ILoggingEvent> appender;
        private Logger errorLogger;

        @BeforeEach
        void stubS3WithInvalidBundleAndAttachAppender() {
            errorLogger = (Logger) LoggerFactory.getLogger(BundleLoadListener.class);
            appender = new ListAppender<>();
            appender.start();
            errorLogger.addAppender(appender);

            // Bundle is structurally valid YAML but violates the schema:
            // source_value contains the reserved '|' character.
            String invalidYaml = """
                apiVersion: registry.rapid7.com/v1
                kind: VendorMapping
                metadata:
                  mapping_version: v1.0.0
                spec:
                  vendors:
                    - id: microsoft
                      name: Microsoft
                      services:
                        - id: microsoft-defender
                          name: Microsoft Defender
                          category: edr
                          data_sources:
                            - product: InsightIDR
                              source_type: product_type
                              source_value: "has|pipe"
                              display_name: Bad
                """;
            byte[] tgz = BundleArchiveBuilder.tgzOf(
                invalidYaml.getBytes(StandardCharsets.UTF_8), "vendor-mapping.yaml");
            when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenReturn(responseBytesOf(tgz));

            listener.onApplicationEvent(
                new org.springframework.boot.context.event.ApplicationStartedEvent(
                    new org.springframework.boot.SpringApplication(),
                    new String[]{},
                    null,
                    java.time.Duration.ZERO));
        }

        @AfterEach
        void detachAppender() {
            errorLogger.detachAppender(appender);
        }

        @Test
        void readiness_shouldBeRefusingTraffic() {
            assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        }

        @Test
        void errorLog_shouldContainParseFailureAndValidationDetail() {
            ILoggingEvent err = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected ERROR log event"));
            assertThat(err.getFormattedMessage())
                .contains("BundleLoadException")
                .contains("could not be parsed");
            // Validation messages from BundleParseException are accessible via
            // the cause chain. We assert that the cause chain reaches a
            // BundleParseException with non-empty validation messages.
            Throwable cause = err.getThrowableProxy() != null
                ? null   // logback ITP doesn't expose getThrowable directly; check via formatted message instead
                : null;
            // The formatted message includes the cause's message via the SLF4J
            // {} substitution for ex.getMessage(). Verify the message contains
            // the schema-violation hint.
            assertThat(err.getFormattedMessage()).containsIgnoringCase("source_value");
        }
    }
}
```

**Caveat on `ApplicationStartedEvent` re-firing:** Spring publishes that event exactly once during application startup. By the time the test class is wired and the `@MockitoBean S3Client` is in place, the framework's own publish has already fired with whatever stub state existed at startup (which is "no stubs" → `s3Client.getObject(...)` returns null → an NPE inside the loader, caught and logged). Re-publishing inside `@BeforeEach` re-runs the listener's logic against the fresh stub. The framework's earlier failure log will appear in the appender for `WhenS3ReturnsValidBundle` if the appender is attached before the re-trigger — that's why the appender attach is moved to scenarios that care about log capture (`WhenS3Throws`, `WhenS3ReturnsInvalidBundle`).

Note on `availability.getReadinessState()`: the listener's REFUSING-then-ACCEPTING sequence completes synchronously within the `onApplicationEvent` call, so by the time the test method asserts state, the final state is set.

- [ ] **Step 2: Run the test**

Run: `./mvnw test -Dtest=VendorMappingBootIntegrationTest`
Expected: PASS — all eight test methods green.

If `WhenS3ReturnsValidBundle.readiness_shouldBeAcceptingTraffic` fails because the holder was already populated by the framework-startup listener invocation (with the no-stub failure), and then `holder.set(...)` throws on the re-trigger, debug:
- The listener's first run failed → holder remains unpopulated.
- The listener's re-trigger succeeds → `holder.set(...)` succeeds.
- This is the intended path.

If the holder somehow got populated during framework startup and the re-trigger throws "snapshot already set", it means the framework startup actually succeeded (the no-stub Mockito default returned non-null). Tighten by making `@BeforeEach` reset `s3Client` first via `Mockito.reset(s3Client)` if needed.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingBootIntegrationTest.java
git commit -m "test(track-04/wp-03): add VendorMappingBootIntegrationTest for valid/throw/invalid scenarios"
```

---

## Task 11: `VendorMappingDiskCacheIntegrationTest` — pre-seeded cache hit

**Files:**
- Create: `src/test/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingDiskCacheIntegrationTest.java`

**Why this is a separate class:** the cache-hit assertion requires the cache file to exist on disk BEFORE the application context starts. That's a different setup contract than the boot scenarios in Task 10 (which assume an empty cache dir at startup). Splitting into two classes keeps `@DynamicPropertySource` setup readable.

The test pre-seeds `<tempDir>/vendor-mapping-v1.0.0.tgz` with valid MVP-seed-tgz bytes inside a static initializer (which runs before Spring's context bootstrap). After context refresh, asserts readiness ACCEPTING and `verify(s3Client, never()).getObject(...)`.

- [ ] **Step 1: Write the test**

Create `src/test/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingDiskCacheIntegrationTest.java`:

```java
package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import com.rapid7.integrationregistry.testsupport.BundleArchiveBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
class VendorMappingDiskCacheIntegrationTest {

    /**
     * The cache must exist BEFORE the Spring context starts. Using a static
     * field (not {@code @TempDir}) and seeding it in {@code @BeforeAll}
     * (which runs before the context bootstraps for the first test class
     * instance) ensures the framework's own {@code ApplicationStartedEvent}
     * listener invocation finds the file already in place.
     */
    private static Path cacheDir;
    private static Path cacheFile;

    @BeforeAll
    static void seedCacheBeforeContextBootstrap() throws IOException {
        cacheDir = Files.createTempDirectory("vendor-mapping-cache-test-");
        cacheFile = cacheDir.resolve("vendor-mapping-v1.0.0.tgz");

        byte[] mvpSeedYaml;
        try (InputStream stream = VendorMappingDiskCacheIntegrationTest.class.getResourceAsStream(
                "/vendor-mapping/bundle/mvp-seed.yaml")) {
            assertThat(stream).as("mvp-seed.yaml present on classpath").isNotNull();
            mvpSeedYaml = stream.readAllBytes();
        }
        byte[] tgz = BundleArchiveBuilder.tgzOf(mvpSeedYaml, "vendor-mapping.yaml");
        Files.write(cacheFile, tgz);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("integration-registry.vendor-mapping.bundle-version", () -> "v1.0.0");
        registry.add("integration-registry.vendor-mapping.s3-bucket", () -> "test-bucket");
        registry.add("integration-registry.vendor-mapping.s3-key-prefix", () -> "registry/mappings/");
        registry.add("integration-registry.vendor-mapping.cache-dir", () -> cacheDir.toString());
    }

    @MockitoBean
    S3Client s3Client;

    @Autowired
    ApplicationAvailability availability;

    @Autowired
    VendorMappingSnapshot vendorMappingSnapshot;

    @Test
    void readiness_shouldBeAcceptingTraffic_whenCacheIsPresent() {
        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void s3Client_shouldNotBeCalled_whenCacheIsPresent() {
        verify(s3Client, never()).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
    }

    @Test
    void mappingVersion_shouldComeFromCachedBundle() {
        assertThat(vendorMappingSnapshot.mappingVersion()).isEqualTo("v1.0.0");
    }
}
```

The `@BeforeAll` runs once per test class, BEFORE Spring's context bootstrap for that class — JUnit 5 is documented to call `@BeforeAll` static methods before any other test infrastructure (including `SpringExtension`'s context loading). This is the seam that lets us prepare the cache file before the framework's own `ApplicationStartedEvent` fires.

- [ ] **Step 2: Run the test**

Run: `./mvnw test -Dtest=VendorMappingDiskCacheIntegrationTest`
Expected: PASS — all three test methods green.

If `s3Client_shouldNotBeCalled_whenCacheIsPresent` fails (S3 was called once), the cache file path computed by `VendorMappingProperties.cacheFilePath()` does NOT match the path we seeded. Diagnose by printing both paths:
- Expected: `cacheFile` from the test = `<tempdir>/vendor-mapping-v1.0.0.tgz`
- Actual: `properties.cacheFilePath()` from the configured `cache-dir` property
The two must be byte-equal.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/rapid7/integrationregistry/mapping/loader/VendorMappingDiskCacheIntegrationTest.java
git commit -m "test(track-04/wp-03): add VendorMappingDiskCacheIntegrationTest for cache-hit S3 skip"
```

---

## Task 12: Full-build verification checkpoint

**Files:** none — pure verification.

This is the gate that proves the work plan's Acceptance signals: every test green, ArchUnit green, PMD green.

- [ ] **Step 1: Run full verify**

Run: `./mvnw verify`
Expected:
- `BUILD SUCCESS`
- All JUnit tests pass:
  - The new test classes from Tasks 2–8, 10, 11 (`BundleLoadExceptionTest`, `VendorMappingPropertiesTest`, `VendorMappingSnapshotHolderTest`, `LoggingVendorMappingSnapshotTest`, `S3VendorMappingBundleLoaderTest`, `BundleLoadListenerTest`, `VendorMappingBootIntegrationTest`, `VendorMappingDiskCacheIntegrationTest`).
  - Plus the existing tests merged from Plan 01 and Plan 02 (`VendorCategoryTest`, `SourceTypeTest`, `ProductNameTest`, `VendorResolutionTest`, `BundleSchemaTest`, `EnumSchemaSyncTest`, `BundleParseExceptionTest`, `MapBackedVendorMappingSnapshotTest`, `BundleParserTest`, `MvpSeedBundleTest`).
  - Plus the architecture tests (now seven `@ArchTest` rules including the new `mappingCoreLayer_shouldNotDependOnFrameworks`), `LayerDependencyViolationDetectionTest`, the adapter tests, and `IntegrationRegistryApplicationTests`.
- ArchUnit reports zero violations. The new types in `mapping/loader/` import Spring + AWS SDK + commons-compress — these are all permitted because the rule explicitly excludes `..mapping.loader..`. The `mapping/exception/BundleLoadException` imports only JDK — also permitted (the rule excludes `..mapping.exception..`). The core `mapping/` types (parser, snapshot, enums) still import only JDK + Jackson + networknt — passes the rule.
- PMD reports zero violations across both `src/main/java` and `src/test/java`.

- [ ] **Step 2: If `./mvnw verify` fails, diagnose and fix**

Common failure modes specific to this plan:

- **PMD `AvoidDuplicateLiterals`** — fires on a literal repeated 4+ times in one file. The `S3VendorMappingBundleLoader.BUNDLE_ENTRY_NAME` constant prevents this for the entry name. The `FIELD_*` constants in `VendorMappingProperties`, `VendorMappingSnapshotHolder`, `S3VendorMappingBundleLoader`, and `LoggingVendorMappingSnapshot` prevent it for null-guard messages. If the rule fires anywhere else, find the duplicate literal and extract a `private static final String` constant.
- **PMD `EmptyCatchBlock`** — every `catch` in `S3VendorMappingBundleLoader` either rethrows as `BundleLoadException`, logs WARN and falls through (cache-corrupt), or logs WARN (delete-corrupt-cache failure). None are empty. The `BundleLoadListener`'s catch logs ERROR and returns. None silent.
- **PMD `CommentContent`** — would fire on `TODO|FIXME|HACK|XXX`. Remove any such comment; if you need to mark deferred work, capture it as a follow-up work plan via the `work-plans` skill instead.
- **PMD `AvoidInstantiatingObjectsInLoops`** — should not fire in this plan (no per-iteration allocations of domain types; all allocations are one-shot).
- **PMD `MutableStaticState`** — should not fire. All static fields are `final`.
- **ArchUnit `mappingCoreLayer_shouldNotDependOnFrameworks`** — if this fires, you imported a Spring or AWS SDK type into a class in `mapping/` core (i.e., NOT under `mapping.loader.` and NOT under `mapping.exception.`). The fix is either to move the class into `mapping.loader.` (if it has runtime concerns) or to remove the import (if it was accidental).
- **ArchUnit `aggregatorLayer_shouldNotDependOnNonMappingLayers`** — should not fire from this plan (no aggregator changes).
- **`VendorMappingBootIntegrationTest` listener-double-invocation issue** — if the framework's own `ApplicationStartedEvent` fired before the test stubs were in place, the listener already attempted a load (failed, since `s3Client` returned null on the unstubbed mock). On the test's manual re-trigger, `holder.set(...)` succeeds with the second snapshot. If somehow both invocations succeeded (the unstubbed mock returned a non-null `ResponseBytes` due to deep-stubbing config), the second `set` throws "snapshot already set". The fix is to ensure the test class does NOT enable Mockito's deep-stubbing (Spring Boot 4's `@MockitoBean` default behavior is `Answers.RETURNS_DEFAULTS`, returning null — not deep-stubbed). If you see this, add `@MockitoBean(answers = Answers.RETURNS_DEFAULTS)` explicitly.
- **`VendorMappingDiskCacheIntegrationTest` cache miss** — if the test asserts that S3 was never called but Mockito reports a single call, the cache path is wrong. Print `properties.cacheFilePath()` vs. `cacheFile` and align.
- **`IntegrationRegistryApplicationTests` context-load failure** — see Task 9 Step 4 for the recipe to add the property values + `@MockitoBean S3Client` to that test.

Diagnose, fix, re-run. Do not bypass the gate.

- [ ] **Step 3: Confirm git state**

Run: `git status && git log --oneline main..HEAD`

Expected:
- `git status` reports a clean working tree (no uncommitted changes).
- `git log` shows the commits matching:
  - The spec commit from the brainstorming step (if committed; the spec file lives in `docs/superpowers/specs/` and is part of this plan's working set).
  - Eleven feature/build/test/docs commits, one per Task 1–11. Specifically:
    - `build(track-04/wp-03): add aws-sdk-s3 + commons-compress; add mapping-core framework-import ban`
    - `feat(track-04/wp-03): add BundleLoadException payload-style checked exception`
    - `feat(track-04/wp-03): add VendorMappingProperties configuration record`
    - `feat(track-04/wp-03): add VendorMappingSnapshotHolder one-shot bean wrapper`
    - `feat(track-04/wp-03): add LoggingVendorMappingSnapshot WARN-on-unknown decorator`
    - `test(track-04/wp-03): add BundleArchiveBuilder testsupport helper for .tgz bytes`
    - `feat(track-04/wp-03): add S3VendorMappingBundleLoader cache-first loader`
    - `feat(track-04/wp-03): add BundleLoadListener with REFUSING-then-ACCEPTING readiness lifecycle`
    - `feat(track-04/wp-03): wire VendorMappingConfiguration beans and yaml defaults`
    - `test(track-04/wp-03): add VendorMappingBootIntegrationTest for valid/throw/invalid scenarios`
    - `test(track-04/wp-03): add VendorMappingDiskCacheIntegrationTest for cache-hit S3 skip`
  - Total: 11 commits (plus the optional spec commit).

- [ ] **Step 4: Stop**

Do **not** invoke `superpowers:finishing-a-development-branch`. The implementation is done; control returns to the parent `execute-plan` skill for the functional review gate (Phase 7), simplify gate (Phase 8), external code review (Phase 9), and close-out (Phase 10).

---

## Self-review notes

**Spec coverage check:**
- §Architecture (loader sub-package, layering boundary, ArchUnit rule) → Task 1 (POM + ArchUnit), Tasks 3–9 (loader sub-package files).
- §Components: `VendorMappingProperties` → Task 3; `VendorMappingConfiguration` → Task 9; `VendorMappingSnapshotHolder` → Task 4; `S3VendorMappingBundleLoader` → Task 7; `BundleLoadListener` → Task 8; `LoggingVendorMappingSnapshot` → Task 5; `package-info.java` → Task 9; `BundleLoadException` → Task 2; `application.yaml` → Task 9; `pom.xml` deps → Task 1; ArchUnit rule → Task 1.
- §Data flow → reflected in `S3VendorMappingBundleLoader.load()` (Task 7 Step 3) and `BundleLoadListener.onApplicationEvent()` (Task 8 Step 3).
- §Error handling → covered in Task 2 (BundleLoadException factories), Task 7 (loader failure-mode wrapping), Task 8 (listener structured ERROR log).
- §Testing: all unit-test classes accounted for in Tasks 2–8; `BundleArchiveBuilder` helper in Task 6; integration tests in Tasks 10 and 11.
- §Disk cache semantics (atomic-rename write, self-healing on corruption, version-keyed filename) → Task 7 implementation + tests.
- §Build verification → Task 12.
- §Out-of-scope items → echoed in the Hard non-goals callout in the header.

**Type-consistency check:**
- `BundleLoadException` factories: `s3FetchFailed(Throwable)`, `cacheReadFailed(Path, Throwable)`, `cacheWriteFailed(Path, Throwable)`, `archiveExtractFailed(Throwable)`, `parseFailed(Throwable)` — defined in Task 2; called from `S3VendorMappingBundleLoader` in Task 7; asserted by tests in Tasks 2 and 7.
- `BundleLoadException.path()` returns `Optional<Path>` — defined in Task 2; asserted in Tasks 2 (own behavior) and 7 (`load_shouldThrowCacheReadFailed_whenIoErrorOnRead` checks `thrown.path()`).
- `VendorMappingProperties.bundleObjectKey()` returns `s3KeyPrefix + "vendor-mapping-" + bundleVersion + ".tgz"` — defined in Task 3; consumed by `S3VendorMappingBundleLoader.fetchFromS3()` in Task 7 and asserted in `S3VendorMappingBundleLoaderTest.load_shouldUseExpectedBucketAndKey_whenFetchingS3` and in `BundleLoadListenerTest.onApplicationEvent_shouldLogStructuredError_whenLoadFails`.
- `VendorMappingProperties.cacheFilePath()` returns `cacheDir.resolve(...)` — defined in Task 3; consumed by `S3VendorMappingBundleLoader.load()` in Task 7.
- `VendorMappingSnapshotHolder.set(VendorMappingSnapshot)` is one-shot, package-private — defined in Task 4; called by `BundleLoadListener.onApplicationEvent` in Task 8 with a `LoggingVendorMappingSnapshot`-wrapped argument.
- `LoggingVendorMappingSnapshot` constructor takes `VendorMappingSnapshot delegate` — defined in Task 5; called by listener in Task 8 with the loaded snapshot.
- `S3VendorMappingBundleLoader.load()` returns `VendorMappingSnapshot` and throws `BundleLoadException` — defined in Task 7; called by listener in Task 8.
- `BundleLoadListener` constructor signature `(S3VendorMappingBundleLoader, VendorMappingSnapshotHolder, VendorMappingProperties, ApplicationEventPublisher)` — defined in Task 8; wired in Task 9 by `bundleLoadListener(...)` `@Bean` method with the four-arg call.
- `VendorMappingConfiguration.s3Client()` produces `S3Client` annotated `@ConditionalOnMissingBean` — Task 9; integration tests override via `@MockitoBean S3Client` in Tasks 10 and 11.
- ArchUnit rule package patterns — `..mapping..` excluding `..mapping.loader..` and `..mapping.exception..` — Task 1 in `LayerDependencyRules.java` and registered in `LayerDependencyRulesTest.java`.
- Tar entry name `"vendor-mapping.yaml"` — referenced as `BUNDLE_ENTRY_NAME` constant in `S3VendorMappingBundleLoader` (Task 7) and as the literal `"vendor-mapping.yaml"` in `BundleArchiveBuilder.tgzOf(...)` calls in Tasks 7, 10, 11. Renaming it requires updating all four sites consistently.

**Placeholder scan:** no TODO/FIXME/HACK/XXX, no "implement appropriate X", no "similar to Task N" without code. Every code block is complete; every command has expected output described.

**Scope check:** the plan covers POM deps + ArchUnit rule + 7 production types + 1 exception + yaml block + package-info + 1 testsupport helper + 7 unit/integration test classes + final verify. Each task ends with a self-contained commit. T08 (aggregator), T09 (controller), T11 (publish pipeline), and the deferred hot-reload feature are explicitly out of scope and called out in the Hard non-goals header.
