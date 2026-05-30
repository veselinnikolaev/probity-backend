package me.veselin.probity.simulation.port;

import me.veselin.probity.bff.dto.simulation.RunSimulationRequest;
import me.veselin.probity.bff.dto.simulation.SimulationResultDto;

import java.util.List;
import java.util.UUID;

public interface SimulationPort {
    SimulationResultDto run(RunSimulationRequest request, UUID userId);

    SimulationResultDto get(UUID simulationId, UUID userId);

    List<SimulationResultDto> listForPortfolio(UUID portfolioId, UUID userId);
}