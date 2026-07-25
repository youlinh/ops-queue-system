package com.acme.opsqueue.task;

import com.acme.opsqueue.scheduling.AutoAssignmentEngine;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TaskConfiguration {
    @Bean
    AutoAssignmentEngine autoAssignmentEngine() {
        return new AutoAssignmentEngine();
    }

    @Bean
    Clock taskClock() {
        return Clock.systemUTC();
    }
}
