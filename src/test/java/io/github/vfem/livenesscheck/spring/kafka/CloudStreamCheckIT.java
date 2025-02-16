package io.github.vfem.livenesscheck.spring.kafka;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("stream")
public class CloudStreamCheckIT {

    //setup and scenario
    //setup
    //1) configure embedded kafka with input and output topics
    //2) configure kafkaTemplate to write into input topic
    //3) get ready to read output, consumer state, liveness state
    //scenarios
    //check the same cases as in kafka listener setup

}
