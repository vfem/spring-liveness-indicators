# Spring Liveness Indicators - Wiki & Codebase Navigation

Welcome to the **Spring Liveness Indicators** documentation wiki. This wiki provides a modular, linked reference designed for fast agent navigation and developer onboarding without needing to read full Java source files.

---

## 📌 Project Summary

- **Type**: Spring Boot Auto-Configuration Starter / Library
- **Primary Goal**: Detect stalled or hung Kafka consumers by comparing committed offset movement against end-of-partition offsets, and trigger Kubernetes/Spring Boot Actuator liveness state failure (`LivenessState.BROKEN`).
- **Target Framework**: Spring Boot 2.4.x+, Spring Kafka, Spring Boot Actuator, Java 17+
- **Artifact**: `io.github.vfem.livenesscheck.spring:spring-liveness-indicators`

---

## 🗺️ Documentation Map

| Document | Description | Key Linked Files |
| :--- | :--- | :--- |
| **[Architecture & Flow](file:///c:/workdir/spring-liveness-indicators/docs/architecture.md)** | System overview, sequence flow, and liveness transition model | [`CommittedOffsetMovementCheck`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java) |
| **[Auto-Configuration](file:///c:/workdir/spring-liveness-indicators/docs/auto-configuration.md)** | Activation conditions, `spring.factories` registration, and bean creation | [`LivenessCheckersAutoConfiguration`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckersAutoConfiguration.java), [`LivenessCheckerCondition`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckerCondition.java), [`spring.factories`](file:///c:/workdir/spring-liveness-indicators/src/main/resources/META-INF/spring.factories) |
| **[Core Components](file:///c:/workdir/spring-liveness-indicators/docs/core-components.md)** | Deep dive into consumer extraction via reflection, offset comparison algorithm, and shutdown | [`CommittedOffsetMovementCheck`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java) |
| **[Configuration Reference](file:///c:/workdir/spring-liveness-indicators/docs/configuration-reference.md)** | Complete property table, default values, and sample YAML snippets | [`application-sample.yml`](file:///c:/workdir/spring-liveness-indicators/src/main/resources/sample/application-sample.yml), [`pom.xml`](file:///c:/workdir/spring-liveness-indicators/pom.xml) |
| **[Testing Guide](file:///c:/workdir/spring-liveness-indicators/docs/testing-guide.md)** | Embedded Kafka integration tests, test listener setups, and test cases | [`CommittedOffsetMovementCheckIT`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheckIT.java), [`BaseConfig`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/BaseConfig.java), [`TestListenerClass`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/TestListenerClass.java) |
| **[Wiki Usage & Maintenance Guide](file:///c:/workdir/spring-liveness-indicators/docs/wiki-guide.md)** | Instructions for AI agents and maintainers on navigating and updating the wiki | [`docs/`](file:///c:/workdir/spring-liveness-indicators/docs) |
| **[Agent Guidelines (AGENTS.md)](file:///c:/workdir/spring-liveness-indicators/AGENTS.md)** | Top-level repository guidance, invariants, and build commands for AI coding agents | [`AGENTS.md`](file:///c:/workdir/spring-liveness-indicators/AGENTS.md) |

---

## ⚡ Quick Symbol Directory

- **Auto Configuration**:
  - Class: [`LivenessCheckersAutoConfiguration`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckersAutoConfiguration.java)
  - Condition: [`LivenessCheckerCondition`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckerCondition.java)
- **Monitoring Service**:
  - Class: [`CommittedOffsetMovementCheck`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java)
- **Build / Packaging**:
  - Build descriptor: [`pom.xml`](file:///c:/workdir/spring-liveness-indicators/pom.xml)
  - Auto-configuration registration: [`spring.factories`](file:///c:/workdir/spring-liveness-indicators/src/main/resources/META-INF/spring.factories)
- **Tests**:
  - Integration Test: [`CommittedOffsetMovementCheckIT`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheckIT.java)
  - Test Spring Boot Config: [`BaseConfig`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/BaseConfig.java)
  - Test Listener: [`TestListenerClass`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/TestListenerClass.java)
