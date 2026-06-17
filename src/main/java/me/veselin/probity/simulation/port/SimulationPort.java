package me.veselin.probity.simulation.port;

import me.veselin.probity.simulation.dto.SimulationData;

import java.util.List;
import java.util.UUID;

public interface SimulationPort {
    SimulationData runSimulation(UUID portfolioId, int numberOfSimulations, int timeHorizonDays, double confidenceLevel, Double assumedReturnPercent, Double assumedVolatilityPercent, UUID userId);

    SimulationData getSimulation(UUID simulationId, UUID userId);

    List<SimulationData> listSimulations(UUID portfolioId, UUID userId);
}