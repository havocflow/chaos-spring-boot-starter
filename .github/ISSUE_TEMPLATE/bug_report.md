---
name: Bug Report
about: Report something that is not working as expected
title: '[BUG] '
labels: bug
assignees: ''
---

## Describe the bug

A clear and concise description of what the bug is.

## To Reproduce

Minimal configuration and code to reproduce the issue:

**`application.yml`:**
```yaml
chaos:
  enabled: true
  # your config here
```

**Annotated method:**
```java
@InjectChaos(latency = "200ms", failureRate = 0.5)
public String myMethod() { ... }
```

**Steps:**
1. 
2. 
3. 

## Expected behaviour

What you expected to happen.

## Actual behaviour

What actually happened. Include full stack traces if applicable.

## Environment

| Property | Value |
|----------|-------|
| HavocFlow version | e.g. 1.0.0 |
| Spring Boot version | e.g. 3.2.0 |
| Java version | e.g. 17 |
| Build tool | Maven / Gradle |
| OS | e.g. macOS 14, Ubuntu 22.04 |

## Additional context

Any other context, logs, or screenshots.
