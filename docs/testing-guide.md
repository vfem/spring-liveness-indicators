# Testing Guide

This document explains the test suite, test configurations, and how to verify **Spring Liveness Indicators**.

---

## 🧪 Running Tests

Tests are executed with Maven Surefire and include both fast mock-based unit tests and full embedded Kafka integration tests:

```sh
mvn test
```

Surefire is configured in [`pom.xml`](file:///c:/workdir/spring-liveness-indicators/pom.xml#L90-L103) to include unit and integration test patterns (`*Test.java`, `*IT.java`, `*Tests.java`).

---

## 🏗️ Test Architecture & Suites

| Suite | Type | File | Description |
| :--- | :--- | :--- | :--- |
| **Auto-Configuration Tests** | Unit Test | [`LivenessCheckersAutoConfigurationTest.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/LivenessCheckersAutoConfigurationTest.java) | Tests conditional activation and property configuration using Spring Boot's `ApplicationContextRunner`. |
| **Core Service Unit Tests** | Unit Test | [`CommittedOffsetMovementCheckTest.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheckTest.java) | Tests `HealthIndicator` logic, offset comparisons, edge cases, error handling, and Kafka AdminClient interactions in isolation. |
| **Container Sources Unit Tests** | Unit Test | [`KafkaContainerSourcesTest.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/KafkaContainerSourcesTest.java) | Tests discovery and resolution across multiple registries, standalone container beans, empty concurrent containers, and dynamic post-init discovery. |
| **Manual & Dynamic Container Tests** | Integration Test | [`ManualContainerTest.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/ManualContainerTest.java) | Tests detection of manually defined `ConcurrentMessageListenerContainer` and `KafkaMessageListenerContainer` `@Bean` definitions, programmatic registry endpoint registration, and explicit `registerContainer`. |
| **Integration Test Suite** | Integration Test | [`CommittedOffsetMovementCheckIT.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheckIT.java) | End-to-end testing with `@EmbeddedKafka` broker, verifying real message flows across multiple consumer groups and topics. |
| **Test Configuration** | Helper | [`BaseConfig.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/BaseConfig.java) | Configures Spring Kafka listeners (single topic, wildcard pattern, and slow simulated consumer). |
| **Manual Container Config** | Helper | [`ManualContainerConfig.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/ManualContainerConfig.java) | Configures standalone `ConcurrentMessageListenerContainer` and `KafkaMessageListenerContainer` beans. |
| **Test Class Listener** | Helper | [`TestListenerClass.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/TestListenerClass.java) | Class-level `@KafkaListener` component with `@KafkaHandler`. |

---

## 🔬 Test Scenarios

### 1. `LivenessCheckersAutoConfigurationTest` (5 Tests)
- `activatesWhenBothPropertiesEnabled`: Verifies `CommittedOffsetMovementCheck` bean is registered when `management.endpoint.health.probes.enabled=true` and `management.health.livenessstate.enabled=true`.
- `doesNotActivateWhenProbesDisabled`: Ensures bean is not registered when `management.endpoint.health.probes.enabled=false`.
- `doesNotActivateWhenLivenessStateDisabled`: Ensures bean is not registered when `management.health.livenessstate.enabled=false`.
- `doesNotActivateWhenPropertiesMissing`: Ensures auto-configuration remains inactive by default if properties are omitted.
- `customAdminTimeoutConfigured`: Verifies custom `liveness.kafka.admin-timeout-ms` property is properly bound to `CommittedOffsetMovementCheck`.

### 2. `CommittedOffsetMovementCheckTest` (15 Tests)
- `constructorValidation`: Validates null checks for `ApplicationContext` and positive value requirements for `adminTimeoutMs` and `maxStalledChecks`.
- `isConsumersEmptyAndSize`: Tests tracking collection state and size queries.
- `shutdownClosesAdminClient`: Verifies `@PreDestroy` `shutdown()` closes the underlying Kafka `AdminClient`.
- `healthReturnsUpWhenProgressing`: Verifies `Health.up()` status and `trackedContainers` health details.
- `healthReturnsDownWhenStalledExceedsThreshold`: Verifies `Health.down()` and publishing of `AvailabilityChangeEvent(LivenessState.BROKEN)` when consumer offset stalls while messages are pending across max consecutive checks.
- `checkConsumerProgressSkipsWhenNullGroupId`: Ensures unmanaged or ungrouped consumers are safely skipped.
- `checkConsumerProgressSkipsWhenNoAssignedPartitions`: Skips consumers with empty partition assignments.
- `checkConsumerProgressSkipsWhenContainerPaused`: Excludes paused partitions from offset evaluation.
- `checkConsumerProgressHandlesAdminClientListOffsetsException` / `InterruptedException`: Verifies graceful failure handling when AdminClient list offsets RPC fails or is interrupted.
- `checkConsumerProgressHandlesAdminClientListConsumerGroupOffsetsException` / `InterruptedException`: Verifies graceful failure handling when consumer group offsets RPC fails or is interrupted.
- `checkConsumerProgressSkipsWhenLatestOffsetIsZeroOrNegative`: Validates empty/unwritten topics do not trigger health warnings.
- `checkConsumerProgressSucceedsWhenConsumerIsProgressing`: Validates offset baseline updates and healthy status when consumer advances.
- `checkConsumerProgressSucceedsWhenTopicFullyConsumed`: Validates consumer that has reached topic end (`currentOffset >= latestOffset`) remains healthy.

### 3. `KafkaContainerSourcesTest` (6 Tests)
- `discoversContainersFromMultipleKafkaListenerEndpointRegistries`: Verifies discovery from multiple/custom `KafkaListenerEndpointRegistry` instances.
- `unwrapsConcurrentMessageListenerContainerFromRegistry`: Verifies child `KafkaMessageListenerContainer` unwrapping from concurrent containers.
- `handlesConcurrentContainerWithEmptyChildrenGracefully`: Verifies empty/unstarted concurrent container fallback.
- `discoversDirectMessageListenerContainerBeans`: Verifies discovery of standalone `ConcurrentMessageListenerContainer` and `KafkaMessageListenerContainer` `@Bean` definitions.
- `allowsManualContainerRegistrationViaMethod`: Verifies explicit registration via `check.registerContainer(...)`.
- `dynamicallyDiscoversContainersAddedAfterInit`: Verifies runtime discovery of new containers added after `init()`.

### 4. `ManualContainerTest` (3 Tests)
- `testManualContainersAreDetected`: Verifies both `@KafkaListener` annotations and manual `ConcurrentMessageListenerContainer` and `KafkaMessageListenerContainer` `@Bean` instances are discovered.
- `testExplicitRegisterContainer`: Verifies explicit runtime registration with `check.registerContainer(...)`.
- `testProgrammaticEndpointRegistrationInRegistry`: Verifies programmatic endpoint registration via `registry.registerListenerContainer(...)`.

### 5. `CommittedOffsetMovementCheckIT` (7 Tests)
- `initExtractsKafkaConsumers`: Ensures all consumer instances defined across `BaseConfig`, `TestListenerClass`, and `ManualContainerConfig` are extracted.
- `healthIndicatorContainsDetails`: Checks health status `UP` and `trackedContainers` metadata.
- `doesntFailWhenTopicCompletelyConsumed`: Sends messages to `classTopic`, allows consumption, and verifies `Health.up()`.
- `doesntFailWhenTopicIsEmpty`: Verifies empty broker state is healthy.
- `failsLivenessIfNoProgress`: Produces message to slow listener topic, checks baseline, waits, and verifies transition to `Health.down()` and `LivenessState.BROKEN`.
- `doesntFailForPausedConsumer`: Pauses all listener containers, produces messages, and verifies healthy state is maintained.
- `multipleTopicsConsumedSuccessfully`: Produces messages across multiple topics with method-level and pattern-matched listeners, verifying multi-topic health.

---

## 🔗 Related Documents

- [Core Components](file:///c:/workdir/spring-liveness-indicators/docs/core-components.md)
- [Architecture & Flow](file:///c:/workdir/spring-liveness-indicators/docs/architecture.md)
- [Wiki Index](file:///c:/workdir/spring-liveness-indicators/docs/README.md)
