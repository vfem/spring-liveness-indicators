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
| **Integration Test Suite** | Integration Test | [`CommittedOffsetMovementCheckIT.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheckIT.java) | End-to-end testing with `@EmbeddedKafka` broker, verifying real message flows across multiple consumer groups and topics. |
| **Test Configuration** | Helper | [`BaseConfig.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/BaseConfig.java) | Configures Spring Kafka listeners (single topic, wildcard pattern, and slow simulated consumer). |
| **Test Class Listener** | Helper | [`TestListenerClass.java`](file:///c:/workdir/spring-liveness-indicators/src/test/java/io/github/vfem/livenesscheck/spring/kafka/config/TestListenerClass.java) | Class-level `@KafkaListener` component with `@KafkaHandler`. |

---

## 🔬 Test Scenarios

### 1. `LivenessCheckersAutoConfigurationTest` (5 Tests)
- `activatesWhenBothPropertiesEnabled`: Verifies `CommittedOffsetMovementCheck` bean is registered when `management.endpoint.health.probes.enabled=true` and `management.health.livenessstate.enabled=true`.
- `doesNotActivateWhenProbesDisabled`: Ensures bean is not registered when `management.endpoint.health.probes.enabled=false`.
- `doesNotActivateWhenLivenessStateDisabled`: Ensures bean is not registered when `management.health.livenessstate.enabled=false`.
- `doesNotActivateWhenPropertiesMissing`: Ensures auto-configuration remains inactive by default if properties are omitted.
- `customAdminTimeoutConfigured`: Verifies custom `liveness.kafka.admin-timeout-ms` property is properly bound to `CommittedOffsetMovementCheck`.

### 2. `CommittedOffsetMovementCheckTest` (16 Tests)
- `constructorValidation`: Validates null checks for `ApplicationContext` and positive value requirement for `adminTimeoutMs`.
- `isConsumersEmptyAndSize`: Tests tracking collection state and size queries.
- `shutdownClosesAdminClient`: Verifies `@PreDestroy` `shutdown()` closes the underlying Kafka `AdminClient`.
- `healthReturnsUpWhenProgressing`: Verifies `Health.up()` status and `trackedConsumers` health details.
- `healthReturnsDownWhenStalled`: Verifies `Health.down()` and publishing of `AvailabilityChangeEvent(LivenessState.BROKEN)` when consumer offset stalls while messages are pending.
- `checkConsumerProgressSkipsWhenNullGroupMetadata` / `checkConsumerProgressSkipsWhenNullGroupId`: Ensures unmanaged or ungrouped consumers are safely skipped.
- `checkConsumerProgressSkipsWhenNoAssignedPartitions`: Skips consumers with empty partition assignments.
- `checkConsumerProgressSkipsWhenAllPartitionsPaused`: Excludes paused partitions from offset evaluation.
- `checkConsumerProgressHandlesAdminClientListOffsetsException` / `InterruptedException`: Verifies graceful failure handling when AdminClient list offsets RPC fails or is interrupted.
- `checkConsumerProgressHandlesAdminClientListConsumerGroupOffsetsException` / `InterruptedException`: Verifies graceful failure handling when consumer group offsets RPC fails or is interrupted.
- `checkConsumerProgressSkipsWhenLatestOffsetIsZeroOrNegative`: Validates empty/unwritten topics do not trigger health warnings.
- `checkConsumerProgressSucceedsWhenConsumerIsProgressing`: Validates offset baseline updates and healthy status when consumer advances.
- `checkConsumerProgressSucceedsWhenTopicFullyConsumed`: Validates consumer that has reached topic end (`currentOffset >= latestOffset`) remains healthy.

### 3. `CommittedOffsetMovementCheckIT` (7 Tests)
- `initExtractsKafkaConsumers`: Ensures all 5 consumer instances defined across `BaseConfig` and `TestListenerClass` are extracted.
- `healthIndicatorContainsDetails`: Checks health status `UP` and `trackedConsumers` metadata.
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
