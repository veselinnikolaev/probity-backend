package me.veselin.probity.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;

/**
 * Configuration for domain event publishing.
 * Registers a custom ApplicationEventMulticaster with error handling
 * to isolate listener exceptions and prevent one failing listener from
 * stopping others.
 */
@Configuration
@Slf4j
public class EventConfiguration {

    /**
     * Custom ApplicationEventMulticaster with error handler.
     * Bean name must be "applicationEventMulticaster" to override Spring's default.
     */
    @Bean(name = "applicationEventMulticaster")
    public ApplicationEventMulticaster applicationEventMulticaster() {
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.setErrorHandler(throwable -> {
            log.error("Listener threw exception during event processing", throwable);
        });
        return multicaster;
    }
}
