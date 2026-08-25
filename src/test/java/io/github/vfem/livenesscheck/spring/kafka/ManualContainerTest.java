package io.github.vfem.livenesscheck.spring.kafka;

import io.github.vfem.livenesscheck.spring.kafka.config.BaseConfig;
import io.github.vfem.livenesscheck.spring.kafka.config.ManualContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {BaseConfig.class, ManualContainerConfig.class, LivenessCheckersAutoConfiguration.class})
@EmbeddedKafka(partitions = 1, brokerProperties = { "listeners=PLAINTEXT://localhost:9092", "port=9092" })
@TestPropertySource(properties = {
    "liveness.kafka.enabled=true"
})
public class ManualContainerTest {

    @Autowired
    private CommittedOffsetMovementCheck check;

    @Autowired
    private ConsumerFactory<Integer, String> consumerFactory;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private KafkaListenerContainerFactory<?> kafkaListenerContainerFactory;

    @Test
    public void testManualContainersAreDetected() {
        // BaseConfig defines 5 containers (@KafkaListener annotations).
        // ManualContainerConfig defines 1 ConcurrentMessageListenerContainer bean + 1 KafkaMessageListenerContainer bean.
        // The check should be able to detect at least 7 containers.
        assertThat(check.getConsumersSize()).isGreaterThanOrEqualTo(7);
        assertThat(check.isConsumersEmpty()).isFalse();
    }

    @Test
    public void testExplicitRegisterContainer() {
        int initialSize = check.getConsumersSize();

        ContainerProperties props = new ContainerProperties("dynamicExplicitTopic");
        props.setGroupId("dynamicExplicitGroup");
        props.setMessageListener((MessageListener<Integer, String>) msg -> {});

        KafkaMessageListenerContainer<Integer, String> dynamicContainer =
                new KafkaMessageListenerContainer<>(consumerFactory, props);

        check.registerContainer(dynamicContainer);

        assertThat(check.getConsumersSize()).isEqualTo(initialSize + 1);
        assertThat(check.resolveContainers()).contains(dynamicContainer);
    }

    @Test
    public void testProgrammaticEndpointRegistrationInRegistry() throws NoSuchMethodException {
        int initialSize = check.getConsumersSize();

        MethodKafkaListenerEndpoint<Integer, String> endpoint = new MethodKafkaListenerEndpoint<>();
        endpoint.setId("dynamicEndpointId");
        endpoint.setGroupId("dynamicEndpointGroup");
        endpoint.setTopics("dynamicEndpointTopic");
        endpoint.setBean(this);
        endpoint.setMethod(ManualContainerTest.class.getMethod("dummyListener", String.class));
        endpoint.setMessageHandlerMethodFactory(new DefaultMessageHandlerMethodFactory());

        registry.registerListenerContainer(endpoint, kafkaListenerContainerFactory, true);

        // Dynamically discovered via resolveContainers()
        assertThat(check.getConsumersSize()).isGreaterThanOrEqualTo(initialSize + 1);
    }

    public void dummyListener(String message) {
        // dummy listener for programmatic endpoint
    }
}

