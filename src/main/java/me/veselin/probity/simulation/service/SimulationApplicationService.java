package me.veselin.probity.simulation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.bff.dto.simulation.RunSimulationRequest;
import me.veselin.probity.bff.dto.simulation.SimulationResultDto;
import me.veselin.probity.simulation.port.SimulationPort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Application service for simulation operations.
 * Provides a layer between the BFF controller and the simulation port,
 * aligning with the hexagonal architecture pattern used in other modules.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationApplicationService implements SimulationPort {

    private final MonteCarloSimulationService monteCarloSimulationService;

    /**
     * Executes a new Monte Carlo simulation for a user-owned portfolio.
     *
     * @param request the simulation request parameters
     * @param userId the authenticated user's ID
     * @return the simulation result with persisted payload
     */
    public SimulationResultDto runSimulation(RunSimulationRequest request, UUID userId) {
        log.info("Application service: running simulation portfolioId={} userId={}", 
                request.portfolioId(), userId);
        return monteCarloSimulationService.run(request, userId);
    }

    /**
     * Retrieves a previously run simulation by its ID.
     *
     * @param simulationId the simulation ID
     * @param userId the authenticated user's ID
     * @return the simulation result
     */
    public SimulationResultDto getSimulation(UUID simulationId, UUID userId) {
        log.debug("Application service: retrieving simulation id={} userId={}", simulationId, userId);
        return monteCarloSimulationService.get(simulationId, userId);
    }

    /**
     * Lists all simulations for a specific portfolio.
     *
     * @param portfolioId the portfolio ID
     * @param userId the authenticated user's ID
     * @return list of simulation results
     */
    public List<SimulationResultDto> listSimulations(UUID portfolioId, UUID userId) {
        log.debug("Application service: listing simulations portfolioId={} userId={}", portfolioId, userId);
        return monteCarloSimulationService.listForPortfolio(portfolioId, userId);
    }
}
