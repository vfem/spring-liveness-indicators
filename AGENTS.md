# AGENTS.md

Instructions and guidelines for AI coding agents operating on the `spring-liveness-indicators` repository.

---

## 🎯 Repository Overview

- **Project**: Spring Liveness Indicators (`spring-liveness-indicators`)
- **Type**: Spring Boot Auto-Configuration Starter / Library
- **Purpose**: Periodically monitors Kafka consumers for committed offset progress across assigned topic partitions. If a consumer stalls while pending messages remain, it marks the application as unhealthy by publishing `AvailabilityChangeEvent(LivenessState.BROKEN)` to Spring Boot Actuator (`/actuator/health/liveness`).
- **Tech Stack**: Java 17, Spring Boot 3.4.x, Spring Kafka, Spring Boot Actuator, Apache Maven.

---

## 📚 Knowledge Base & Wiki Navigation (Zero-Read Docs)

Before reading entire source code files, consult the modular wiki in [`docs/`](file:///c:/workdir/spring-liveness-indicators/docs):

| Topic | Document |
| :--- | :--- |
| **Wiki Entrypoint & Map** | [`docs/README.md`](file:///c:/workdir/spring-liveness-indicators/docs/README.md) |
| **Architecture & Event Flow** | [`docs/architecture.md`](file:///c:/workdir/spring-liveness-indicators/docs/architecture.md) |
| **Auto-Configuration & Conditions** | [`docs/auto-configuration.md`](file:///c:/workdir/spring-liveness-indicators/docs/auto-configuration.md) |
| **Core Checking Logic & Container Resolution** | [`docs/core-components.md`](file:///c:/workdir/spring-liveness-indicators/docs/core-components.md) |
| **Properties & Configuration** | [`docs/configuration-reference.md`](file:///c:/workdir/spring-liveness-indicators/docs/configuration-reference.md) |
| **Testing Guide & Test Scenarios** | [`docs/testing-guide.md`](file:///c:/workdir/spring-liveness-indicators/docs/testing-guide.md) |
| **Wiki Maintenance Protocol** | [`docs/wiki-guide.md`](file:///c:/workdir/spring-liveness-indicators/docs/wiki-guide.md) |

---

## 🧭 Source Code Map

- **Auto-Configuration**:
  - Registration: [`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`](file:///c:/workdir/spring-liveness-indicators/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
  - AutoConfig Bean: [`LivenessCheckersAutoConfiguration.java`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckersAutoConfiguration.java)
  - Activation Condition: [`LivenessCheckerCondition.java`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckerCondition.java)
- **Core Engine**:
  - Offset Monitor: [`CommittedOffsetMovementCheck.java`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java)
- **Integration Tests**:
  - Test Suite: [`CommittedOffsetMovementCheckIT.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheckIT.java)
  - Test Configs: [`BaseConfig.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/BaseConfig.java), [`TestListenerClass.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/TestListenerClass.java)
  - Test Profile: [`src/test/resources/application.yml`](file:///c:/workdir/spring-liveness-indicators/src/test/resources/application.yml)

---

## 🛠️ Build, Test, and Verification Commands

- **Compile Project**:
  ```sh
  mvn compile
  ```
- **Compile Tests**:
  ```sh
  mvn test-compile
  ```
- **Run All Tests**:
  ```sh
  mvn test
  ```
- **Run Single Integration Test**:
  ```sh
  mvn test -Dtest=CommittedOffsetMovementCheckIT
  ```

---

## 📐 Key Invariants & Design Rules

1. **Activation Conditions**:
   - The starter must **only** activate if both Spring Kafka and Actuator are present, AND `management.endpoint.health.probes.enabled=true` AND `management.health.livenessstate.enabled=true`.
2. **Dynamic Container Resolution**:
   - `MessageListenerContainer` instances are resolved dynamically via Spring ApplicationContext (`KafkaListenerEndpointRegistry`, `MessageListenerContainer` beans, and explicit registration) and inspected via public container APIs without modifying user code.
3. **Partition State Handling**:
   - Paused partitions (`isContainerPaused()`, `isPauseRequested()`) must always be excluded from evaluation.
   - Topics that are empty (`latestOffset <= 0`) or fully consumed (`currentOffset >= latestOffset`) must **never** trigger `LivenessState.BROKEN`.
4. **Graceful Teardown**:
   - `CommittedOffsetMovementCheck.shutdown()` (@PreDestroy) must cleanly close `AdminClient`.

---

## 📝 Agent Maintenance Checklist

Whenever you modify or extend this repository, update the documentation wiki accordingly:

- [ ] If new configuration properties are added: Update [`docs/configuration-reference.md`](file:///c:/workdir/spring-liveness-indicators/docs/configuration-reference.md) and [`src/main/resources/sample/application-sample.yml`](file:///c:/workdir/spring-liveness-indicators/src/main/resources/sample/application-sample.yml).
- [ ] If offset checking or container resolution logic changes: Update [`docs/core-components.md`](file:///c:/workdir/spring-liveness-indicators/docs/core-components.md).
- [ ] If auto-configuration conditions change: Update [`docs/auto-configuration.md`](file:///c:/workdir/spring-liveness-indicators/docs/auto-configuration.md).
- [ ] If tests or test scenarios are added: Update [`docs/testing-guide.md`](file:///c:/workdir/spring-liveness-indicators/docs/testing-guide.md).
- [ ] Run `mvn test-compile` or `mvn test` to verify zero regression.
