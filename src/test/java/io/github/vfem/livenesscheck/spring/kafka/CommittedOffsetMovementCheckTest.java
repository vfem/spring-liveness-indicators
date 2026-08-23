package io.github.vfem.livenesscheck.spring.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.internals.SubscriptionState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.common.utils.LogContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommittedOffsetMovementCheckTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private AdminClient adminClient;

    private CommittedOffsetMovementCheck check;
    private Set<KafkaConsumer<?, ?>> consumers;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", "localhost:9092");

        check = new CommittedOffsetMovementCheck(3000L, applicationContext, config);

        // Close initial admin client to prevent background connection retries to localhost:9092
        Field adminField = CommittedOffsetMovementCheck.class.getDeclaredField("adminClient");
        adminField.setAccessible(true);
        AdminClient initialAdminClient = (AdminClient) adminField.get(check);
        if (initialAdminClient != null) {
            initialAdminClient.close();
        }

        // Inject mocked AdminClient
        adminField.set(check, adminClient);

        // Get access to consumers set
        Field consumersField = CommittedOffsetMovementCheck.class.getDeclaredField("consumers");
        consumersField.setAccessible(true);
        consumers = (Set<KafkaConsumer<?, ?>>) consumersField.get(check);
    }

    @Test
    void constructorValidation() {
        Map<String, Object> config = Map.of("bootstrap.servers", "localhost:9092");

        assertThatThrownBy(() -> new CommittedOffsetMovementCheck(1000L, null, config))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new CommittedOffsetMovementCheck(0L, applicationContext, config))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new CommittedOffsetMovementCheck(-5L, applicationContext, config))
                .isInstanceOf(IllegalArgumentException.class);

        CommittedOffsetMovementCheck defaultCheck = new CommittedOffsetMovementCheck(applicationContext, config);
        assertThat(defaultCheck).isNotNull();
        defaultCheck.shutdown();
    }

    @Test
    void isConsumersEmptyAndSize() throws Exception {
        assertThat(check.isConsumersEmpty()).isTrue();
        assertThat(check.getConsumersSize()).isEqualTo(0);

        KafkaConsumer<?, ?> consumer = createMockConsumer("group1", Set.of(new TopicPartition("t", 0)), Set.of());
        consumers.add(consumer);

        assertThat(check.isConsumersEmpty()).isFalse();
        assertThat(check.getConsumersSize()).isEqualTo(1);
    }

    @Test
    void shutdownClosesAdminClient() {
        check.shutdown();
        verify(adminClient).close();
    }

    @Test
    void healthReturnsUpWhenProgressing() {
        Health health = check.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("trackedConsumers", 0);
    }

    @Test
    void healthReturnsDownWhenStalled() throws Exception {
        TopicPartition tp = new TopicPartition("myTopic", 0);
        KafkaConsumer<?, ?> consumer = createMockConsumer("testGroup", Set.of(tp), Set.of());
        consumers.add(consumer);

        // Mock latest offset as 100
        ListOffsetsResult.ListOffsetsResultInfo info = new ListOffsetsResult.ListOffsetsResultInfo(100L, 0L, null);
        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> futureLatest = new KafkaFutureImpl<>();
        futureLatest.complete(Map.of(tp, info));
        when(listOffsetsResult.all()).thenReturn(futureLatest);
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        // Mock committed offset as 50
        ListConsumerGroupOffsetsResult groupOffsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, OffsetAndMetadata>> futureCommitted = new KafkaFutureImpl<>();
        futureCommitted.complete(Map.of(tp, new OffsetAndMetadata(50L)));
        when(groupOffsetsResult.partitionsToOffsetAndMetadata()).thenReturn(futureCommitted);
        when(adminClient.listConsumerGroupOffsets("testGroup")).thenReturn(groupOffsetsResult);

        // Check 1: baseline established (Health UP)
        Health health1 = check.health();
        assertThat(health1.getStatus()).isEqualTo(Status.UP);

        // Check 2: offset still at 50 with latest 100 -> stalled (Health DOWN)
        Health health2 = check.health();
        assertThat(health2.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health2.getDetails()).containsEntry("reason", "One or more Kafka consumers stalled while unconsumed messages remain");
        assertThat(health2.getDetails()).containsEntry("trackedConsumers", 1);

        ArgumentCaptor<AvailabilityChangeEvent> eventCaptor = ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
        verify(applicationContext).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getState()).isEqualTo(LivenessState.BROKEN);
    }

    @Test
    void checkConsumerProgressSkipsWhenNullGroupMetadata() {
        KafkaConsumer<?, ?> consumer = mock(KafkaConsumer.class);
        when(consumer.groupMetadata()).thenReturn(null);
        consumers.add(consumer);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
    }

    @Test
    void checkConsumerProgressSkipsWhenNullGroupId() {
        KafkaConsumer<?, ?> consumer = mock(KafkaConsumer.class);
        ConsumerGroupMetadata metadata = mock(ConsumerGroupMetadata.class);
        when(metadata.groupId()).thenReturn(null);
        when(consumer.groupMetadata()).thenReturn(metadata);
        consumers.add(consumer);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
    }

    @Test
    void checkConsumerProgressSkipsWhenNoAssignedPartitions() throws Exception {
        KafkaConsumer<?, ?> consumer = createMockConsumer("group1", Collections.emptySet(), Collections.emptySet());
        consumers.add(consumer);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
        verify(adminClient, never()).listOffsets(any());
    }

    @Test
    void checkConsumerProgressSkipsWhenAllPartitionsPaused() throws Exception {
        TopicPartition tp = new TopicPartition("topic1", 0);
        KafkaConsumer<?, ?> consumer = createMockConsumer("group1", Set.of(tp), Set.of(tp));
        consumers.add(consumer);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
        verify(adminClient, never()).listOffsets(any());
    }

    @Test
    void checkConsumerProgressHandlesAdminClientListOffsetsException() throws Exception {
        TopicPartition tp = new TopicPartition("topic1", 0);
        KafkaConsumer<?, ?> consumer = createMockConsumer("group1", Set.of(tp), Collections.emptySet());
        consumers.add(consumer);

        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> future = new KafkaFutureImpl<>();
        future.completeExceptionally(new ExecutionException("Broker error", new RuntimeException()));
        when(listOffsetsResult.all()).thenReturn(future);
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
    }

    @Test
    void checkConsumerProgressHandlesAdminClientListOffsetsInterruptedException() throws Exception {
        TopicPartition tp = new TopicPartition("topic1", 0);
        KafkaConsumer<?, ?> consumer = createMockConsumer("group1", Set.of(tp), Collections.emptySet());
        consumers.add(consumer);

        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> future = new KafkaFutureImpl<>();
        future.completeExceptionally(new InterruptedException("Interrupted"));
        when(listOffsetsResult.all()).thenReturn(future);
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
        assertThat(Thread.interrupted()).isTrue(); // Verifies interrupt status was set and cleared
    }

    @Test
    void checkConsumerProgressHandlesAdminClientListConsumerGroupOffsetsException() throws Exception {
        TopicPartition tp = new TopicPartition("topic1", 0);
        KafkaConsumer<?, ?> consumer = createMockConsumer("group1", Set.of(tp), Collections.emptySet());
        consumers.add(consumer);

        ListOffsetsResult.ListOffsetsResultInfo info = new ListOffsetsResult.ListOffsetsResultInfo(10L, 0L, null);
        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> futureLatest = new KafkaFutureImpl<>();
        futureLatest.complete(Map.of(tp, info));
        when(listOffsetsResult.all()).thenReturn(futureLatest);
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        ListConsumerGroupOffsetsResult groupOffsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, OffsetAndMetadata>> futureGroup = new KafkaFutureImpl<>();
        futureGroup.completeExceptionally(new ExecutionException("Group coordinator error", new RuntimeException()));
        when(groupOffsetsResult.partitionsToOffsetAndMetadata()).thenReturn(futureGroup);
        when(adminClient.listConsumerGroupOffsets("group1")).thenReturn(groupOffsetsResult);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
    }

    @Test
    void checkConsumerProgressHandlesAdminClientListConsumerGroupOffsetsInterruptedException() throws Exception {
        TopicPartition tp = new TopicPartition("topic1", 0);
        KafkaConsumer<?, ?> consumer = createMockConsumer("group1", Set.of(tp), Collections.emptySet());
        consumers.add(consumer);

        ListOffsetsResult.ListOffsetsResultInfo info = new ListOffsetsResult.ListOffsetsResultInfo(10L, 0L, null);
        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> futureLatest = new KafkaFutureImpl<>();
        futureLatest.complete(Map.of(tp, info));
        when(listOffsetsResult.all()).thenReturn(futureLatest);
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        ListConsumerGroupOffsetsResult groupOffsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, OffsetAndMetadata>> futureGroup = new KafkaFutureImpl<>();
        futureGroup.completeExceptionally(new InterruptedException("Interrupted"));
        when(groupOffsetsResult.partitionsToOffsetAndMetadata()).thenReturn(futureGroup);
        when(adminClient.listConsumerGroupOffsets("group1")).thenReturn(groupOffsetsResult);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void checkConsumerProgressSkipsWhenLatestOffsetIsZeroOrNegative() throws Exception {
        TopicPartition tp = new TopicPartition("topic1", 0);
        KafkaConsumer<?, ?> consumer = createMockConsumer("group1", Set.of(tp), Collections.emptySet());
        consumers.add(consumer);

        ListOffsetsResult.ListOffsetsResultInfo info = new ListOffsetsResult.ListOffsetsResultInfo(0L, 0L, null);
        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> futureLatest = new KafkaFutureImpl<>();
        futureLatest.complete(Map.of(tp, info));
        when(listOffsetsResult.all()).thenReturn(futureLatest);
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        ListConsumerGroupOffsetsResult groupOffsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, OffsetAndMetadata>> futureGroup = new KafkaFutureImpl<>();
        futureGroup.complete(Map.of(tp, new OffsetAndMetadata(0L)));
        when(groupOffsetsResult.partitionsToOffsetAndMetadata()).thenReturn(futureGroup);
        when(adminClient.listConsumerGroupOffsets("group1")).thenReturn(groupOffsetsResult);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
    }

    @Test
    void checkConsumerProgressSucceedsWhenConsumerIsProgressing() throws Exception {
        TopicPartition tp = new TopicPartition("topic1", 0);
        KafkaConsumer<?, ?> consumer = createMockConsumer("group1", Set.of(tp), Collections.emptySet());
        consumers.add(consumer);

        ListOffsetsResult.ListOffsetsResultInfo info = new ListOffsetsResult.ListOffsetsResultInfo(100L, 0L, null);
        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> futureLatest = new KafkaFutureImpl<>();
        futureLatest.complete(Map.of(tp, info));
        when(listOffsetsResult.all()).thenReturn(futureLatest);
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        // Check 1: offset at 10
        ListConsumerGroupOffsetsResult groupOffsetsResult1 = mock(ListConsumerGroupOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, OffsetAndMetadata>> futureGroup1 = new KafkaFutureImpl<>();
        futureGroup1.complete(Map.of(tp, new OffsetAndMetadata(10L)));
        when(groupOffsetsResult1.partitionsToOffsetAndMetadata()).thenReturn(futureGroup1);
        when(adminClient.listConsumerGroupOffsets("group1")).thenReturn(groupOffsetsResult1);

        assertThat(check.checkConsumerProgress()).isTrue();

        // Check 2: offset progressed to 20
        ListConsumerGroupOffsetsResult groupOffsetsResult2 = mock(ListConsumerGroupOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, OffsetAndMetadata>> futureGroup2 = new KafkaFutureImpl<>();
        futureGroup2.complete(Map.of(tp, new OffsetAndMetadata(20L)));
        when(groupOffsetsResult2.partitionsToOffsetAndMetadata()).thenReturn(futureGroup2);
        when(adminClient.listConsumerGroupOffsets("group1")).thenReturn(groupOffsetsResult2);

        assertThat(check.checkConsumerProgress()).isTrue();
        verify(applicationContext, never()).publishEvent(any());
    }

    @Test
    void checkConsumerProgressSucceedsWhenTopicFullyConsumed() throws Exception {
        TopicPartition tp = new TopicPartition("topic1", 0);
        KafkaConsumer<?, ?> consumer = createMockConsumer("group1", Set.of(tp), Collections.emptySet());
        consumers.add(consumer);

        ListOffsetsResult.ListOffsetsResultInfo info = new ListOffsetsResult.ListOffsetsResultInfo(50L, 0L, null);
        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> futureLatest = new KafkaFutureImpl<>();
        futureLatest.complete(Map.of(tp, info));
        when(listOffsetsResult.all()).thenReturn(futureLatest);
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        ListConsumerGroupOffsetsResult groupOffsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, OffsetAndMetadata>> futureGroup = new KafkaFutureImpl<>();
        futureGroup.complete(Map.of(tp, new OffsetAndMetadata(50L)));
        when(groupOffsetsResult.partitionsToOffsetAndMetadata()).thenReturn(futureGroup);
        when(adminClient.listConsumerGroupOffsets("group1")).thenReturn(groupOffsetsResult);

        // Check 1: baseline established at 50
        assertThat(check.checkConsumerProgress()).isTrue();

        // Check 2: offset remains at 50 (equal to latest offset 50) -> fully caught up
        assertThat(check.checkConsumerProgress()).isTrue();
        verify(applicationContext, never()).publishEvent(any());
    }

    private KafkaConsumer<?, ?> createMockConsumer(String groupId, Set<TopicPartition> assigned, Set<TopicPartition> paused) throws Exception {
        KafkaConsumer<?, ?> consumer = mock(KafkaConsumer.class);
        ConsumerGroupMetadata metadata = new ConsumerGroupMetadata(groupId);
        lenient().when(consumer.groupMetadata()).thenReturn(metadata);

        SubscriptionState subscriptionState = new SubscriptionState(new LogContext(), org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST);
        if (!assigned.isEmpty()) {
            subscriptionState.assignFromUser(new HashSet<>(assigned));
        }
        if (!paused.isEmpty()) {
            for (TopicPartition tp : paused) {
                subscriptionState.pause(tp);
            }
        }

        Field subscriptionsField = KafkaConsumer.class.getDeclaredField("subscriptions");
        subscriptionsField.setAccessible(true);
        subscriptionsField.set(consumer, subscriptionState);

        return consumer;
    }
}
