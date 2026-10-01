package me.veselin.probity.simulation.service;

import me.veselin.probity.auth.domain.User;
import me.veselin.probity.auth.repository.UserRepository;
import me.veselin.probity.common.domain.event.DomainEvent;
import me.veselin.probity.common.domain.event.SimulationCompletedEvent;
import me.veselin.probity.common.domain.event.SimulationFailedEvent;
import me.veselin.probity.common.domain.event.SimulationRequestedEvent;
import me.veselin.probity.common.domain.event.TestEventCapture;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.persistence.PortfolioRepository;
import me.veselin.probity.simulation.BaseSimulationIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for simulation event publishing.
 * Verifies that simulation events are fired correctly from MonteCarloSimulationService.run().
 */
@Transactional
public class SimulationEventPublishingTest extends BaseSimulationIntegrationTest {

    @Autowired
    private MonteCarloSimulationService monteCarloSimulationService;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private TestEventCapture testEventCapture;

    @BeforeEach
    void setupTestEventCapture() {
        testEventCapture.clear();
    }

    @Test
    void runSimulation_firesSimulationRequestedAndCompletedEvents() {
        // Arrange - use seeded portfolio from base class
        UUID portfolioUuid = UUID.fromString(portfolioId);
        UUID userId = userRepository.findByUsername(ADMIN_USERNAME)
                .map(User::getId)
                .orElseThrow();

        // Act
        monteCarloSimulationService.run(portfolioUuid, 100, 5, 0.95, null, null, userId);

        // Assert
        List<DomainEvent> events = testEventCapture.getCapturedEvents();
        assertThat(events).hasSize(2);

        // First event should be SimulationRequestedEvent
        assertThat(events.get(0)).isInstanceOf(SimulationRequestedEvent.class);
        SimulationRequestedEvent requestedEvent = (SimulationRequestedEvent) events.get(0);
        assertThat(requestedEvent.portfolioId()).isEqualTo(portfolioUuid);
        assertThat(requestedEvent.userId()).isEqualTo(userId);
        assertThat(requestedEvent.numberOfSimulations()).isEqualTo(100);
        assertThat(requestedEvent.timeHorizonDays()).isEqualTo(5);
        assertThat(requestedEvent.confidenceLevel()).isEqualTo(0.95);
        assertThat(requestedEvent.occurredAt()).isNotNull();

        // Second event should be SimulationCompletedEvent
        assertThat(events.get(1)).isInstanceOf(SimulationCompletedEvent.class);
        SimulationCompletedEvent completedEvent = (SimulationCompletedEvent) events.get(1);
        assertThat(completedEvent.portfolioId()).isEqualTo(portfolioUuid);
        assertThat(completedEvent.userId()).isEqualTo(userId);
        assertThat(completedEvent.simulationId()).isNotNull();
        assertThat(completedEvent.currentPortfolioValue()).isNotNull();
        assertThat(completedEvent.occurredAt()).isNotNull();
    }

    @Test
    void runSimulationWithEmptyPortfolio_firesSimulationRequestedAndFailedEventsAndRethrowsException() {
        // Arrange - create empty portfolio
        UUID userId = userRepository.findByUsername(ADMIN_USERNAME)
                .map(User::getId)
                .orElseThrow();

        Portfolio portfolio = Portfolio.create("Empty Portfolio", userId);
        Portfolio savedPortfolio = portfolioRepository.save(portfolio);

        // Act & Assert
        assertThatThrownBy(() ->
                monteCarloSimulationService.run(savedPortfolio.getId(), 100, 5, 0.95, null, null, userId)
        ).isInstanceOf(me.veselin.probity.simulation.exception.EmptyPortfolioException.class)
                .hasMessageContaining("has no positions");

        // Verify events were published
        List<DomainEvent> events = testEventCapture.getCapturedEvents();
        assertThat(events).hasSize(2);

        // First event should be SimulationRequestedEvent
        assertThat(events.get(0)).isInstanceOf(SimulationRequestedEvent.class);
        SimulationRequestedEvent requestedEvent = (SimulationRequestedEvent) events.get(0);
        assertThat(requestedEvent.portfolioId()).isEqualTo(savedPortfolio.getId());
        assertThat(requestedEvent.userId()).isEqualTo(userId);

        // Second event should be SimulationFailedEvent
        assertThat(events.get(1)).isInstanceOf(SimulationFailedEvent.class);
        SimulationFailedEvent failedEvent = (SimulationFailedEvent) events.get(1);
        assertThat(failedEvent.portfolioId()).isEqualTo(savedPortfolio.getId());
        assertThat(failedEvent.userId()).isEqualTo(userId);
        assertThat(failedEvent.errorMessage()).contains("has no positions");
        assertThat(failedEvent.occurredAt()).isNotNull();
    }
}
