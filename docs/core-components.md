# Core Components

This document describes the internals of [`CommittedOffsetMovementCheck`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java), the primary service class of the library.

---

## 🔍 Class Summary: `CommittedOffsetMovementCheck`

- **Location**: [`src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java)
- **Implemented Interface**: `org.springframework.boot.actuate.health.HealthIndicator`
- **Key State Variables**:
  - `Map<String, Map<TopicPartition, OffsetAndMetadata>> groupsTopicPartitionOffsets`: Tracks historical committed offsets across `(groupId -> (TopicPartition -> OffsetAndMetadata))`.
  - `Set<KafkaConsumer<?, ?>> consumers`: Thread-safe set (`CopyOnWriteArraySet`) of active Kafka consumer instances.
  - `AdminClient adminClient`: Native Kafka admin client used for querying topic and group metadata.
  - `long adminTimeoutMs`: Timeout in milliseconds for Kafka AdminClient RPC operations (default 5000ms).

---

## 🪞 Reflection-Based Consumer Extraction

To monitor offsets without requiring invasive changes to consumer code, the class accesses underlying Kafka consumers via reflection on Spring's listener containers:

1. **Hierarchy Traversal**:
   - For `ConcurrentMessageListenerContainer`: Iterates over child `KafkaMessageListenerContainer` instances via `getContainers()`.
   - For `KafkaMessageListenerContainer`: Accesses private field `listenerConsumer` (`KafkaMessageListenerContainer$ListenerConsumer`).
   - From `listenerConsumer`: Accesses private field `consumer` to retrieve `KafkaConsumer<?, ?>`.
   - See [`extractKafkaConsumer(...)`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java#L107-L120).

2. **Partition Subscriptions & Pause States**:
   - Accesses `consumer.subscriptions` (instance of `SubscriptionState`).
   - Invokes `subscriptions.assignedPartitions()` to get assigned `Set<TopicPartition>`.
   - Invokes `subscriptions.pausedPartitions()` to get paused partitions and excludes them from check evaluation.
   - See [`extractAssigned(...)`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java#L236-L250) and [`extractPaused(...)`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java#L252-L266).

---

## 🩺 On-Demand Health Indicator (`health()`)

When Spring Boot Actuator queries `/actuator/health` or `/actuator/health/liveness`, `CommittedOffsetMovementCheck.health()` executes:

```java
@Override
public Health health() {
    boolean healthy = checkConsumerProgress();
    return healthy ? Health.up().build() : Health.down().build();
}
```

---

## 📊 Offset Verification Algorithm (`checkConsumerProgress`)

For every tracked `KafkaConsumer`:

```mermaid
flowchart TD
    Start["Check Consumer Progress"] --> GetGroup{"Has Group ID?"}
    GetGroup -- No --> Skip["Skip (Trace Log)"]
    GetGroup -- Yes --> GetPartitions["Extract Assigned Partitions (minus Paused)"]
    GetPartitions --> EmptyCheck{"Assigned Partitions Empty?"}
    EmptyCheck -- Yes --> Skip
    EmptyCheck -- No --> QueryAdmin["Query AdminClient: Latest Offsets & Committed Offsets"]
    QueryAdmin --> ForEachPartition["For Each Assigned Partition"]
    ForEachPartition --> CheckLatest{"Latest Offset <= 0?"}
    CheckLatest -- Yes --> NextPart["Skip (Empty Partition)"]
    CheckLatest -- No --> CheckPrev{"Previous Offset Exists?"}
    CheckPrev -- No --> InitPrev["Initialize Previous Offset with Current"]
    CheckPrev -- Yes --> CheckEnd{"Current >= Latest?"}
    CheckEnd -- Yes --> EndReached["End Reached: OK (Topic Fully Consumed)"]
    CheckEnd -- No --> CompareOffsets{"Previous Offset >= Current Offset?"}
    CompareOffsets -- Yes --> TriggerBroken["Publish AvailabilityChangeEvent(LivenessState.BROKEN) & Mark Unhealthy"]
    CompareOffsets -- No --> UpdatePrev["Update Previous Offset Record"]
```

### Key Decision Branches in Code:
1. **Empty / No Messages on Topic**: If `latestOffsetForPartition <= 0`, partition is skipped.
2. **First Check Run**: If no previous offset recorded, current committed offset (or 0) is saved as baseline.
3. **End of Topic Reached**: If `currentOffset >= latestOffset`, consumer is completely caught up, no failure raised.
4. **Stalled Consumer**: If `previousOffset >= currentOffset` while `currentOffset < latestOffset`, failure is triggered:
   ```java
   AvailabilityChangeEvent.publish(applicationContext, LivenessState.BROKEN);
   isHealthy = false;
   ```

---

## 🛑 Lifecycle & Teardown

- **Method**: [`shutdown()`](file:///c:/workdir/spring-liveness-indicators/src/main/java/io/github/vfem/livenesscheck/spring/kafka/CommittedOffsetMovementCheck.java#L268-L278) (annotated with `@PreDestroy`)
- **Teardown Flow**:
  1. Closes the `AdminClient` instance cleanly.

---

## 🔗 Related Documents

- [Architecture & Flow](file:///c:/workdir/spring-liveness-indicators/docs/architecture.md)
- [Testing Guide](file:///c:/workdir/spring-liveness-indicators/docs/testing-guide.md)
- [Configuration Reference](file:///c:/workdir/spring-liveness-indicators/docs/configuration-reference.md)
