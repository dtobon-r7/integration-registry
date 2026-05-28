# Design — Boot-time S3 loader with readiness gate and disk cache

**Work plan**: [`engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/work-plans/03-s3-loader-readiness-gate.md`](../../../../../engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/work-plans/03-s3-loader-readiness-gate.md)
**Track**: 04 — Vendor mapping bundle and snapshot
**Branch**: `worktree-track-04-plan-03` (worktree at `repos/platform/integration-registry/.claude/worktrees/track-04-plan-03`)
**Date**: 2026-05-28

---

## Outcome

Land the **runtime lifecycle** of vendor mapping: the Spring-context wiring that turns "a bundle exists in S3" into "the snapshot bean serves traffic."

Plans 01 and 02 shipped the contract layer (interface, record, enums, JSON Schema) and the stateless data layer (parser, snapshot, MVP seed). This plan ships the runtime layer that reads `MAPPING_BUNDLE_VERSION` from configuration, fetches `vendor-mapping-vX.Y.Z.tgz` from S3 (AWS SDK v2), caches on local disk for same-version restarts, calls `BundleParser.parse(...)` to produce the immutable snapshot, exposes that snapshot as a Spring bean, holds `/actuator/health/readiness` DOWN until the first successful load, and emits WARN logs (with `mapping_version`) on every unknown-triplet lookup.

After this PR ships, **all of Track 04's exit criteria are met**: the registry can boot, fetch and validate its bundle, build the snapshot, gate readiness, and surface unknowns. Tracks 08 (aggregator) and 09 (controller) can autowire `VendorMappingSnapshot` and rely on the readiness gate to ensure they only run post-load.

Seven new production types under a new sub-package `com.rapid7.integrationregistry.mapping.loader`, plus one new exception in `mapping.exception`, plus an `application.yaml` block, plus two new POM dependencies, plus one new ArchUnit rule.

## Architecture

The runtime layer lives entirely in `mapping/loader/`. The core data layer (parser, snapshot, enums, records) stays a pure data layer — no Spring annotations, no AWS imports, no logger field. A new ArchUnit rule enforces this:

```java
noClasses().that().resideInAPackage("..mapping..")
    .and().resideOutsideOfPackages("..mapping.loader..", "..mapping.exception..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework..", "software.amazon.awssdk..");
```

The loader sub-package is the only place Spring and AWS SDK imports are permitted within `mapping/`. The existing rule that no internal layer except `aggregator` may depend on `..mapping..` is unchanged — `..mapping.loader..` is *within* `..mapping..`, so the existing rule still applies.

### Lifecycle

Readiness is gated by a custom `BundleLoadHealthIndicator` registered in the `readiness` health group, NOT by Spring's `ApplicationAvailability` / `AvailabilityChangeEvent` machinery. Spring Boot 4's framework unconditionally publishes `ReadinessState.ACCEPTING_TRAFFIC` from `EventPublishingRunListener.ready(...)` *after* all `ApplicationReadyEvent` listeners run; any `REFUSING_TRAFFIC` a listener publishes within the same `ready()` call is overwritten. The custom `HealthIndicator` bypasses that race entirely: it consults the snapshot holder at every probe call, so the readiness state always reflects the actual load outcome regardless of event ordering.

```
Spring context refresh
 → @Bean s3Client, bundleParser, snapshotHolder, bundleLoader, bundleLoadListener,
        bundleLoadHealthIndicator created
 → BundleLoadListener receives ApplicationStartedEvent:
     [1] holder.isLoaded()? → if yes, no-op (defensive guard)
     [2] snapshot = loader.load()                   ← cache check, S3 fetch, parse
     [3] holder.set(new LoggingVendorMappingSnapshot(snapshot))   ← on success
     [4] log INFO with mapping_version + bundle_version
   On failure at [2]:
     log structured ERROR; holder stays empty; control returns cleanly so
     context refresh completes (replica alive, /actuator/health/liveness UP)
 → /actuator/health/readiness aggregates `readinessState` (framework) +
   `bundleLoad` (our indicator). The indicator returns DOWN when the holder
   is empty, UP when populated. Any DOWN component pulls the group to DOWN.
```

### Bean shape

