package me.veselin.probity.bff.controller;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.bff.dto.UserPrincipal;
import me.veselin.probity.bff.dto.portfolio.PortfolioCreateDto;
import me.veselin.probity.bff.dto.portfolio.PortfolioCreatedDto;
import me.veselin.probity.common.util.ApiRoutes;
import me.veselin.probity.portfolio.dto.*;
import me.veselin.probity.portfolio.service.PortfolioCommandService;
import me.veselin.probity.portfolio.service.PortfolioQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioQueryService portfolioQueryService;
    private final PortfolioCommandService portfolioCommandService;

    @GetMapping(ApiRoutes.Portfolios.PORTFOLIOS)
    public ResponseEntity<List<PortfolioDto>> getPortfolios(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(portfolioQueryService.getPortfolios(principal.id()));
    }

    @GetMapping(ApiRoutes.Portfolios.SUMMARY)
    public ResponseEntity<PortfolioSummaryDto> getSummary(
            @PathVariable String id,
            @RequestParam(defaultValue = "90d") String range) {
        System.out.println(range);
        return ResponseEntity.ok(portfolioQueryService.getSummary(id, range));
    }

    @GetMapping(ApiRoutes.Portfolios.POSITIONS)
    public ResponseEntity<List<PositionDto>> getPositions(@PathVariable String id) {
        return ResponseEntity.ok(portfolioQueryService.getPositions(id));
    }

    @GetMapping(ApiRoutes.Portfolios.COMPOSITION)
    public ResponseEntity<List<CompositionEntryDto>> getComposition(@PathVariable String id) {
        return ResponseEntity.ok(portfolioQueryService.getComposition(id));
    }

    @GetMapping(ApiRoutes.Portfolios.VOLATILITY)
    public ResponseEntity<List<VolatilityPointDto>> getVolatility(
            @PathVariable String id,
            @RequestParam(defaultValue = "90d") String range) {
        return ResponseEntity.ok(portfolioQueryService.getVolatility(id, range));
    }

    @GetMapping(ApiRoutes.Portfolios.ALERTS)
    public ResponseEntity<List<RiskAlertDto>> getAlerts(@PathVariable String id) {
        return ResponseEntity.ok(portfolioQueryService.getAlerts(id));
    }

    @PostMapping(ApiRoutes.Portfolios.PORTFOLIOS)
    public ResponseEntity<PortfolioCreatedDto> create(@RequestBody PortfolioCreateDto portfolioCreateDto,
                                                      @AuthenticationPrincipal UserPrincipal userPrincipal){
        return ResponseEntity.ok(portfolioCommandService.create(portfolioCreateDto.name(), userPrincipal.id()));
    }
}