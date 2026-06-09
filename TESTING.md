# Testing — Integration Registry

> The Registry has no database. RFC-001 v2 makes vendor mapping a YAML
> bundle fetched from S3 once at boot — that dependency is mocked at the
> bean boundary (no JPA, no @DataJpaTest, no LocalStack).
>
> **Exception (ADR-006): the Valkey cache (T07) is tested with
> Testcontainers** against `valkey/valkey:8-alpine`. The cache's
> correctness lives in behaviors only a real server exercises — TTL
> expiry, serialization round-trip, keyspace independence — so those
> tests require a running Docker daemon. Everything outside the cache
> stays Docker-free.
>
> Consequence: `mvn verify` now requires Docker for the cache tests.
> Tests still run under `mvn test` / `mvn verify` directly — there is
> no *IT.java suffix or maven-failsafe split; the Testcontainers-backed
> cache tests run under Surefire like the rest.

## Testing pyramid

This service targets:

- ~70% unit tests
- ~20% controller slice + Spring-context integration
- ~10% adapter contract tests

No JaCoCo coverage gate is enforced. The 80% line and 60% branch
targets from the platform Java test standards are guidelines for
authors, not build gates.

## Which test kind do I write?

| If you're testing... | Write a... |
|---|---|
| Pure logic with no Spring context | unit test |
| HTTP edge of a controller (status codes, validation, JSON shape) | controller slice |
| End-to-end orchestration with adapters mocked | Spring-context integration |
| How an adapter parses upstream JSON | adapter contract test |
| Cache behavior against real Valkey (TTL expiry, serialization round-trip, tier independence) | Testcontainers integration test (ADR-006; Docker required) |

## Naming and structure

All tests use method-prefixed naming: `methodName_shouldDoX_whenY()`.
All tests follow Arrange-Act-Assert with explicit comments. Unit and
controller tests assert one logical behavior per test; Spring-context
integration tests may assert a full workflow.

## Unit
- When to use: pure logic with no Spring context — e.g. VendorAggregator
  rollup precedence, data_source_id minting, status mapping helpers.
- Plain JUnit 5 with @ExtendWith(MockitoExtension.class) when mocks needed.
- Should run in <100ms.
- Don'ts: spinning up a Spring context, touching disk or network,
  testing framework code instead of business logic.

## Controller slice
- @WebMvcTest + @AutoConfigureRestTestClient (Spring Boot 4 idiomatic).
- RestTestClient replaces MockMvc / TestRestTemplate / WebTestClient for
  inbound endpoint testing in Spring Boot 4. It does NOT stub outbound
  calls.
- Requires `org.springframework.boot:spring-boot-starter-webmvc-test`
  (test scope) — not yet in `pom.xml`; will be added in T07 when
  `VendorController` lands. Without it, the `@AutoConfigureRestTestClient`
  annotation does not resolve.
- Validation annotations (@Valid, @NotNull, etc.) MUST be tested here —
  they only activate inside a Spring context.
- Snippet (when VendorController lands in T07):
  ```java
  @WebMvcTest(VendorController.class)
  @AutoConfigureRestTestClient
  class VendorControllerTest {
      @MockitoBean VendorService service;
      @Autowired RestTestClient client;
      // ...
  }
  ```
- Don'ts: testing complex business logic; not mocking the service layer;
  validation tests landing in Spring-context integration instead of here.

## Spring-context integration
- @SpringBootTest with adapters mocked via @MockitoBean.
- Wires VendorService end-to-end (controller → service → coordinator →
  aggregator), with adapter beans replaced by mocks.
- Don'ts: hitting real upstreams; testing adapter normalization here
  (that's an adapter contract test); testing validation here (that's a
  controller slice).

## Adapter contract test
- RestClient + MockRestServiceServer + FixtureLoader.
- Canonical example: SampleContractTest.
- Fixture layout: src/test/resources/fixtures/<adapter>/<scenario>.json.
- Always end with `server.verify()` — without it, an unfulfilled stub
  expectation passes silently.
- Don'ts: hitting a real upstream; committing fixtures > 5KB without
  trimming irrelevant fields; brittle assertions on response field
  ordering.

## Common anti-patterns to avoid

- **Ice cream cone**: more integration tests than unit tests. If you can
  unit-test it, do.
- **Testing implementation details**: prefer asserting observable
  behavior over verifying internal mock interactions
  (`assertThat(result).isHealthy()` over `verify(repo).save(any())`).
- **Brittle assertions**: don't assert on ordered indices when order
  isn't guaranteed (`result.get(0).name()` is brittle;
  `assertThat(result).extracting("name").contains("Alice")` is robust).
- **Test interdependence**: every test sets up its own state in
  @BeforeEach or in-test; no @Order chains.
- **Over-mocking simple collaborators**: don't mock Instant.now() or
  UUID.randomUUID() — pin them with constants instead.

## Where each kind lives in the source tree

- `src/test/java/com/rapid7/integrationregistry/<layer>/...` — tests
  for code in the matching main package
- `src/test/java/com/rapid7/integrationregistry/integration/` —
  full-context read-path integration suites that span every layer
  (controller → service → coordinator → aggregator → cache) and so
  belong to no single layer package (e.g. ReadPathIntegrationTest)
- `src/test/java/com/rapid7/integrationregistry/testsupport/` — shared
  test utilities (e.g. FixtureLoader)
- `src/test/java/com/rapid7/integrationregistry/testsupport/examples/` —
  canonical example tests demonstrating the patterns above (e.g.
  SampleContractTest)
- `src/test/resources/fixtures/<adapter>/<scenario>.json` — pinned
  upstream JSON fixtures
- `src/test/resources/application-test.yaml` — `test` profile config
  (empty by default; populate when test-only config diverges)

## Quality gates

- ArchUnit: layer rules apply to main code only (test source set
  excluded via ImportOption.DoNotIncludeTests).
- PMD: applies to both main and test sources.
- No JaCoCo coverage gate today.
