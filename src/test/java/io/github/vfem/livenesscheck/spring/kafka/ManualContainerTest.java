package io.github.vfem.livenesscheck.spring.kafka;

import io.github.vfem.livenesscheck.spring.kafka.config.BaseConfig;
import io.github.vfem.livenesscheck.spring.kafka.config.ManualContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
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

    @Test
    public void testManualContainerIsDetected() {
        // BaseConfig defines 5 containers (@KafkaListener annotations).
        // ManualContainerConfig defines 1 container manually as a @Bean.
        // The check should be able to detect all 6 containers.
        assertThat(check.getConsumersSize()).isGreaterThanOrEqualTo(6);
    }
}
