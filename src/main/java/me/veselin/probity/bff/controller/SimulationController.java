package me.veselin.probity.bff.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.bff.dto.auth.UserPrincipal;
import me.veselin.probity.bff.util.ConditionalGetSupport;
import me.veselin.probity.common.util.ApiRoutes;
import me.veselin.probity.bff.dto.simulation.RunSimulationRequest;
import me.veselin.probity.bff.dto.simulation.SimulationResponse;
import me.veselin.probity.simulation.dto.SimulationData;
import me.veselin.probity.simulation.port.SimulationPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Tag(name = "Simulations", description = "Monte Carlo portfolio simulations")
@RestController
@RequiredArgsConstructor
@Slf4j
public class SimulationController {

    private final SimulationPort simulationPort;

    /**
     * POST /simulations/run
     * Runs a new Monte Carlo simulation and persists the result.
     */
    @Operation(summary = "Run Monte Carlo simulation")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Simulation created"),
        @ApiResponse(responseCode = "400", description = "Invalid request or empty portfolio"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Portfolio not found")
    })
    @PostMapping(ApiRoutes.Simulations.RUN)
    public ResponseEntity<SimulationResponse> run(
            @Valid @RequestBody RunSimulationRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            UriComponentsBuilder ucb) {

        log.info("Running simulation for portfolio: {}, user: {}, simulations: {}, horizon: {} days, confidence: {}",
                request.portfolioId(), principal.id(), request.numberOfSimulations(), request.timeHorizonDays(), request.confidenceLevel());
        SimulationData data = simulationPort.runSimulation(
                request.portfolioId(),
                request.numberOfSimulations(),
                request.timeHorizonDays(),
                request.confidenceLevel(),
                request.assumedReturnPercent(),
                request.assumedVolatilityPercent(),
                principal.id()
        );

        URI location = ucb.path(ApiRoutes.Simulations.SIMULATION)
                .buildAndExpand(data.id())
                .toUri();

        log.info("Simulation completed successfully: {}, portfolio: {}", data.id(), request.portfolioId());
        return ResponseEntity.created(location).body(mapToResponse(data));
    }

    /**
     * GET /simulations/{id}
     * Fetches a previously run simulation result by its ID.
     */
    @Operation(summary = "Get simulation by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Simulation returned"),
        @ApiResponse(responseCode = "304", description = "Not modified"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Simulation not found")
    })
    @GetMapping(ApiRoutes.Simulations.SIMULATION)
    public ResponseEntity<SimulationResponse> getSimulation(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
            String ifNoneMatch) {

        log.debug("Fetching simulation: {} for user: {}", id, principal.id());
        String etag = "\"" + id + "\"";

        if (etag.equals(ifNoneMatch)) {
            log.debug("Simulation not modified: {}", id);
            return ConditionalGetSupport.notModifiedETag(etag);
        }

        SimulationData data = simulationPort.getSimulation(id, principal.id());
        return ConditionalGetSupport.okImmutable(mapToResponse(data), etag);
    }

    /**
     * GET /simulations?portfolioId={portfolioId}
     * Lists all simulations for a portfolio, most recent first.
     */
    @Operation(summary = "List simulations for portfolio")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Simulations returned"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping(ApiRoutes.Simulations.SIMULATIONS)
    public ResponseEntity<List<SimulationResponse>> listSimulations(
            @RequestParam UUID portfolioId,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.debug("Listing simulations for portfolio: {}, user: {}", portfolioId, principal.id());
        return ResponseEntity.ok(simulationPort.listSimulations(portfolioId, principal.id())
                .stream()
                .map(this::mapToResponse)
                .toList());
    }

    private SimulationResponse mapToResponse(SimulationData data) {
        return new SimulationResponse(
                data.id(),
                data.portfolioId(),
                data.createdAt(),
                new SimulationResponse.Parameters(
                        data.parameters().numberOfSimulations(),
                        data.parameters().timeHorizonDays(),
                        data.parameters().confidenceLevel()
                ),
                data.currentPortfolioValue(),
                data.allPaths(),
                data.percentileSeries(),
                new SimulationResponse.Statistics(
                        data.statistics().expectedFinalValue(),
                        data.statistics().medianFinalValue(),
                        data.statistics().stdDeviation(),
                        data.statistics().minValue(),
                        data.statistics().maxValue()
                ),
                new SimulationResponse.Outcomes(
                        data.outcomes().probabilityOf10PercentLoss(),
                        data.outcomes().probabilityOf20PercentLoss(),
                        data.outcomes().valueAtRisk95(),
                        data.outcomes().conditionalValueAtRisk95()
                ),
                data.distribution()
        );
    }
}