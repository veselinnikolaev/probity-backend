package me.veselin.probity.bff.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SecurityConfigurationTest {

    @Autowired
    private SecurityConfiguration securityConfiguration;

    @Test
    void contextLoads() {
        // If this test passes, the @PostConstruct validation succeeded
        // (empty secret would have thrown exception on startup)
        assertThat(securityConfiguration).isNotNull();
    }
}
