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
            @Value("${liveness.kafka.scheduled:true}")
            Boolean scheduled,
            @Value("${liveness.kafka.check-initial-delay-sec:600}")
            long checkInitialDelaySec,
            @Value("${liveness.kafka.check-period-sec:600}")
            long checkPeriodSec,
            ApplicationContext applicationContext,
            KafkaAdmin kafkaAdmin) {
        return new CommittedOffsetMovementCheck(
                scheduled,
                checkInitialDelaySec,
                checkPeriodSec,
                applicationContext,
                kafkaAdmin.getConfigurationProperties()
        );
    }

}