The `VendorMappingSnapshot` exposed as a bean is a `VendorMappingSnapshotHolder` — an `AtomicReference<VendorMappingSnapshot>`-backed wrapper that itself implements `VendorMappingSnapshot`. Pre-load reads (`lookup()` / `mappingVersion()`) throw `IllegalStateException("snapshot not yet loaded — readiness should have prevented this call")`. Post-load reads delegate to the loaded snapshot. The readiness gate is the upstream contract that prevents pre-load reads in production; the `IllegalStateException` is a defensive failure mode for the case where the gate is bypassed (e.g., a misconfigured probe).

### Logging

The Plan 02 snapshot does no logging — `MapBackedVendorMappingSnapshot.lookup(...)` is a pure map lookup. Plan 03's WARN-on-unknown lives in a decorator: `LoggingVendorMappingSnapshot` wraps the parsed snapshot at load time, and its `lookup(...)` inspects the result. When the underlying lookup returns `VendorResolution.unknown()`, the decorator logs WARN with the raw triplet and `mapping_version`. The decorator is wired in step [3] above, so it's transparently active for every aggregator call once readiness flips ACCEPTING.

### Disk cache semantics

Per RFC-001 §Bundle lifecycle, the disk cache is a **same-version-restart optimization only** — it never substitutes for an unfetchable pinned version, and it never serves a cross-version load. The cache filename embeds the version (`vendor-mapping-vX.Y.Z.tgz`) so a version bump always falls through to S3.

