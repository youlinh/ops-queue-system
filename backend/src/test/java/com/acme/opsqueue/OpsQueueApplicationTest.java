package com.acme.opsqueue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.acme.opsqueue.support.MySqlIntegrationTest;

@SpringBootTest(classes = OpsQueueApplication.class)
@ActiveProfiles("test")
class OpsQueueApplicationTest extends MySqlIntegrationTest {
    @Test
    void contextLoads() {
    }
}
