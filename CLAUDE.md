# Integration Registry

Read-only aggregation layer for third-party integration metadata across Rapid7 products. Part of the Unified Integrations View initiative (Phase 1).

## Tech Stack

- Java 25
- Spring Boot 4.0.6
- Maven (with wrapper)

## Build

```bash
./mvnw verify          # full build + tests
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

- Controller: `@WebMvcTest`
- Service / Coordinator / Aggregator: plain JUnit with mocked collaborators
- Adapters: contract tests against fixture data
