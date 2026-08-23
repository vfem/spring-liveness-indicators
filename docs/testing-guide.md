# Testing Guide

This document explains the test suite, test configurations, and how to verify **Spring Liveness Indicators**.

---

## 🧪 Running Tests

Tests are executed with Maven Surefire and Spring Kafka Embedded Broker:

```sh
mvn test
```

Surefire is configured in [`pom.xml`](file:///c:/workdir/spring-liveness-indicators/pom.xml#L90-L103) to include unit and integration test patterns (`*Test.java`, `*IT.java`, `*Tests.java`).

---

## 🏗️ Test Architecture

The testing framework uses:
- **`spring-kafka-test`**: Provides `@EmbeddedKafka` broker on port `9092` with topics `classTopic`, `methodTopic1`, `methodTopic2`, and `slowMethodTopic`.
- **`spring-boot-starter-test`**: Integration test context orchestration.

### Test Classes Overview

| File | Type | Description |
| :--- | :--- | :--- |
| [`CommittedOffsetMovementCheckIT.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheckIT.java) | Integration Test | Main test suite verifying consumer extraction and all movement scenarios. |
| [`BaseConfig.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/BaseConfig.java) | Test Configuration | Sets up consumer factory, embedded broker connection, and method-level `@KafkaListener`s (including a simulated slow consumer). |
| [`TestListenerClass.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/TestListenerClass.java) | Test Component | Class-level `@KafkaListener` with `@KafkaHandler`. |

---

## 🔬 Test Scenarios in `CommittedOffsetMovementCheckIT`

### 1. Consumer Extraction Verification
- **Method**: [`initExtractsKafkaConsumers()`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheckIT.java#L46-L51)
- **Validation**: Ensures that all 5 consumer instances defined across `BaseConfig` and `TestListenerClass` are extracted into `consumers` set.

### 2. Fully Consumed Topic
- **Method**: [`doesntFailWhenTopicCompletelyConsumed()`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheckIT.java#L54-L79)
- **Validation**: Sends messages to `classTopic`, allows consumer to finish consumption, runs `checkConsumerProgress()`, and asserts `applicationAvailability.getLivenessState() == LivenessState.CORRECT`.

### 3. Empty Topic
- **Method**: [`doesntFailWhenTopicIsEmpty()`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheckIT.java#L81-L90)
- **Validation**: Ensures checks pass with `LivenessState.CORRECT` when no messages exist on topics.

### 4. Stalled Consumer / No Progress Detection
- **Method**: [`failsLivenessIfNoProgress()`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheckIT.java#L95-L115)
- **Validation**: Sends a message to `slowMethodTopic` (where listener sleeps for 10s). Invokes `checkConsumerProgress()` before and after a 2-second sleep. Asserts that `applicationAvailability.getLivenessState()` transitions to `LivenessState.BROKEN`.

### 5. Paused Consumers
- **Method**: [`doesntFailForPausedConsumer()`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheckIT.java#L117-L151)
- **Validation**: Explicitly pauses all listener containers (`container.pause()`), produces messages, runs checks, and verifies `LivenessState.CORRECT` is preserved.

---

## 🔗 Related Documents

- [Core Components](file:///c:/workdir/spring-liveness-indicators/docs/core-components.md)
- [Architecture & Flow](file:///c:/workdir/spring-liveness-indicators/docs/architecture.md)
- [Wiki Index](file:///c:/workdir/spring-liveness-indicators/docs/README.md)
