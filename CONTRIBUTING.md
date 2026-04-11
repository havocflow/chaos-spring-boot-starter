# Contributing to HavocFlow

Thank you for taking the time to contribute! All types of contributions are welcome — bug reports, feature requests, documentation improvements, and code changes.

## Table of Contents

- [Reporting Bugs](#reporting-bugs)
- [Requesting Features](#requesting-features)
- [Submitting Pull Requests](#submitting-pull-requests)
- [Code Style](#code-style)
- [Running Tests](#running-tests)
- [Developer Certificate of Origin](#developer-certificate-of-origin)

---

## Reporting Bugs

1. Search [existing issues](https://github.com/havocflow/chaos-spring-boot-starter/issues) to avoid duplicates.
2. Open a new issue using the **Bug Report** template.
3. Include:
   - HavocFlow version
   - Spring Boot version and Java version
   - Minimal reproduction (YAML config + annotation usage)
   - Actual vs. expected behaviour

---

## Requesting Features

1. Check [existing issues](https://github.com/havocflow/chaos-spring-boot-starter/issues) and open PRs first.
2. Open a new issue using the **Feature Request** template.
3. Describe the use-case, not just the solution — this helps us understand whether the feature fits the project scope.

---

## Submitting Pull Requests

1. **Fork** the repository and create a branch from `main`:
   ```bash
   git checkout -b feat/your-feature-name
   ```

2. **Make your changes.** Keep PRs focused — one logical change per PR.

3. **Add or update tests.** All new behaviour must be covered by tests.

4. **Update documentation** if you change public API, configuration properties, or behaviour.

5. **Add a CHANGELOG entry** under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md).

6. **Run the full test suite** (see below) and ensure it passes.

7. **Open a PR** against the `main` branch. Fill out the PR template completely.

---

## Code Style

- **Java 8 compatible** — no language features above Java 8 (lambdas and streams are fine; `var`, records, sealed classes are not).
- **No Lombok** — the project intentionally avoids Lombok for broader IDE compatibility and transparency.
- Standard Java naming conventions (camelCase methods, PascalCase classes, UPPER_SNAKE_CASE constants).
- Prefer explicit over clever — this is a library used in tests and CI pipelines; readability matters.
- Keep methods short and single-purpose.
- No trailing whitespace; Unix line endings (`\n`).

---

## Running Tests

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=ChaosEngineTest

# Run tests with verbose output
mvn test -pl . --no-transfer-progress
```

The test suite uses JUnit 5 and Spring Boot Test. No external services are required.

---

## Developer Certificate of Origin

By contributing to this project you agree to the [Developer Certificate of Origin (DCO) 1.1](https://developercertificate.org/). This means you certify that you have the right to submit your contribution under the project's Apache 2.0 license.

You do not need to sign commits, but by opening a PR you implicitly agree to the DCO.

---

Thank you for helping make HavocFlow better!
