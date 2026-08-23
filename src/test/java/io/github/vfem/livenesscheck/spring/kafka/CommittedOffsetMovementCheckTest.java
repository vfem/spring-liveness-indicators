package io.github.vfem.livenesscheck.spring.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.internals.KafkaFutureImpl;
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
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
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
    private Set<MessageListenerContainer> containers;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", "localhost:9092");

        check = new CommittedOffsetMovementCheck(3000L, 3, applicationContext, config);

        // Close initial admin client to prevent background connection retries to localhost:9092
        Field adminField = CommittedOffsetMovementCheck.class.getDeclaredField("adminClient");
        adminField.setAccessible(true);
        AdminClient initialAdminClient = (AdminClient) adminField.get(check);
        if (initialAdminClient != null) {
            initialAdminClient.close();
        }

        // Inject mocked AdminClient
        adminField.set(check, adminClient);

        // Get access to containers set
        Field containersField = CommittedOffsetMovementCheck.class.getDeclaredField("containers");
        containersField.setAccessible(true);
        containers = (Set<MessageListenerContainer>) containersField.get(check);
    }

    @Test
    void constructorValidation() {
        Map<String, Object> config = Map.of("bootstrap.servers", "localhost:9092");

        assertThatThrownBy(() -> new CommittedOffsetMovementCheck(1000L, 3, null, config))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new CommittedOffsetMovementCheck(0L, 3, applicationContext, config))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new CommittedOffsetMovementCheck(-5L, 3, applicationContext, config))
                .isInstanceOf(IllegalArgumentException.class);
        
        assertThatThrownBy(() -> new CommittedOffsetMovementCheck(5000L, 0, applicationContext, config))
                .isInstanceOf(IllegalArgumentException.class);

        CommittedOffsetMovementCheck defaultCheck = new CommittedOffsetMovementCheck(applicationContext, config);
        assertThat(defaultCheck).isNotNull();
        defaultCheck.shutdown();
    }

    @Test
    void isConsumersEmptyAndSize() {
        assertThat(check.isConsumersEmpty()).isTrue();
        assertThat(check.getConsumersSize()).isEqualTo(0);

        MessageListenerContainer container = createMockContainer("group1", Set.of(new TopicPartition("t", 0)), false);
        containers.add(container);

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
        assertThat(health.getDetails()).containsEntry("trackedContainers", 0);
    }

    @Test
    void healthReturnsDownWhenStalledExceedsThreshold() {
        TopicPartition tp = new TopicPartition("myTopic", 0);
        MessageListenerContainer container = createMockContainer("testGroup", Set.of(tp), false);
        containers.add(container);

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

        // Check 2: offset still at 50 with latest 100 -> stalled 1 (Health UP)
        Health health2 = check.health();
        assertThat(health2.getStatus()).isEqualTo(Status.UP);

        // Check 3: stalled 2 (Health UP)
        Health health3 = check.health();
        assertThat(health3.getStatus()).isEqualTo(Status.UP);

        // Check 4: stalled 3 (Health DOWN)
        Health health4 = check.health();
        assertThat(health4.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health4.getDetails()).containsEntry("reason", "One or more Kafka consumers stalled while unconsumed messages remain");
        assertThat(health4.getDetails()).containsEntry("trackedContainers", 1);

        ArgumentCaptor<AvailabilityChangeEvent> eventCaptor = ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
        verify(applicationContext).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getState()).isEqualTo(LivenessState.BROKEN);
    }

    @Test
    void checkConsumerProgressSkipsWhenNullGroupId() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ContainerProperties props = mock(ContainerProperties.class);
        when(props.getGroupId()).thenReturn(null);
        when(container.getContainerProperties()).thenReturn(props);
        containers.add(container);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
    }

    @Test
    void checkConsumerProgressSkipsWhenNoAssignedPartitions() {
        MessageListenerContainer container = createMockContainer("group1", Collections.emptySet(), false);
        containers.add(container);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
        verify(adminClient, never()).listOffsets(any());
    }

    @Test
    void checkConsumerProgressSkipsWhenContainerPaused() {
        TopicPartition tp = new TopicPartition("topic1", 0);
        MessageListenerContainer container = createMockContainer("group1", Set.of(tp), true);
        containers.add(container);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
        verify(adminClient, never()).listOffsets(any());
    }

    @Test
    void checkConsumerProgressHandlesAdminClientListOffsetsException() {
        TopicPartition tp = new TopicPartition("topic1", 0);
        MessageListenerContainer container = createMockContainer("group1", Set.of(tp), false);
        containers.add(container);

        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        KafkaFutureImpl<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> future = new KafkaFutureImpl<>();
        future.completeExceptionally(new ExecutionException("Broker error", new RuntimeException()));
        when(listOffsetsResult.all()).thenReturn(future);
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        boolean result = check.checkConsumerProgress();
        assertThat(result).isTrue();
    }

    @Test
    void checkConsumerProgressHandlesAdminClientListOffsetsInterruptedException() {
        TopicPartition tp = new TopicPartition("topic1", 0);
        MessageListenerContainer container = createMockContainer("group1", Set.of(tp), false);
        containers.add(container);

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
    void checkConsumerProgressHandlesAdminClientListConsumerGroupOffsetsException() {
        TopicPartition tp = new TopicPartition("topic1", 0);
        MessageListenerContainer container = createMockContainer("group1", Set.of(tp), false);
        containers.add(container);

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
    void checkConsumerProgressHandlesAdminClientListConsumerGroupOffsetsInterruptedException() {
        TopicPartition tp = new TopicPartition("topic1", 0);
        MessageListenerContainer container = createMockContainer("group1", Set.of(tp), false);
        containers.add(container);

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
    void checkConsumerProgressSkipsWhenLatestOffsetIsZeroOrNegative() {
        TopicPartition tp = new TopicPartition("topic1", 0);
        MessageListenerContainer container = createMockContainer("group1", Set.of(tp), false);
        containers.add(container);

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
    void checkConsumerProgressSucceedsWhenConsumerIsProgressing() {
        TopicPartition tp = new TopicPartition("topic1", 0);
        MessageListenerContainer container = createMockContainer("group1", Set.of(tp), false);
        containers.add(container);

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
    void checkConsumerProgressSucceedsWhenTopicFullyConsumed() {
        TopicPartition tp = new TopicPartition("topic1", 0);
        MessageListenerContainer container = createMockContainer("group1", Set.of(tp), false);
        containers.add(container);

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

    private MessageListenerContainer createMockContainer(String groupId, Set<TopicPartition> assigned, boolean paused) {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ContainerProperties props = mock(ContainerProperties.class);
        lenient().when(props.getGroupId()).thenReturn(groupId);
        lenient().when(container.getContainerProperties()).thenReturn(props);
        lenient().when(container.getAssignedPartitions()).thenReturn(assigned);
        lenient().when(container.isContainerPaused()).thenReturn(paused);
        lenient().when(container.isPauseRequested()).thenReturn(paused);
        return container;
    }
}
