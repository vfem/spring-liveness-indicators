package io.github.vfem.livenesscheck.spring.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaContainerSourcesTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private AdminClient adminClient;

    private CommittedOffsetMovementCheck check;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", "localhost:9092");

        check = new CommittedOffsetMovementCheck(3000L, 3, applicationContext, config);

        // Close initial admin client and inject mock
        Field adminField = CommittedOffsetMovementCheck.class.getDeclaredField("adminClient");
        adminField.setAccessible(true);
        AdminClient initialAdminClient = (AdminClient) adminField.get(check);
        if (initialAdminClient != null) {
            initialAdminClient.close();
        }
        adminField.set(check, adminClient);
    }

    @Test
    void discoversContainersFromMultipleKafkaListenerEndpointRegistries() {
        KafkaListenerEndpointRegistry defaultRegistry = mock(KafkaListenerEndpointRegistry.class);
        KafkaListenerEndpointRegistry customRegistry = mock(KafkaListenerEndpointRegistry.class);

        MessageListenerContainer container1 = createMockSingleContainer("group1", Set.of(new TopicPartition("t1", 0)));
        MessageListenerContainer container2 = createMockSingleContainer("group2", Set.of(new TopicPartition("t2", 0)));

        when(defaultRegistry.getAllListenerContainers()).thenReturn(List.of(container1));
        when(customRegistry.getAllListenerContainers()).thenReturn(List.of(container2));

        when(applicationContext.getBeansOfType(KafkaListenerEndpointRegistry.class))
                .thenReturn(Map.of("defaultRegistry", defaultRegistry, "customRegistry", customRegistry));
        when(applicationContext.getBeansOfType(MessageListenerContainer.class))
                .thenReturn(Collections.emptyMap());

        Set<MessageListenerContainer> resolved = check.resolveContainers();
        assertThat(resolved).containsExactlyInAnyOrder(container1, container2);
        assertThat(check.getConsumersSize()).isEqualTo(2);
        assertThat(check.isConsumersEmpty()).isFalse();
    }

    @Test
    void unwrapsConcurrentMessageListenerContainerFromRegistry() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        ConcurrentMessageListenerContainer<?, ?> concurrentContainer = mock(ConcurrentMessageListenerContainer.class);
        KafkaMessageListenerContainer<?, ?> child1 = mock(KafkaMessageListenerContainer.class);
        KafkaMessageListenerContainer<?, ?> child2 = mock(KafkaMessageListenerContainer.class);

        setupContainerProperties(child1, "concurrentGroup");
        setupContainerProperties(child2, "concurrentGroup");

        doReturn(List.of(child1, child2)).when(concurrentContainer).getContainers();
        when(registry.getAllListenerContainers()).thenReturn(List.of(concurrentContainer));

        when(applicationContext.getBeansOfType(KafkaListenerEndpointRegistry.class))
                .thenReturn(Map.of("registry", registry));
        when(applicationContext.getBeansOfType(MessageListenerContainer.class))
                .thenReturn(Collections.emptyMap());

        Set<MessageListenerContainer> resolved = check.resolveContainers();
        assertThat(resolved).containsExactlyInAnyOrder(child1, child2);
        assertThat(check.getConsumersSize()).isEqualTo(2);
    }

    @Test
    void handlesConcurrentContainerWithEmptyChildrenGracefully() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        ConcurrentMessageListenerContainer<?, ?> concurrentContainer = mock(ConcurrentMessageListenerContainer.class);
        setupContainerProperties(concurrentContainer, "emptyGroup");

        doReturn(Collections.emptyList()).when(concurrentContainer).getContainers();
        when(registry.getAllListenerContainers()).thenReturn(List.of(concurrentContainer));

        when(applicationContext.getBeansOfType(KafkaListenerEndpointRegistry.class))
                .thenReturn(Map.of("registry", registry));
        when(applicationContext.getBeansOfType(MessageListenerContainer.class))
                .thenReturn(Collections.emptyMap());

        Set<MessageListenerContainer> resolved = check.resolveContainers();
        assertThat(resolved).containsExactly(concurrentContainer);
    }

    @Test
    void discoversDirectMessageListenerContainerBeans() {
        KafkaMessageListenerContainer<?, ?> directSingleContainer = mock(KafkaMessageListenerContainer.class);
        ConcurrentMessageListenerContainer<?, ?> directConcurrentContainer = mock(ConcurrentMessageListenerContainer.class);
        KafkaMessageListenerContainer<?, ?> child = mock(KafkaMessageListenerContainer.class);

        setupContainerProperties(directSingleContainer, "singleBeanGroup");
        setupContainerProperties(child, "concurrentBeanGroup");

        doReturn(List.of(child)).when(directConcurrentContainer).getContainers();

        when(applicationContext.getBeansOfType(KafkaListenerEndpointRegistry.class))
                .thenReturn(Collections.emptyMap());
        when(applicationContext.getBeansOfType(MessageListenerContainer.class))
                .thenReturn(Map.of("singleBean", directSingleContainer, "concurrentBean", directConcurrentContainer));

        Set<MessageListenerContainer> resolved = check.resolveContainers();
        assertThat(resolved).containsExactlyInAnyOrder(directSingleContainer, child);
        assertThat(check.getConsumersSize()).isEqualTo(2);
    }

    @Test
    void allowsManualContainerRegistrationViaMethod() {
        KafkaMessageListenerContainer<?, ?> manualContainer = mock(KafkaMessageListenerContainer.class);
        setupContainerProperties(manualContainer, "manualGroup");

        when(applicationContext.getBeansOfType(KafkaListenerEndpointRegistry.class))
                .thenReturn(Collections.emptyMap());
        when(applicationContext.getBeansOfType(MessageListenerContainer.class))
                .thenReturn(Collections.emptyMap());

        assertThat(check.isConsumersEmpty()).isTrue();

        check.registerContainer(manualContainer);

        assertThat(check.isConsumersEmpty()).isFalse();
        assertThat(check.getConsumersSize()).isEqualTo(1);
        assertThat(check.resolveContainers()).contains(manualContainer);
    }

    @Test
    void dynamicallyDiscoversContainersAddedAfterInit() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container1 = createMockSingleContainer("g1", Set.of(new TopicPartition("t1", 0)));

        when(registry.getAllListenerContainers()).thenReturn(List.of(container1));
        when(applicationContext.getBeansOfType(KafkaListenerEndpointRegistry.class))
                .thenReturn(Map.of("registry", registry));
        when(applicationContext.getBeansOfType(MessageListenerContainer.class))
                .thenReturn(Collections.emptyMap());

        // Startup initialization
        check.init();
        assertThat(check.getConsumersSize()).isEqualTo(1);

        // Later, dynamic container is added to registry at runtime
        MessageListenerContainer container2 = createMockSingleContainer("g2", Set.of(new TopicPartition("t2", 0)));
        when(registry.getAllListenerContainers()).thenReturn(List.of(container1, container2));

        // resolveContainers dynamically reflects new container
        assertThat(check.getConsumersSize()).isEqualTo(2);
        assertThat(check.resolveContainers()).containsExactlyInAnyOrder(container1, container2);
    }

    private MessageListenerContainer createMockSingleContainer(String groupId, Set<TopicPartition> assigned) {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        setupContainerProperties(container, groupId);
        lenient().when(container.getAssignedPartitions()).thenReturn(assigned);
        return container;
    }

    private void setupContainerProperties(MessageListenerContainer container, String groupId) {
        ContainerProperties props = mock(ContainerProperties.class);
        lenient().when(props.getGroupId()).thenReturn(groupId);
        lenient().when(container.getContainerProperties()).thenReturn(props);
    }
}
