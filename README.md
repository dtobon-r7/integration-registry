# Integration Registry

Read-only aggregation service for third-party integration metadata across Rapid7 products. Part of the Unified Integrations View initiative (Phase 1).

Customers running multiple Command Platform products manage integrations independently in each product. The Integration Registry centralizes that metadata — fetching it live from product APIs, grouping it by vendor and vendor service, and surfacing a unified health view to the Command Platform UI.

## Architecture

The Registry sits behind Kong and fans out to product APIs on demand. It is stateless — no database, no admin API, no shared state across replicas.

```
Kong → VendorController → VendorService → FanOutCoordinator → Adapters → Product APIs
                                       ↘ VendorAggregator ← VendorMappingSnapshot (boot-time bundle)
```

**Key components:**

| Component | Responsibility |
|---|---|
| `VendorController` | HTTP boundary — parse, validate, serialize |
| `VendorService` | Orchestration — delegate, unwrap, assemble response |
| `FanOutCoordinator` | Parallel adapter dispatch, per-adapter timeouts, cache read/write |
| `VendorAggregator` | Resolve source identifiers → vendor service → vendor; worst-state-wins health rollup |
| `InsightIDRAdapter` | Fetches event sources from InsightIDR (2-call pattern: list + per-source detail) |
| `InsightConnectAdapter` | Fetches connections from InsightConnect (single call) |
| `VendorMappingSnapshot` | Immutable bundle loaded at boot — maps product-side identifiers to canonical vendors |

**Cache:** Short-TTL Valkey cache keyed by `(org_id, product_name)`. On adapter failure, the stale tier is served with an explicit flag rather than dropping the data. Partial unavailability is always a `200` — affected products surface in `unavailable_products[]`.

## Data Model

Four-layer hierarchy:

```
Vendor  →  Vendor Service  →  Data Source  →  Integration
```

- **Vendor** — third-party company (Microsoft, Atlassian). Grid filter facet.
- **Vendor Service** — a specific product offering (Microsoft Defender, Jira). Primary grid row.
- **Data Source** — one per `(product_name, source_type, source_value)`. Groups instances in the expanded row.
- **Integration** — a single configured instance in the customer org.

Health rolls up via worst-state-wins (`error > missing_data > warning > disabled > healthy`) at the data-source, vendor-service, and vendor levels.

## API

Base path: `GET /integration-registry/v1/`

All routes require `X-IPIMS-ORG-ID` and `X-IPIMS-USER-ID`.

| Route | Purpose |
|---|---|
| `GET /vendor-services` | Primary grid feed — one row per vendor service |
| `GET /vendor-services/{id}` | Expanded row — data sources and integration instances |
| `GET /vendors` | Lightweight vendor list for the filter dropdown |
| `GET /vendors/{id}` | Vendor-scoped view — vendor header plus its vendor services |

The OpenAPI spec (`openapi.json`) is the machine-readable contract for wire format, request/response schemas, and enums.

## Tech Stack

- Java 25
- Spring Boot 4.0.6
- Valkey (Redis-compatible) cache via Testcontainers in tests
- Maven wrapper (`./mvnw`)

## Build & Run

```bash
# Full build + tests + ArchUnit + PMD (Docker required for cache tests)
./mvnw verify

# Build JAR only
./mvnw package -DskipTests

# Run locally
./mvnw spring-boot:run

# App port:    8080
# Health:      GET http://localhost:8080/actuator/health
```

> **Docker required for `verify`:** the Valkey cache tests use Testcontainers against `valkey/valkey:8-alpine`. Everything else is Docker-free.

## Quality Gates

| Gate | Tool | Notes |
|---|---|---|
| Architecture | ArchUnit | Enforces layer boundaries (controller → service → coordinator → adapter) |
| Code quality | PMD 7.17.0 | Ruleset in `pmd-ruleset.xml` |
| Formatting | Spotless / Google Java Format | Run `./mvnw spotless:apply` to auto-fix |

## Testing

See [TESTING.md](TESTING.md) for the full testing guide. Summary:

- ~70% unit tests (plain JUnit 5, no Spring context)
- ~20% controller slice (`@WebMvcTest` + `RestTestClient`)
- ~10% adapter contract tests (`MockRestServiceServer` + fixture JSON)
- Cache behavior tested with Testcontainers (Docker required)

## Profiles

| Profile | Usage |
|---|---|
| `local` | Local development |
| `staging` | Staging environment |
| `production` | Production environment |

Activate via `SPRING_PROFILES_ACTIVE` or `-Dspring-boot.run.profiles=<profile>`.

## MVP Scope

Phase 1 covers two products and two vendor services:

| Adapter | Product | Integration Type |
|---|---|---|
| `InsightIDRAdapter` | InsightIDR | `SIEM Event Source` |
| `InsightConnectAdapter` | InsightConnect | `Automation Plugin` |

Fast-follow adapters (InsightVM, InsightCloudSec, Surface Command, InsightAppSec) conform to the same `IntegrationAdapter` interface.
