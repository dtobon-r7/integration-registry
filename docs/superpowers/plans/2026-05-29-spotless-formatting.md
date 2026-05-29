# Spotless Formatting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce consistent Java formatting via Spotless Maven Plugin with Google Java Format, failing `./mvnw verify` on violations.

**Architecture:** Add `spotless-maven-plugin 3.5.1` to `pom.xml` with `spotless:check` bound to the `verify` phase. Apply Google Java Format 1.28.0 to all existing source files in a one-time migration commit. No hooks, no CI pipeline changes — Jenkins already runs `./mvnw verify`.

**Tech Stack:** Spotless Maven Plugin 3.5.1, Google Java Format 1.28.0, Java 25, Maven Wrapper

---

### Task 1: Add Spotless to pom.xml

**Files:**
- Modify: `pom.xml` (inside `<build><plugins>` block, after the `maven-pmd-plugin` entry)

- [ ] **Step 1: Add the plugin**

  Open `pom.xml`. After the closing `</plugin>` tag of the `maven-pmd-plugin` block (around line 132), insert:

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

- [ ] **Step 2: Verify spotless:check detects unformatted files**

  Run:
  ```bash
  ./mvnw spotless:check
  ```

  Expected: **FAILURE** — Spotless reports files that do not match Google Java Format. This confirms the plugin is wired up and the existing code needs formatting.

  If it passes (unlikely), the existing code already conforms and you can skip Task 2.

---

### Task 2: Apply formatting to all existing source files (migration)

- [ ] **Step 1: Format all Java files**

  Run:
  ```bash
  ./mvnw spotless:apply
  ```

  Expected: `BUILD SUCCESS`. Spotless rewrites every `.java` file under `src/` to conform to Google Java Format. This is a large diff — that is expected and normal.

- [ ] **Step 2: Confirm spotless:check now passes**

  Run:
  ```bash
  ./mvnw spotless:check
  ```

  Expected: `BUILD SUCCESS` with output like:
  ```
  [INFO] All 1 files are up-to-date with Spotless!
  ```
  (file count will reflect actual source count)

- [ ] **Step 3: Run full verify to confirm nothing is broken**

  Run:
  ```bash
  ./mvnw verify
  ```

  Expected: `BUILD SUCCESS`. All gates pass — Spotless check, PMD, ArchUnit, and tests.

  If any test or PMD rule fails, it is a pre-existing issue unrelated to formatting. Fix it before committing.

- [ ] **Step 4: Commit**

  ```bash
  git add pom.xml src/
  git commit -m "chore: add Spotless with Google Java Format, apply to all sources

  - spotless-maven-plugin 3.5.1, googleJavaFormat 1.28.0 (Java 25 compatible)
  - spotless:check bound to verify phase; enforced by Jenkins on every PR
  - one-time reformat of all existing .java files

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  ```
