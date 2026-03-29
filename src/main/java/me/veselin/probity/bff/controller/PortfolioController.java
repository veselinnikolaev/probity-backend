package me.veselin.probity.bff.controller;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.bff.dto.UserPrincipal;
import me.veselin.probity.common.util.ApiRoutes;
import me.veselin.probity.portfolio.dto.*;
import me.veselin.probity.portfolio.service.PortfolioQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping(ApiRoutes.Portfolios.PORTFOLIOS)
public class PortfolioController {

    private final PortfolioQueryService portfolioQueryService;

    @GetMapping
    public ResponseEntity<List<PortfolioDto>> getPortfolios(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(portfolioQueryService.getPortfolios(principal.id()));
    }

    @GetMapping("/{id}/summary")
    public ResponseEntity<PortfolioSummaryDto> getSummary(
            @PathVariable String id,
            @RequestParam(defaultValue = "90d") String range) {
        return ResponseEntity.ok(portfolioQueryService.getSummary(id, range));
    }

    @GetMapping("/{id}/positions")
    public ResponseEntity<List<PositionDto>> getPositions(@PathVariable String id) {
        return ResponseEntity.ok(portfolioQueryService.getPositions(id));
    }

    @GetMapping("/{id}/composition")
    public ResponseEntity<List<CompositionEntryDto>> getComposition(@PathVariable String id) {
        return ResponseEntity.ok(portfolioQueryService.getComposition(id));
    }

    @GetMapping("/{id}/volatility")
    public ResponseEntity<List<VolatilityPointDto>> getVolatility(
            @PathVariable String id,
            @RequestParam(defaultValue = "90d") String range) {
        return ResponseEntity.ok(portfolioQueryService.getVolatility(id, range));
    }

    @GetMapping("/{id}/alerts")
    public ResponseEntity<List<RiskAlertDto>> getAlerts(@PathVariable String id) {
        return ResponseEntity.ok(portfolioQueryService.getAlerts(id));
    }
}