The cache is **self-healing on corruption**: if the cached file fails to gunzip / untar / parse, the loader logs WARN, deletes the corrupt file, and falls through to S3. The atomic-rename write (write to sibling tempfile, then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`) prevents partial writes from a crash mid-write.

### Layering boundary

```
┌────────────────────────────────────────────────────────────┐
│  mapping.loader  (Spring + AWS SDK + commons-compress OK)  │
│    BundleLoadListener, S3VendorMappingBundleLoader,        │
│    VendorMappingSnapshotHolder, LoggingVendorMappingSnapshot,│
│    VendorMappingProperties, VendorMappingConfiguration     │
├────────────────────────────────────────────────────────────┤
│  mapping  (pure data layer — no Spring, no AWS, no logger) │
│    BundleParser, VendorMappingSnapshot,                    │
│    MapBackedVendorMappingSnapshot, VendorResolution,       │
│    VendorCategory, ProductName, SourceType                 │
├────────────────────────────────────────────────────────────┤
│  mapping.exception  (Spring/AWS ban does not apply)        │
│    BundleParseException, BundleLoadException               │
└────────────────────────────────────────────────────────────┘
```

## Components

All paths absolute under `src/main/java/com/rapid7/integrationregistry/`.

### `mapping/loader/VendorMappingProperties.java`

`@ConfigurationProperties` record bound to prefix `integration-registry.vendor-mapping`.

```java
package com.rapid7.integrationregistry.mapping.loader;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Objects;

@ConfigurationProperties("integration-registry.vendor-mapping")
public record VendorMappingProperties(
    String bundleVersion,        // e.g. "v1.0.0" — REQUIRED, no default
    String s3Bucket,             // e.g. "rapid7-...registry-mappings" — REQUIRED
    String s3KeyPrefix,          // e.g. "registry/mappings/" — REQUIRED, with trailing slash
    Path cacheDir                // default ${java.io.tmpdir}/integration-registry/vendor-mapping
) {
    public VendorMappingProperties {
        Objects.requireNonNull(bundleVersion, "bundleVersion");
        Objects.requireNonNull(s3Bucket, "s3Bucket");
        Objects.requireNonNull(s3KeyPrefix, "s3KeyPrefix");
        if (cacheDir == null) {
            cacheDir = Path.of(System.getProperty("java.io.tmpdir"),
                               "integration-registry", "vendor-mapping");
        }
    }

    public String bundleObjectKey() {
        return s3KeyPrefix + "vendor-mapping-" + bundleVersion + ".tgz";
    }

    public Path cacheFilePath() {
        return cacheDir.resolve("vendor-mapping-" + bundleVersion + ".tgz");
    }
}
```

Activated via `@EnableConfigurationProperties(VendorMappingProperties.class)` on `VendorMappingConfiguration`.

### `mapping/loader/VendorMappingConfiguration.java`

`@Configuration` defining the bean graph.

```java
@Configuration
@EnableConfigurationProperties(VendorMappingProperties.class)
public class VendorMappingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public S3Client s3Client() {
        return S3Client.create();   // default credentials chain, default region
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
        return holder;   // single bean serves both interface types
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

`@ConditionalOnMissingBean` on `S3Client` is the test-override seam: integration tests declare `@MockitoBean S3Client` and the conditional skips the real bean.

### `mapping/loader/VendorMappingSnapshotHolder.java`

Package-private final class implementing `VendorMappingSnapshot`. One-shot population.

```java
final class VendorMappingSnapshotHolder implements VendorMappingSnapshot {

    private static final String NOT_LOADED_MESSAGE =
        "snapshot not yet loaded — readiness should have prevented this call";

    private final AtomicReference<VendorMappingSnapshot> ref = new AtomicReference<>();

    void set(VendorMappingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
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

### `mapping/loader/S3VendorMappingBundleLoader.java`

Package-private final class. Single public method `load() throws BundleLoadException`.

```java
final class S3VendorMappingBundleLoader {

    private static final Logger log = LoggerFactory.getLogger(S3VendorMappingBundleLoader.class);
    private static final String BUNDLE_ENTRY_NAME = "vendor-mapping.yaml";

    private final S3Client s3Client;
    private final BundleParser parser;
    private final VendorMappingProperties properties;

    S3VendorMappingBundleLoader(S3Client s3Client, BundleParser parser, VendorMappingProperties properties) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    VendorMappingSnapshot load() throws BundleLoadException {
        Path cacheFile = properties.cacheFilePath();
        if (Files.exists(cacheFile)) {
            try {
                return parseFromBytes(Files.readAllBytes(cacheFile));
            } catch (IOException ioe) {
                throw BundleLoadException.cacheReadFailed(cacheFile, ioe);
            } catch (BundleLoadException corruptCache) {
                log.warn("vendor mapping disk cache corrupted at {}, deleting and falling through to S3 — {}",
                         cacheFile, corruptCache.getMessage());
                deleteCorruptCache(cacheFile);
                // fall through
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
            Path temp = Files.createTempFile(cacheFile.getParent(), "vendor-mapping-", ".tgz.partial");
            Files.write(temp, bytes);
            Files.move(temp, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw BundleLoadException.cacheWriteFailed(cacheFile, ex);
        }
    }

    private VendorMappingSnapshot parseFromBytes(byte[] tgzBytes) throws BundleLoadException {
        try (var byteIn = new ByteArrayInputStream(tgzBytes);
             var gzipIn = new GzipCompressorInputStream(byteIn);
             var tarIn = new TarArchiveInputStream(gzipIn)) {
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
            return parser.parse(tarIn);   // BundleParser does not close the underlying stream
        } catch (IOException ex) {
            throw BundleLoadException.archiveExtractFailed(ex);
        } catch (BundleParseException | IllegalStateException ex) {
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

Notes:
- The cache-corruption fallthrough catches `BundleLoadException` from `parseFromBytes` (any of `archiveExtractFailed` or `parseFailed`); it does not catch `cacheReadFailed` (that's a real read I/O error and propagates).
- `parser.parse(tarIn)` consumes the tar entry stream; the parser does not close its input (per `BundleParser`'s `parse(InputStream)` contract).

### `mapping/loader/BundleLoadListener.java`

`ApplicationListener<ApplicationStartedEvent>`. Loads the bundle and populates the holder. Failure semantics are surfaced via the `BundleLoadHealthIndicator` (below), NOT via `AvailabilityChangeEvent`.

```java
final class BundleLoadListener implements ApplicationListener<ApplicationStartedEvent> {

    private static final Logger log = LoggerFactory.getLogger(BundleLoadListener.class);

    private final S3VendorMappingBundleLoader loader;
    private final VendorMappingSnapshotHolder holder;
    private final VendorMappingProperties properties;

    BundleLoadListener(S3VendorMappingBundleLoader loader,
                       VendorMappingSnapshotHolder holder,
                       VendorMappingProperties properties) {
        this.loader = loader;
        this.holder = holder;
        this.properties = properties;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        if (holder.isLoaded()) {
            return;   // defensive: holder is one-shot
        }
        VendorMappingSnapshot loaded;
        try {
            loaded = loader.load();
        } catch (BundleLoadException | RuntimeException ex) {
            log.error("Vendor mapping bundle load failed; readiness will report DOWN. "
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
    }
}
```

The error log carries the failure class. The `BundleLoadException` factories embed enough context (path, cause class) in their message that grepping the log identifies the failure mode without needing to reconstruct from the stack trace. The `RuntimeException` arm of the catch is intentional and load-bearing: a startup failure must never propagate out of `onApplicationEvent` and crash context refresh (which would force the replica into a hard-failed state instead of a held-out-of-rotation state). PMD's `AvoidCatchingGenericException` is suppressed locally with this rationale.

### `mapping/loader/BundleLoadHealthIndicator.java`

Spring Boot 4 `HealthIndicator` consulted by `/actuator/health/readiness`. Returns `UP` (with `mapping_version` + `bundle_version` details) when the holder is populated, `DOWN` (with `reason: vendor mapping bundle not yet loaded` + `bundle_version`) when empty. Registered in the readiness group via `management.endpoint.health.group.readiness.include: readinessState,bundleLoad` in `application.yaml`.

```java
final class BundleLoadHealthIndicator implements HealthIndicator {

    private final VendorMappingSnapshotHolder holder;
    private final VendorMappingProperties properties;

    BundleLoadHealthIndicator(VendorMappingSnapshotHolder holder, VendorMappingProperties properties) {
        this.holder = holder;
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (holder.isLoaded()) {
            return Health.up()
                .withDetail("mapping_version", holder.mappingVersion())
                .withDetail("bundle_version", properties.bundleVersion())
                .build();
        }
        return Health.down()
            .withDetail("reason", "vendor mapping bundle not yet loaded")
            .withDetail("bundle_version", properties.bundleVersion())
            .build();
    }
}
```

### `mapping/loader/LoggingVendorMappingSnapshot.java`

Package-private final class implementing `VendorMappingSnapshot`. WARN on unknown.

```java
final class LoggingVendorMappingSnapshot implements VendorMappingSnapshot {

    private static final Logger log = LoggerFactory.getLogger(LoggingVendorMappingSnapshot.class);

    private final VendorMappingSnapshot delegate;

    LoggingVendorMappingSnapshot(VendorMappingSnapshot delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
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

### `mapping/loader/package-info.java`

```java
/**
 * Runtime lifecycle of vendor mapping — Spring-wired loader that fetches the
 * pinned bundle from S3 at boot, caches on local disk for same-version
 * restarts, builds the immutable {@link VendorMappingSnapshot}, exposes it as
 * a bean, and gates {@code /actuator/health/readiness} on successful load.
 *
 * <p>This is the only sub-package within {@code mapping/} that imports Spring
 * Framework or the AWS SDK — the core data layer
 * ({@code com.rapid7.integrationregistry.mapping}) stays framework-agnostic.
 * The boundary is enforced by an ArchUnit rule.
 *
 * <p>See RFC-001 §Vendor mapping → Bundle lifecycle and §Operational notes for
 * the ground truth this package wires up.
 */
package com.rapid7.integrationregistry.mapping.loader;
```

### `mapping/exception/BundleLoadException.java`

Payload-style checked exception per ADR-001. **Not** `final` (per ADR-001's shared invariant).

```java
package com.rapid7.integrationregistry.mapping.exception;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Thrown by {@code S3VendorMappingBundleLoader.load()} when the bundle cannot
 * be retrieved, decoded, or parsed at boot. Caught by
 * {@code BundleLoadListener}, which maps it to a readiness-probe-down state
 * plus a structured ERROR log entry.
 *
 * <p>This is a <em>payload-style</em> exception per ADR-001: the structured
 * payload here is the optional {@link #path()} (populated for cache I/O
 * failures), and the underlying {@link Throwable} cause discriminates the
 * remaining failure modes via the named static factories.
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

`parseFailed(Throwable)` accepts both `BundleParseException` (YAML/schema failures) and `IllegalStateException` (the parser's schema/enum-sync drift case).

### `application.yaml`

Add to the top-level (no profile activation, so it applies to every profile unless overridden):

```yaml
integration-registry:
  vendor-mapping:
    cache-dir: ${java.io.tmpdir}/integration-registry/vendor-mapping
    # bundle-version, s3-bucket, s3-key-prefix come from environment or per-profile override
```

Per-profile overrides (e.g., staging, production) will set `bundle-version`, `s3-bucket`, and `s3-key-prefix` in deployment manifests / env vars. The `cache-dir` can be overridden too if a non-tmpdir location is required.

### `pom.xml` — new dependencies

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

Versions to confirm against latest stable at implementation time (within the same major series for transitive compatibility).

### ArchUnit — new rule

In `LayerDependencyRules.java`:

```java
static final ArchRule mappingCoreLayer_shouldNotDependOnFrameworks =
        noClasses().that().resideInAPackage("..mapping..")
                .and().resideOutsideOfPackages("..mapping.loader..", "..mapping.exception..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "software.amazon.awssdk..");
```

Registered in `LayerDependencyRulesTest` alongside the existing rules.

## Data flow

### Boot path — success (S3 fetch)

```
Spring context refresh → beans created (S3Client, parser, holder, loader, listener)
                       → framework auto-publishes ACCEPTING_TRAFFIC at ApplicationStartedEvent
BundleLoadListener.onApplicationEvent(ApplicationStartedEvent):
  [1] publish REFUSING_TRAFFIC                              ← override default
  [2] loader.load():
        Files.exists(cacheFile)? no
        s3Client.getObject(GetObjectRequest{bucket, bundleObjectKey}) → bytes
        Files.createDirectories(cacheDir)
        Files.write(tempFile, bytes); Files.move(tempFile, cacheFile, ATOMIC_MOVE)
        gunzip + untar bytes → tar entry "vendor-mapping.yaml"
        parser.parse(tarEntryStream) → VendorMappingSnapshot
  [3] holder.set(new LoggingVendorMappingSnapshot(snapshot))
  [4] log INFO "Vendor mapping bundle loaded; mapping_version=v1.0.0"
  [5] publish ACCEPTING_TRAFFIC

GET /actuator/health/readiness → UP
```

### Boot path — success (cache hit)

```
[1] publish REFUSING_TRAFFIC
[2] loader.load():
      Files.exists(cacheFile)? yes
      Files.readAllBytes(cacheFile) → bytes
      gunzip + untar → parse → VendorMappingSnapshot
      (S3Client.getObject is NEVER called)
[3..5] success path continues
```

### Boot path — corrupt cache → S3 fallthrough

```
[1] publish REFUSING_TRAFFIC
[2a] loader.load():
       Files.exists(cacheFile)? yes
       Files.readAllBytes(cacheFile) → bytes (truncated)
       gunzip → IOException → BundleLoadException.archiveExtractFailed wrapped
       loader catches, log.warn("disk cache corrupted ... falling through to S3"),
       Files.deleteIfExists(cacheFile), proceed
[2b]   s3Client.getObject(...) → fresh bytes → write atomic cache → parse → snapshot
[3..5] success path continues
```

### Boot path — S3 failure

```
[1] publish REFUSING_TRAFFIC
[2] loader.load():
      no cache file
      s3Client.getObject(...) throws SdkClientException
      → BundleLoadException.s3FetchFailed(...)
[catch] log.error structured fields:
        failure_class=BundleLoadException, bundle_version=v1.0.0,
        cause="...", message="..."
[return without setting holder, without publishing ACCEPTING_TRAFFIC]

GET /actuator/health/readiness → DOWN forever (replica out of rotation)
GET /actuator/health/liveness  → UP (replica alive, not killed)
```

### Boot path — invalid bundle from S3

```
[1] publish REFUSING_TRAFFIC
[2] loader.load():
      s3Client.getObject(...) → bytes (valid tgz, malformed YAML or schema-violating)
      gunzip + untar OK → parser.parse(...) throws BundleParseException
      → BundleLoadException.parseFailed(bundleParseException)
[catch] log.error with the BundleParseException's message AND its validationMessages()
        (since the cause exposes them via the payload-style accessor)
[return without setting holder]

readiness DOWN forever
```

### Lookup path — post-load

```
aggregator (T08, future) → @Autowired VendorMappingSnapshot mapping
                       → mapping.lookup(product, type, value)
                          (mapping is the holder bean)
                          → holder.lookup(...): ref.get() != null
                            → loggingDecorator.lookup(...)
                              → underlyingMapBacked.lookup(...) → VendorResolution
                              → if equals(unknown): log.warn("Unknown vendor mapping triplet...")
                              → return resolution
```

### Lookup path — pre-load (defensive; should not happen in production)

```
caller → holder.lookup(...) → ref.get() == null → throw IllegalStateException(
            "snapshot not yet loaded — readiness should have prevented this call")
```

The `IllegalStateException` is a programming-error indicator. In production, the readiness gate prevents traffic from reaching aggregator code pre-load. If this exception ever fires in a real deployment, the fix is upstream (probe configuration, framework integration), not downstream (silent fallback).

## Error handling

### Failure → exception → outcome table

| Failure                                     | Wrapped as                              | Outcome                                  |
|---------------------------------------------|-----------------------------------------|------------------------------------------|
| Cache file gunzip / tar / parse fails       | caught inside loader (no exception)     | WARN log; cache deleted; S3 fallthrough  |
| Cache read I/O error (permissions, read)    | `cacheReadFailed(path, cause)`          | `BundleLoadException` → readiness DOWN   |
| Cache write I/O error (full disk, permissions) | `cacheWriteFailed(path, cause)`      | `BundleLoadException` → readiness DOWN   |
| S3 fetch fails (network, 4xx, 5xx)          | `s3FetchFailed(SdkException)`           | `BundleLoadException` → readiness DOWN   |
| Tarball empty                               | `archiveExtractFailed(IllegalStateException)` | `BundleLoadException` → readiness DOWN |
| Tarball entry name ≠ `vendor-mapping.yaml`  | `archiveExtractFailed(IllegalStateException)` | `BundleLoadException` → readiness DOWN |
| Tarball IO error during streaming           | `archiveExtractFailed(IOException)`     | `BundleLoadException` → readiness DOWN   |
| YAML/schema invalid (`BundleParseException`) | `parseFailed(BundleParseException)`    | `BundleLoadException` → readiness DOWN   |
| Schema/enum-sync drift (`IllegalStateException` from parser) | `parseFailed(IllegalStateException)` | `BundleLoadException` → readiness DOWN |

### Logging

| Site                                | Level | Fields                                                                                  |
|-------------------------------------|-------|-----------------------------------------------------------------------------------------|
| Listener — load success             | INFO  | `mapping_version`, `bundle_version`                                                     |
| Listener — load failure             | ERROR | `failure_class`, `bundle_version`, `s3_bucket`, `s3_key`, message, cause stacktrace; if parseFailed of BundleParseException, the validation messages are accessible via the cause's `validationMessages()` accessor |
| Loader — corrupt cache fallthrough  | WARN  | `path`, message of the underlying failure                                               |
| Loader — failed to delete corrupt cache | WARN | `path`, IO cause                                                                       |
| Decorator — unknown lookup          | WARN  | `product`, `source_type`, `source_value`, `mapping_version`                             |

Standard SLF4J class loggers; no MDC, no structured-log JSON encoder beyond Logback's defaults. Plan 03 does not mandate a structured-logging library — that's a downstream decision for observability tooling.

### No silent failures

Every catch path either re-throws (wrapped) or logs at WARN/ERROR. No path swallows an exception silently.

## Testing

Test layering targets `~70%` unit / `~30%` Spring-context integration per `TESTING.md`. All tests follow the existing `methodName_shouldDoX_whenY()` naming convention with explicit Arrange-Act-Assert comments. No `RestTestClient` dep added (TESTING.md notes it lands in T07); readiness assertions go through the `BundleLoadHealthIndicator` bean directly (the readiness gate's load-bearing component), not through `ApplicationAvailability` (which Spring Boot 4 unconditionally flips to ACCEPTING after `ApplicationReadyEvent` listeners run).

### Unit tests under `src/test/java/com/rapid7/integrationregistry/mapping/loader/`

| File | Tests |
|------|-------|
| `VendorMappingPropertiesTest` | `bundleObjectKey_shouldComposeKey_whenAllFieldsSet`; `cacheFilePath_shouldComposePath_whenAllFieldsSet`; `properties_shouldDefaultCacheDir_whenNullPassed`; null-guard tests for required fields. |
| `VendorMappingSnapshotHolderTest` | `lookup_shouldThrowIllegalState_whenNotYetSet`; `mappingVersion_shouldThrowIllegalState_whenNotYetSet`; `lookup_shouldDelegate_whenSet`; `mappingVersion_shouldDelegate_whenSet`; `set_shouldThrowIllegalState_whenAlreadySet`; `set_shouldThrowNpe_whenNullSnapshot`. |
| `LoggingVendorMappingSnapshotTest` | `lookup_shouldLogWarn_whenUnderlyingReturnsUnknown` (Logback `ListAppender` captures the WARN; assert message contains `product`, `source_type`, `source_value`, `mapping_version`); `lookup_shouldNotLog_whenUnderlyingReturnsKnown`; `lookup_shouldDelegate_whenKnownTriplet`; `mappingVersion_shouldDelegate_always`. |
| `S3VendorMappingBundleLoaderTest` | `load_shouldReadFromDisk_whenCacheExists` (uses `@TempDir` + `BundleArchiveBuilder`; verifies `S3Client.getObject(...)` is NOT called); `load_shouldFetchS3_whenCacheMissing`; `load_shouldFallthroughToS3_whenCacheCorrupted` (writes garbage bytes to TempDir; expects S3 call + cache replaced with valid tgz); `load_shouldThrowS3FetchFailed_whenS3Throws`; `load_shouldThrowArchiveExtractFailed_whenTarballEmpty`; `load_shouldThrowArchiveExtractFailed_whenEntryNameWrong`; `load_shouldThrowParseFailed_whenYamlInvalid`; `load_shouldThrowCacheReadFailed_whenIoErrorOnRead`. |
| `BundleLoadListenerTest` | `onApplicationEvent_shouldPublishRefusing_thenAccepting_whenLoadSucceeds` (Mockito `InOrder` verifies event order); `onApplicationEvent_shouldPublishRefusing_only_whenLoadFails`; `onApplicationEvent_shouldPopulateHolder_whenLoadSucceeds`; `onApplicationEvent_shouldNotPopulateHolder_whenLoadFails`; `onApplicationEvent_shouldLogStructuredError_whenLoadFails` (assert ERROR log entry contains `failure_class`, `bundle_version`, `s3_bucket`, `s3_key`). Collaborators (`S3VendorMappingBundleLoader`, `VendorMappingSnapshotHolder`, `ApplicationEventPublisher`) are Mockito mocks; `VendorMappingProperties` is a real record with test-fixture values. |

### Exception test under `src/test/java/com/rapid7/integrationregistry/mapping/exception/`

| File | Tests |
|------|-------|
| `BundleLoadExceptionTest` | One test per static factory verifying message + cause + payload; `independentlyCatchable_shouldNotShareParentWithOtherExceptions_whenThrown` (mirrors `AdapterExceptionsTest` and `BundleParseExceptionTest`); ADR-001 invariants checked via reflection (`!Modifier.isFinal`, `serialVersionUID` field present). |

### Spring-context integration under `src/test/java/com/rapid7/integrationregistry/mapping/loader/`

| File | Tests |
|------|-------|
| `VendorMappingBootIntegrationTest` | `@SpringBootTest` + `@MockitoBean S3Client` + `@TempDir` cache directory + `@DynamicPropertySource` to register `integration-registry.vendor-mapping.bundle-version=v1.0.0`, `s3-bucket=test-bucket`, `s3-key-prefix=test/mappings/`, `cache-dir=<tempDirPath>` before context starts. Three `@Nested` classes: `WhenS3ReturnsValidBundle` — stub `getObject(...)` to return MVP-seed-as-tgz bytes built by `BundleArchiveBuilder.tgzOf(mvpSeedYamlBytes, "vendor-mapping.yaml")`; assert `availability.getReadinessState() == ACCEPTING_TRAFFIC` and that the `VendorMappingSnapshot` bean resolves all 4 MVP triplets. `WhenS3Throws` — stub to throw `SdkClientException`; assert `getReadinessState() == REFUSING_TRAFFIC`; assert `getLivenessState() == CORRECT`. `WhenS3ReturnsInvalidBundle` — stub to return malformed YAML wrapped in tgz; assert `getReadinessState() == REFUSING_TRAFFIC` and ERROR log captured with validation messages. |
| `VendorMappingDiskCacheIntegrationTest` | `@SpringBootTest` + `@MockitoBean S3Client` + `@TempDir` cache directory + `@DynamicPropertySource` registering the cache-dir to the temp path. Pre-seeds `<tempDir>/vendor-mapping-v1.0.0.tgz` with valid MVP-seed-tgz bytes BEFORE context starts (in a `@BeforeAll` static helper that runs before Spring's context bootstrap). After context refresh and `ApplicationStartedEvent`, asserts: (a) `availability.getReadinessState() == ACCEPTING_TRAFFIC`, (b) `verify(s3Client, never()).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class))`. |

The MVP-seed YAML bytes for these tests come from the existing `src/main/resources/vendor-mapping/bundle/mvp-seed.yaml` resource (read via `getResourceAsStream`). Each integration test rebuilds the `.tgz` bytes in-memory via `BundleArchiveBuilder` rather than committing a binary fixture.

### Test support under `src/test/java/com/rapid7/integrationregistry/testsupport/`

| File | Purpose |
|------|---------|
| `BundleArchiveBuilder` | Static helper. `byte[] BundleArchiveBuilder.tgzOf(byte[] yamlContent, String entryName)` returns a single-entry gzipped tarball. Uses `commons-compress` (`GzipCompressorOutputStream` + `TarArchiveOutputStream`). Used by both unit tests of the loader and the two integration tests. |

### Build verification

`./mvnw verify` runs the full chain: unit tests, integration tests, ArchUnit (including the new `mappingCoreLayer_shouldNotDependOnFrameworks` rule), and PMD. The new ArchUnit rule prevents accidental Spring/AWS imports in the core data layer at build time.

## Out of scope

These are explicit non-goals so the scope cut is unambiguous for the implementer:

- **Hot-reload from S3 / polling refresh** — RFC defers as non-breaking forward extension.
- **Cross-replica snapshot consistency** — each replica is self-contained; consistency is achieved at deploy time via the regionally-identical pinned version.
- **The S3 publish pipeline** that produces the `.tgz` artifacts (T11).
- **The deploy-manifest pin convention** documentation (T11).
- **Aggregator / controller wiring** that consumes the snapshot (T08, T09).
- **Surfacing across read routes** (the synthetic-vendor row, etc.) — T08 / T09 own response shapes.
- **Bundle JSON Schema CI rules beyond loader self-validation** — T11 owns CI's full validation suite (uniqueness, immutability, deprecation safety, PR template).
- **`RestTestClient` for HTTP-layer readiness assertions** — TESTING.md notes this lands in T07; this plan asserts via `ApplicationAvailability` directly.

## References

- Work plan: [`engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/work-plans/03-s3-loader-readiness-gate.md`](../../../../../engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/work-plans/03-s3-loader-readiness-gate.md)
- Track scope: [`engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/scope.md`](../../../../../engagements/unified-integrations-view/project/tracks/04-vendor-mapping-bundle-and-snapshot/scope.md)
- RFC-001 §Vendor mapping → Bundle lifecycle and §Operational notes — fetch-on-boot, readiness gate, disk cache as same-version-restart, immutability, unknown-triplet semantics.
- Plan 02 design: [`docs/superpowers/specs/2026-05-27-02-bundle-parser-snapshot-seed-design.md`](2026-05-27-02-bundle-parser-snapshot-seed-design.md) — parser + snapshot + MVP seed surface this plan consumes.
- ADR-001: Exception design conventions — marker-style vs payload-style, `mapping/exception/` sub-package for >1 exception, "not final" + `serialVersionUID` shared invariants.
- TESTING.md — test layering, naming, location.
- Existing `LayerDependencyRules` — confirms the loader sub-package is within `..mapping..` and the existing "only aggregator may depend on mapping" rule still holds.
