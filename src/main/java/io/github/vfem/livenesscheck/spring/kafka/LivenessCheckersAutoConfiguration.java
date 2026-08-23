package io.github.vfem.livenesscheck.spring.kafka;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
@Conditional(LivenessCheckerCondition.class)
public class LivenessCheckersAutoConfiguration {

    @Bean
    @Autowired
    public CommittedOffsetMovementCheck committedOffsetMovementCheck(
            @Value("${liveness.kafka.admin-timeout-ms:5000}")
            long adminTimeoutMs,
            @Value("${liveness.kafka.max-stalled-checks:3}")
            int maxStalledChecks,
            ApplicationContext applicationContext,
            KafkaAdmin kafkaAdmin) {
        return new CommittedOffsetMovementCheck(
                adminTimeoutMs,
                maxStalledChecks,
                applicationContext,
                kafkaAdmin.getConfigurationProperties()
        );
    }

}
