package io.github.vfem.livenesscheck.spring.kafka.config;

import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.function.Function;

@Configuration
@Import(TestChannelBinderConfiguration.class)
public class TestCloudStreamConfig {

    @Bean
    public Function<?, ?> noOpFunc() {
        return m -> m;
    }

}
