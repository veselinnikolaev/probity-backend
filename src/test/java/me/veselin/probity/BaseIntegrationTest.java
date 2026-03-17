package me.veselin.probity;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.junit.jupiter.api.Order;

import java.time.Duration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {
    @Autowired RedisTemplate<String, String> redisTemplate;

    // shared across ALL test classes in the JVM — started once
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("probity_test")
            .withUsername("test")
            .withPassword("test");

    @ServiceConnection
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.2.4")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    static {
        postgres.start();
        redis.start();
    }

    @BeforeEach
    @Order(1)
    void cleanRedis() {
        redisTemplate.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();
    }
}