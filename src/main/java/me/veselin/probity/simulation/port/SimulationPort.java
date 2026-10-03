package me.veselin.probity.simulation.port;

import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.simulation.dto.SimulationData;
import me.veselin.probity.simulation.dto.SimulationStatusResponse;

import java.util.List;
import java.util.UUID;

public interface SimulationPort {
    SimulationData runSimulation(UUID portfolioId, int numberOfSimulations, int timeHorizonDays, double confidenceLevel, Double assumedReturnPercent, Double assumedVolatilityPercent, UUID userId);

    /**
     * Initiates an asynchronous simulation run.
     * Creates a PENDING simulation record and publishes a Kafka event for processing.
     * Returns 202 Accepted with status location.
     */
    SimulationStatusResponse runSimulationAsync(UUID portfolioId, int numberOfSimulations, int timeHorizonDays, double confidenceLevel, Double assumedReturnPercent, Double assumedVolatilityPercent, UUID userId, String idempotencyKey);

    SimulationData getSimulation(UUID simulationId, UUID userId);

    /**
     * Gets the current status of a simulation.
     * Returns status and result if COMPLETED.
     */
    SimulationStatusResponse getSimulationStatus(UUID simulationId, UUID userId);

    /**
     * Gets a portfolio with positions for market data snapshot capture.
     */
    Portfolio getSimulationPortfolio(UUID portfolioId, UUID userId);

    List<SimulationData> listSimulations(UUID portfolioId, UUID userId);
}