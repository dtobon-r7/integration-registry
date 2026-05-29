# Integration Registry

Read-only aggregation layer for third-party integration metadata across Rapid7 products. Part of the Unified Integrations View initiative (Phase 1).

## Tech Stack

- Java 25
- Spring Boot 4.0.6
- Maven (with wrapper)

## Build

Requires JDK 25 on `JAVA_HOME` (the pom targets Java 25; ArchUnit 1.4.2 and PMD 7.17.0 read class file major version 69).

```bash
./mvnw verify          # full build + tests + ArchUnit + PMD
./mvnw package         # build JAR (skip tests: -DskipTests)
./mvnw clean           # clean target/
```

## Run Locally

```bash
./mvnw spring-boot:run
# with explicit profile:
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

- App port: **8080**
- Health check: `GET http://localhost:8080/actuator/health`
- Requires: Java 25

## Profiles

| Profile | Purpose |
|---------|---------|
| `local` | Local development |
| `staging` | Staging environment |
| `production` | Production environment |

Activate via `SPRING_PROFILES_ACTIVE` env var or `-Dspring-boot.run.profiles=<profile>`.

## Package Layout

Base package: `com.rapid7.integrationregistry`

| Package | Responsibility |
|---------|---------------|
| `controller` | HTTP boundary: parse request, extract headers, validate inputs, serialize responses to JSON |
| `service` | Orchestration: delegate to coordinator, unwrap results, assemble response |
| `coordinator` | Parallel adapter dispatch, per-adapter timeouts, failure isolation, cache read/write |
| `adapter` | Product-specific integration fetchers implementing `IntegrationAdapter` |
| `aggregator` | Vendor-service grouping and worst-state-wins health rollup |
| `mapping` | Vendor mapping snapshot: resolve product source identifiers to canonical vendor and vendor service |

## Remote

```
https://github.com/dtobon-r7/integration-registry.git
```

## Coding Conventions

### Layer boundaries (RFC-001)

Each layer has a strict scope of knowledge:

- **Controller** — HTTP only. No cache, no fan-out, no business logic.
- **Service** — orchestrates but has no HTTP knowledge. Delegates to coordinator and aggregator.
- **Coordinator** — owns cache interaction and parallel adapter dispatch. Knows adapters and cache; nothing else.
- **Aggregator** — resolves raw source identifiers via vendor mapping snapshot. Knows mapping data only.
- **Adapters** — implement `IntegrationAdapter` interface. All per-product idiosyncrasies live here. Know only their product's API.
- **Mapping** — provides `VendorMappingSnapshot` (source identifier → vendor service → vendor).

### Dependency injection structure

```
VendorController → VendorService
VendorService → FanOutCoordinator + VendorAggregator
FanOutCoordinator → IntegrationAdapter[] (autowired set)
```

### Testing approach

See [TESTING.md](TESTING.md).

### Quality gates

| Gate | Tool | Fails on |
|------|------|----------|
| Architecture | ArchUnit | Layer boundary violations (RFC-001) |
| Code quality | PMD | Rule violations in `pmd-ruleset.xml` |
| Formatting | Spotless / Google Java Format | Any `.java` file not formatted to GJF style |

**PMD ruleset provenance**: Curated from scratch targeting LLM/agentic development failure modes — empty catch blocks, hallucination residue (unused code), structural bloat, placeholder leftovers. Not pulled from another platform service (none had PMD configured).

**Versions**: maven-pmd-plugin 3.28.0; PMD engine pinned to `pmd-core` + `pmd-java` 7.17.0 in the plugin's `<dependencies>`. `<targetJdk>` flows from the project-level `${java.version}` property.

**Suppressing a rule**: Annotate the specific method with `@SuppressWarnings("PMD.<RuleName>")`. Keep suppressions local and justified.
