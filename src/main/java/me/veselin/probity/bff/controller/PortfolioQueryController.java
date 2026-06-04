package me.veselin.probity.bff.controller;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.bff.dto.auth.UserPrincipal;
import me.veselin.probity.common.util.ApiRoutes;
import me.veselin.probity.portfolio.dto.CompositionEntryDto;
import me.veselin.probity.portfolio.dto.CorrelationMatrixDto;
import me.veselin.probity.portfolio.dto.PortfolioDto;
import me.veselin.probity.portfolio.dto.PortfolioSummaryDto;
import me.veselin.probity.portfolio.dto.PositionDto;
import me.veselin.probity.portfolio.dto.RiskAlertDto;
import me.veselin.probity.portfolio.dto.RiskMetricsDto;
import me.veselin.probity.portfolio.dto.VaRReportDto;
import me.veselin.probity.portfolio.dto.VolatilityPointDto;
import me.veselin.probity.portfolio.port.portfolio.PortfolioDashboardQueryPort;
import me.veselin.probity.portfolio.port.portfolio.PortfolioQueryPort;
import me.veselin.probity.portfolio.port.portfolio.PortfolioRiskQueryPort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PortfolioQueryController {

    private final PortfolioQueryPort portfolioQueryPort;
    private final PortfolioDashboardQueryPort portfolioDashboardQueryPort;
    private final PortfolioRiskQueryPort portfolioRiskQueryPort;

    /**
     * GET /portfolios
     * Lists all portfolios for the authenticated user.
     *
     * @param principal authenticated user principal
     * @return list of portfolio DTOs
     */
    @GetMapping(ApiRoutes.Portfolios.PORTFOLIOS)
    public ResponseEntity<List<PortfolioDto>> getPortfolios(@AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioQueryPort.getPortfolios(principal.id()));
    }

    /**
     * GET /portfolios/{id}
     * Fetches a specific portfolio by ID.
     *
     * @param id portfolio UUID
     * @param principal authenticated user principal
     * @return portfolio DTO
     * @throws me.veselin.probity.portfolio.exception.PortfolioNotFoundException if portfolio not found
     */
    @GetMapping(ApiRoutes.Portfolios.PORTFOLIO)
    public ResponseEntity<PortfolioDto> getPortfolio(@PathVariable UUID id,
                                                     @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioQueryPort.getPortfolio(id, principal.id()));
    }

    /**
     * GET /portfolios/{id}/summary
     * Fetches portfolio summary with performance metrics over the specified range.
     *
     * @param id portfolio UUID
     * @param range time range (e.g., "7d", "30d", "90d", "1y")
     * @param principal authenticated user principal
     * @return portfolio summary DTO
     */
    @GetMapping(ApiRoutes.Portfolios.SUMMARY)
    public ResponseEntity<PortfolioSummaryDto> getSummary(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "90d") String range,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(portfolioDashboardQueryPort.getSummary(id, range, principal.id()));
    }

    /**
     * GET /portfolios/{id}/positions
     * Lists all positions in a portfolio.
     *
     * @param id portfolio UUID
     * @param principal authenticated user principal
     * @return list of position DTOs
     */
    @GetMapping(ApiRoutes.Portfolios.POSITIONS)
    public ResponseEntity<List<PositionDto>> getPositions(@PathVariable UUID id,
                                                          @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioDashboardQueryPort.getPositions(id, principal.id()));
    }

    /**
     * GET /portfolios/{id}/composition
     * Fetches portfolio composition breakdown by asset.
     *
     * @param id portfolio UUID
     * @param principal authenticated user principal
     * @return list of composition entry DTOs
     */
    @GetMapping(ApiRoutes.Portfolios.COMPOSITION)
    public ResponseEntity<List<CompositionEntryDto>> getComposition(@PathVariable UUID id,
                                                                    @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioDashboardQueryPort.getComposition(id, principal.id()));
    }

    /**
     * GET /portfolios/{id}/volatility
     * Fetches rolling volatility points for the portfolio over the specified range.
     *
     * @param id portfolio UUID
     * @param range time range (e.g., "7d", "30d", "90d", "1y")
     * @param principal authenticated user principal
     * @return list of volatility point DTOs
     */
    @GetMapping(ApiRoutes.Portfolios.VOLATILITY)
    public ResponseEntity<List<VolatilityPointDto>> getVolatility(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "90d") String range,
            @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioRiskQueryPort.getVolatility(id, range, principal.id()));
    }

    /**
     * GET /portfolios/{id}/alerts
     * Fetches risk alerts for the portfolio.
     *
     * @param id portfolio UUID
     * @param principal authenticated user principal
     * @return list of risk alert DTOs
     */
    @GetMapping(ApiRoutes.Portfolios.ALERTS)
    public ResponseEntity<List<RiskAlertDto>> getAlerts(@PathVariable UUID id,
                                                         @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioDashboardQueryPort.getAlerts(id, principal.id()));
    }

    /**
     * GET /portfolios/{id}/risk-metrics
     * Fetches risk metrics (volatility, Sharpe ratio, max drawdown, etc.) over the specified range.
     *
     * @param id portfolio UUID
     * @param range time range (e.g., "7d", "30d", "90d", "1y")
     * @param principal authenticated user principal
     * @return risk metrics DTO
     */
    @GetMapping(ApiRoutes.Portfolios.RISK_METRICS)
    public ResponseEntity<RiskMetricsDto> getRiskMetrics(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "90d") String range,
            @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioRiskQueryPort.getRiskMetrics(id, range, principal.id()));
    }

    /**
     * GET /portfolios/{id}/correlation
     * Fetches the correlation matrix between portfolio assets over the specified range.
     *
     * @param id portfolio UUID
     * @param range time range (e.g., "7d", "30d", "90d", "1y")
     * @param principal authenticated user principal
     * @return correlation matrix DTO
     */
    @GetMapping(ApiRoutes.Portfolios.CORRELATION)
    public ResponseEntity<CorrelationMatrixDto> getCorrelation(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "90d") String range,
            @AuthenticationPrincipal UserPrincipal principal) {

        return ResponseEntity.ok(portfolioRiskQueryPort.getCorrelationMatrix(id, range, principal.id()));
    }

    /**
     * GET /portfolios/{id}/var
     * Fetches Value at Risk (VaR) report for the portfolio.
     *
     * @param id portfolio UUID
     * @param confidenceLevel confidence level for VaR (e.g., 0.95 for 95%)
     * @param timeHorizonDays time horizon in days
     * @param principal authenticated user principal
     * @return VaR report DTO
     */
    @GetMapping(ApiRoutes.Portfolios.VAR)
    public ResponseEntity<VaRReportDto> getVaRReport(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0.95") double confidenceLevel,
            @RequestParam(defaultValue = "1") int timeHorizonDays,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(
                portfolioRiskQueryPort.getVaRReport(id, confidenceLevel, timeHorizonDays, principal.id())
        );
    }
}