# Spotless Code Formatting — Design

**Date:** 2026-05-29  
**Status:** Approved

## Problem

The codebase has no enforced formatting standard. Inconsistent style creates noise in diffs and creates friction in code review.

## Goal

Enforce consistent Java formatting across all source files using Spotless Maven Plugin with Google Java Format, failing the build on violations — the same way PMD currently does.

## Non-Goals

- No pre-commit or pre-push git hooks.
- No GitHub Actions workflow (Jenkins handles CI).
- No formatting of XML, Markdown, or other file types.

## Design

### Plugin

Add `spotless-maven-plugin 3.5.1` to `pom.xml`. Google Java Format `1.28.0` is required for Java 25 compatibility (earlier versions break on Java 25 class files).

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>3.5.1</version>
  <configuration>
    <java>
      <googleJavaFormat>
        <version>1.28.0</version>
      </googleJavaFormat>
    </java>
  </configuration>
  <executions>
    <execution>
      <id>spotless-check</id>
      <phase>verify</phase>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```

Spotless automatically targets `src/main/java/**/*.java` and `src/test/java/**/*.java` when the `<java>` block is used without explicit `<includes>`.

### Lifecycle integration

`spotless:check` is bound to the `verify` phase. This means:

| Command | Behaviour |
|---------|-----------|
| `./mvnw verify` | Fails if any Java file is not formatted (existing gate, no change needed) |
| `./mvnw spotless:apply` | Formats all Java files in place |
| `./mvnw spotless:check` | Checks formatting without applying changes |

Jenkins runs `./mvnw verify`, so PR enforcement is automatic with no additional pipeline config.

### Developer workflow

1. Write code.
2. Before committing: `./mvnw spotless:apply` (optional, but recommended).
3. `./mvnw verify` will fail locally and in Jenkins if formatting is wrong.
4. Fix with `./mvnw spotless:apply`, then re-run.

## Style choices

Google Java Format is non-configurable by design. The formatter enforces:
- 2-space indentation
- Column limit of 100
- Google style for imports, braces, and whitespace

There is no project-level overrides — that is intentional. Consistency over preference.

## Migration

The first time `./mvnw spotless:apply` runs, it will reformat all existing source files. This produces a one-time large diff. After that, day-to-day diffs are clean.
