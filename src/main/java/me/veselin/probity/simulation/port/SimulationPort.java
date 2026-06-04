package me.veselin.probity.simulation.port;

import me.veselin.probity.bff.dto.simulation.RunSimulationRequest;
import me.veselin.probity.bff.dto.simulation.SimulationResultDto;

import java.util.List;
import java.util.UUID;

public interface SimulationPort {
    SimulationResultDto runSimulation(RunSimulationRequest request, UUID userId);

    SimulationResultDto getSimulation(UUID simulationId, UUID userId);

    List<SimulationResultDto> listSimulations(UUID portfolioId, UUID userId);
}