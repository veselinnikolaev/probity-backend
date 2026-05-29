package me.veselin.probity.bff.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.veselin.probity.bff.dto.auth.UserPrincipal;
import me.veselin.probity.common.util.ApiRoutes;
import me.veselin.probity.bff.dto.simulation.RunSimulationRequest;
import me.veselin.probity.bff.dto.simulation.SimulationResultDto;
import me.veselin.probity.simulation.service.MonteCarloSimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SimulationController {

    private final MonteCarloSimulationService simulationService;

    /**
     * POST /simulations/run
     * Runs a new Monte Carlo simulation and persists the result.
     */
    @PostMapping(ApiRoutes.Simulations.RUN)
    public ResponseEntity<SimulationResultDto> run(
            @Valid @RequestBody RunSimulationRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            UriComponentsBuilder ucb) {

        SimulationResultDto result = simulationService.run(request, principal.id());

        URI location = ucb.path(ApiRoutes.Simulations.SIMULATION)
                .buildAndExpand(result.id())
                .toUri();

        return ResponseEntity.created(location).body(result);
    }

    /**
     * GET /simulations/{id}
     * Fetches a previously run simulation result by its ID.
     */
    @GetMapping(ApiRoutes.Simulations.SIMULATION)
    public ResponseEntity<SimulationResultDto> getSimulation(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(simulationService.get(id, principal.id()));
    }

    /**
     * GET /simulations?portfolioId={portfolioId}
     * Lists all simulations for a portfolio, most recent first.
     */
    @GetMapping(ApiRoutes.Simulations.SIMULATIONS)
    public ResponseEntity<List<SimulationResultDto>> listSimulations(
            @RequestParam UUID portfolioId,
            @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(simulationService.listForPortfolio(portfolioId, principal.id()));
    }
}