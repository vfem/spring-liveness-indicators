package io.github.vfem.livenesscheck.spring.kafka.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableAutoConfiguration
@ComponentScan
@EnableKafka
public class BaseConfig {

    private static final Logger log = LoggerFactory.getLogger(BaseConfig.class);

    @Value("${" + EmbeddedKafkaBroker.SPRING_EMBEDDED_KAFKA_BROKERS + "}")
    private String brokerAddresses;

    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<Integer, String>> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<Integer, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(1);
        factory.getContainerProperties().setPollTimeout(3000);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        return factory;
    }

    @Bean
    public ConsumerFactory<Integer, String> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerConfigs());
    }

    @Bean
    public Map<String, Object> consumerConfigs() {
        Map<String, Object> props = new HashMap<>();
        System.out.println(brokerAddresses);

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerAddresses);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "300000");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "100");
        return props;
    }

    @KafkaListener(id = "one", groupId = "methodGroup", topics = "methodTopic1")
    public void listen1(ConsumerRecord<String, String> consumerRecord) {
        log.info("Received Kafka Record from topic '{}' - key '{}', payload '{}'", consumerRecord.topic(), consumerRecord.key(), consumerRecord.value());
    }

    @KafkaListener(id = "two", groupId = "methodGroup", topics = "methodTopic2")
    public void listen2(ConsumerRecord<String, String> consumerRecord) {
        log.info("Received Kafka Record from topic '{}' - key '{}', payload '{}'", consumerRecord.topic(), consumerRecord.key(), consumerRecord.value());
    }

    @KafkaListener(id = "three", groupId = "patternGroup", topicPattern = "method.*")
    public void listen3(ConsumerRecord<String, String> consumerRecord) {
        log.info("Received Kafka Record from topic '{}' - key '{}', payload '{}'", consumerRecord.topic(), consumerRecord.key(), consumerRecord.value());
    }

    @KafkaListener(id = "slowConsumer", groupId = "slowListener", topics = "slowMethodTopic")
    public void slowListener(ConsumerRecord<String, String> consumerRecord) {
        log.info("slowListener received Kafka Record from topic '{}' - key '{}', payload '{}'",
                consumerRecord.topic(), consumerRecord.key(), consumerRecord.value());

        try {
            log.info("slowListener is sleeping for 10 seconds");
            Thread.sleep(10_000);
            log.info("slowListener woke up after 10 seconds");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        log.info("slowListener has processed Kafka Record from topic '{}' - key '{}', payload '{}'",
                consumerRecord.topic(), consumerRecord.key(), consumerRecord.value());
    }

}
