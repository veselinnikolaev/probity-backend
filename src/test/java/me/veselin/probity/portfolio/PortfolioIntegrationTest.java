package me.veselin.probity.portfolio;

import jakarta.servlet.http.Cookie;
import me.veselin.probity.auth.BaseAuthIntegrationTest;
import me.veselin.probity.auth.domain.User;
import me.veselin.probity.auth.dto.AuthResult;
import me.veselin.probity.auth.enumeration.Token;
import me.veselin.probity.auth.repository.UserRepository;
import me.veselin.probity.common.util.ApiRoutes;
import me.veselin.probity.marketdata.domain.PriceBar;
import me.veselin.probity.marketdata.port.MarketDataPort;
import me.veselin.probity.portfolio.domain.Asset;
import me.veselin.probity.portfolio.domain.Portfolio;
import me.veselin.probity.portfolio.enumeration.AssetType;
import me.veselin.probity.portfolio.enumeration.DateRange;
import me.veselin.probity.portfolio.enumeration.Sector;
import me.veselin.probity.portfolio.repository.AssetRepository;
import me.veselin.probity.portfolio.repository.PortfolioRepository;
import me.veselin.probity.risk.enumeration.RiskLevel;
import me.veselin.probity.risk.port.RiskPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
public class PortfolioIntegrationTest extends BaseAuthIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired UserRepository userRepository;

    @MockitoBean MarketDataPort marketDataPort;
    @MockitoBean RiskPort riskPort;

    private String portfolioId;

    @BeforeEach
    void seedPortfolio() {
        Asset apple = assetRepository.save(Asset.create("AAPL", "Apple Inc.", Sector.TECHNOLOGY, AssetType.STOCK));

        UUID userId = userRepository.findByUsername(ADMIN_USERNAME)
                .map(User::getId)
                .orElseThrow();

        Portfolio portfolio = Portfolio.create("Test Portfolio", userId);
        portfolio.addPosition(apple, new BigDecimal("10"), new BigDecimal("148.00"));
        portfolioRepository.save(portfolio);

        portfolioId = portfolio.getId().toString();

        List<PriceBar> stubBars = List.of(
                PriceBar.from("AAPL", LocalDate.now().minusDays(2),
                        new BigDecimal("147.00"), new BigDecimal("149.00"),
                        new BigDecimal("146.00"), new BigDecimal("148.00"),
                        new BigDecimal("148.00"), 1_200_000L),
                PriceBar.from("AAPL", LocalDate.now().minusDays(1),
                        new BigDecimal("148.00"), new BigDecimal("150.00"),
                        new BigDecimal("147.00"), new BigDecimal("149.00"),
                        new BigDecimal("149.00"), 1_300_000L),
                PriceBar.from("AAPL", LocalDate.now(),
                        new BigDecimal("149.00"), new BigDecimal("151.00"),
                        new BigDecimal("148.00"), new BigDecimal("150.00"),
                        new BigDecimal("150.00"), 1_500_000L)
        );

        when(marketDataPort.getLatestPrice(anyString()))
                .thenReturn(new BigDecimal("150.00"));
        when(marketDataPort.getHistoricalBars(anyString(), any(), any()))
                .thenReturn(stubBars);

        // Stable deterministic values so assertions never depend on floating-point variance.
        when(riskPort.annualisedVolatility(any())).thenReturn(0.18);
        when(riskPort.sharpeRatio(any())).thenReturn(1.2);
        when(riskPort.var95(anyDouble(), any())).thenReturn(220.0);
        when(riskPort.rollingVolatility(any(), anyInt())).thenReturn(List.of(0.15, 0.16, 0.17));
        when(riskPort.toDailyReturns(any())).thenReturn(List.of(0.01, -0.005, 0.008));
        when(riskPort.riskScore(anyString(), anyDouble())).thenReturn(55);
        when(riskPort.riskLevel(anyInt())).thenReturn(RiskLevel.MODERATE.name());
    }

    // ── GET /portfolios ──────────────────────────────────────────────────────

    @Test
    void getPortfolios_authenticated_returnsPortfolioList() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(get(ApiRoutes.Portfolios.PORTFOLIOS)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].id").value(portfolioId))
                .andExpect(jsonPath("$[0].name").value("Test Portfolio"))
                .andExpect(jsonPath("$[0].totalValue").isNumber());
    }

    @Test
    void getPortfolios_noAuth_returns401() throws Exception {
        mockMvc.perform(get(ApiRoutes.Portfolios.PORTFOLIOS)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /portfolios/{id}/summary ─────────────────────────────────────────

    @Test
    void getSummary_defaultRange_returnsAllFields() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(get(ApiRoutes.Portfolios.SUMMARY, portfolioId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalValue").isNumber()) // Changed from currentValue
                .andExpect(jsonPath("$.totalValueDelta").isNumber()) // Changed from totalDeltaPct
                .andExpect(jsonPath("$.dailyReturn").isNumber())
                .andExpect(jsonPath("$.volatility").isNumber())
                .andExpect(jsonPath("$.sharpeRatio").isNumber())
                .andExpect(jsonPath("$.var95").isNumber())
                .andExpect(jsonPath("$.sparklines").exists()); // Changed from sparkline
    }

    @Test
    void getSummary_explicitRange_returns200() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(get(ApiRoutes.Portfolios.SUMMARY, portfolioId)
                        .param("range", DateRange.THIRTY_DAYS.getValue())
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalValue").isNumber());
    }

    @Test
    void getSummary_unknownId_returns404() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(get(ApiRoutes.Portfolios.SUMMARY, UUID.randomUUID())
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void getSummary_noAuth_returns401() throws Exception {
        mockMvc.perform(get(ApiRoutes.Portfolios.SUMMARY, portfolioId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /portfolios/{id}/positions ───────────────────────────────────────

    @Test
    void getPositions_seededPortfolio_returnsOnePosition() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(get(ApiRoutes.Portfolios.POSITIONS, portfolioId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$[0].name").value("Apple Inc."))
                .andExpect(jsonPath("$[0].assetType").value(AssetType.STOCK.name()))
                .andExpect(jsonPath("$[0].price", closeTo(150.0, 0.01)))
                .andExpect(jsonPath("$[0].riskScore").value(55))
                .andExpect(jsonPath("$[0].riskLevel").value(RiskLevel.MODERATE.name()))
                .andExpect(jsonPath("$[0].sector").value("Technology"));
    }

    @Test
    void getPositions_unknownId_returns404() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(get(ApiRoutes.Portfolios.POSITIONS, UUID.randomUUID())
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPositions_noAuth_returns401() throws Exception {
        mockMvc.perform(get(ApiRoutes.Portfolios.POSITIONS, portfolioId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /portfolios/{id}/composition ─────────────────────────────────────

    @Test
    void getComposition_singleSector_returns100Percent() throws Exception {
        AuthResult auth = login();

        // Single AAPL position in "Technology" → one entry at 100%
        mockMvc.perform(get(ApiRoutes.Portfolios.COMPOSITION, portfolioId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("Technology"))
                .andExpect(jsonPath("$[0].value", closeTo(100.0, 0.01)))
                .andExpect(jsonPath("$[0].color").value("#3B82F6")); // SECTOR_COLORS.get("Technology")
    }

    @Test
    void getComposition_noAuth_returns401() throws Exception {
        mockMvc.perform(get(ApiRoutes.Portfolios.COMPOSITION, portfolioId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /portfolios/{id}/volatility ──────────────────────────────────────

    @Test
    void getVolatility_defaultRange_returnsDateKeyedSeries() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(get(ApiRoutes.Portfolios.VOLATILITY, portfolioId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].date").isString())
                .andExpect(jsonPath("$[0].value").isNumber());
    }

    @Test
    void getVolatility_explicitRange_returns200() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(get(ApiRoutes.Portfolios.VOLATILITY, portfolioId)
                        .param("range", DateRange.ONE_YEAR.getValue())
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void getVolatility_noAuth_returns401() throws Exception {
        mockMvc.perform(get(ApiRoutes.Portfolios.VOLATILITY,portfolioId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /portfolios/{id}/alerts ──────────────────────────────────────────

    @Test
    void getAlerts_returnsAlertListWithRequiredFields() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(get(ApiRoutes.Portfolios.ALERTS, portfolioId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].id").isString())
                .andExpect(jsonPath("$[0].message").isString())
                .andExpect(jsonPath("$[0].severity").isString());
    }

    @Test
    void getAlerts_singleSectorAt100Percent_firesConcentrationAlert() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(get(ApiRoutes.Portfolios.ALERTS, portfolioId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // 1. Verify that there is at least one high severity alert
                .andExpect(jsonPath("$[?(@.severity == 'high')]", hasSize(greaterThanOrEqualTo(1))))
                // 2. Extract all messages for high alerts and check if any contain "Technology"
                .andExpect(jsonPath("$[?(@.severity == 'high')].message",
                        hasItem(containsString("Technology"))));
    }

    @Test
    void getAlerts_noAuth_returns401() throws Exception {
        mockMvc.perform(get(ApiRoutes.Portfolios.ALERTS, portfolioId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}