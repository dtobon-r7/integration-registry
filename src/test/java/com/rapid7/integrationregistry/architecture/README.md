# Architecture Rules

ArchUnit rules that enforce RFC-001's Spring layer boundaries at compile time. Any violation fails `mvn verify`.

## How it works

- **`LayerDependencyRules.java`** — defines the rules as shared constants (single source of truth)
- **`LayerDependencyRulesTest.java`** — runs rules against production code via `@ArchTest`; this is what fails the build
- **`LayerDependencyViolationDetectionTest.java`** — proves the rules actually catch violations using deliberate fixture classes

## Adding a new rule

1. Define the rule in `LayerDependencyRules.java`:

```java
static final ArchRule myLayer_shouldNotDependOnX =
        noClasses().that().resideInAPackage("..mylayer..")
                .should().dependOnClassesThat().resideInAnyPackage("..forbidden..");
```

2. Reference it in `LayerDependencyRulesTest.java`:

```java
@ArchTest
static final ArchRule myLayer_shouldNotDependOnX =
        LayerDependencyRules.myLayer_shouldNotDependOnX;
```

3. (Optional) Add a violation fixture to prove it works:
   - Create a class in `src/test/java/.../mylayer/` that deliberately violates the rule
   - Add a test in `LayerDependencyViolationDetectionTest.java` that asserts `result.hasViolation()`

## Naming convention

Method-prefixed: `layerName_shouldNotDependOnDescription`

## Running locally

```bash
./mvnw test -Dtest="LayerDependencyRulesTest" -Dmaven.compiler.release=21
```

## Current rules (from RFC-001 §Spring layer boundaries)

| Layer | Cannot depend on |
|-------|-----------------|
| controller | coordinator, adapter, aggregator, mapping |
| service | jakarta.servlet, spring web/http |
| coordinator | controller, service, aggregator, mapping |
| aggregator | controller, service, coordinator, adapter |
| adapter | controller, service, coordinator, aggregator, mapping |
