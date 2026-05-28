package me.veselin.probity.bff.controller;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.bff.dto.auth.UserPrincipal;
import me.veselin.probity.bff.dto.portfolio.*;
import me.veselin.probity.common.util.ApiRoutes;
import me.veselin.probity.portfolio.dto.*;
import me.veselin.probity.portfolio.service.portfolio.PortfolioCommandService;
import me.veselin.probity.portfolio.service.portfolio.PortfolioQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioQueryService portfolioQueryService;
    private final PortfolioCommandService portfolioCommandService;

    @GetMapping(ApiRoutes.Portfolios.PORTFOLIOS)
    public ResponseEntity<List<PortfolioDto>> getPortfolios(@AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioQueryService.getPortfolios(principal.id()));
    }

    @GetMapping(ApiRoutes.Portfolios.PORTFOLIO)
    public ResponseEntity<PortfolioDto> getPortfolio(@PathVariable UUID id,
                                                     @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioQueryService.getPortfolio(id, principal.id()));
    }

    @GetMapping(ApiRoutes.Portfolios.SUMMARY)
    public ResponseEntity<PortfolioSummaryDto> getSummary(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "90d") String range,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(portfolioQueryService.getSummary(id, range, principal.id()));
    }

    @GetMapping(ApiRoutes.Portfolios.POSITIONS)
    public ResponseEntity<List<PositionDto>> getPositions(@PathVariable UUID id,
                                                          @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioQueryService.getPositions(id, principal.id()));
    }

    @GetMapping(ApiRoutes.Portfolios.COMPOSITION)
    public ResponseEntity<List<CompositionEntryDto>> getComposition(@PathVariable UUID id,
                                                                    @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioQueryService.getComposition(id, principal.id()));
    }

    @GetMapping(ApiRoutes.Portfolios.VOLATILITY)
    public ResponseEntity<List<VolatilityPointDto>> getVolatility(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "90d") String range,
            @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioQueryService.getVolatility(id, range, principal.id()));
    }

    @GetMapping(ApiRoutes.Portfolios.ALERTS)
    public ResponseEntity<List<RiskAlertDto>> getAlerts(@PathVariable UUID id,
                                                        @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioQueryService.getAlerts(id, principal.id()));
    }

    @GetMapping(ApiRoutes.Portfolios.RISK_METRICS)
    public ResponseEntity<RiskMetricsDto> getRiskMetrics(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "90d") String range,
            @AuthenticationPrincipal UserPrincipal principal){

        return ResponseEntity.ok(portfolioQueryService.getRiskMetrics(id, range, principal.id()));
    }

    @GetMapping(ApiRoutes.Portfolios.CORRELATION)
    public ResponseEntity<CorrelationMatrixDto> getCorrelation(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "90d") String range,
            @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioQueryService.getCorrelationMatrix(id, range, principal.id()));
    }

    @GetMapping(ApiRoutes.Portfolios.VAR)
    public ResponseEntity<VaRReportDto> getVaRReport(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0.95") double confidenceLevel,
            @RequestParam(defaultValue = "1") int timeHorizonDays,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(
                portfolioQueryService.getVaRReport(id, confidenceLevel, timeHorizonDays, principal.id())
        );
    }

    @PostMapping(ApiRoutes.Portfolios.PORTFOLIOS)
    public ResponseEntity<PortfolioCreatedDto> create(@RequestBody PortfolioCreateRequest request,
                                                      @AuthenticationPrincipal UserPrincipal userPrincipal,
                                                      UriComponentsBuilder ucb){

        PortfolioCreatedDto created = portfolioCommandService.create(request.name(), userPrincipal.id());

        URI location = ucb.path(ApiRoutes.Portfolios.PORTFOLIO)
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PostMapping(ApiRoutes.Portfolios.POSITIONS)
    public ResponseEntity<PositionCreatedDto> addPosition(
            @PathVariable UUID id,
            @RequestBody PositionCreateRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            UriComponentsBuilder ucb) {

        PositionCreatedDto created = portfolioCommandService.addPosition(id, request, principal.id());

        URI location = ucb.path(ApiRoutes.Portfolios.POSITION)
                .buildAndExpand(id, created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping(ApiRoutes.Portfolios.POSITION)
    public ResponseEntity<Void> updatePosition(
            @PathVariable UUID id,
            @PathVariable UUID positionId,
            @RequestBody PositionUpdateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        portfolioCommandService.updatePosition(id, positionId, request, principal.id());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(ApiRoutes.Portfolios.POSITION)
    public ResponseEntity<Void> deletePosition(
            @PathVariable UUID id,
            @PathVariable UUID positionId,
            @AuthenticationPrincipal UserPrincipal principal) {

        portfolioCommandService.deletePosition(id, positionId, principal.id());
        return ResponseEntity.noContent().build();
    }
}