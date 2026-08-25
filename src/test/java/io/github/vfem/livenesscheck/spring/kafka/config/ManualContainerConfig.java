package io.github.vfem.livenesscheck.spring.kafka.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

@Configuration
public class ManualContainerConfig {

    @Bean
    public ConcurrentMessageListenerContainer<Integer, String> manualContainer(ConsumerFactory<Integer, String> consumerFactory) {
        ContainerProperties containerProps = new ContainerProperties("manualTopic");
        containerProps.setGroupId("manualGroup");
        containerProps.setMessageListener((MessageListener<Integer, String>) message -> {
            System.out.println("Received manually: " + message);
        });
        ConcurrentMessageListenerContainer<Integer, String> container =
                new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
        container.setConcurrency(1);
        return container;
    }

    @Bean
    public KafkaMessageListenerContainer<Integer, String> singleManualContainer(ConsumerFactory<Integer, String> consumerFactory) {
        ContainerProperties containerProps = new ContainerProperties("singleManualTopic");
        containerProps.setGroupId("singleManualGroup");
        containerProps.setMessageListener((MessageListener<Integer, String>) message -> {
            System.out.println("Received via single manual container: " + message);
        });
        return new KafkaMessageListenerContainer<>(consumerFactory, containerProps);
    }
}
