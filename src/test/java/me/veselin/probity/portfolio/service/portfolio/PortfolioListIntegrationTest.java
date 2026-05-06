package me.veselin.probity.portfolio.service.portfolio;

import jakarta.servlet.http.Cookie;
import me.veselin.probity.auth.dto.AuthResult;
import me.veselin.probity.auth.enumeration.Token;
import me.veselin.probity.common.util.ApiRoutes;
import me.veselin.probity.portfolio.BasePortfolioIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class PortfolioListIntegrationTest extends BasePortfolioIntegrationTest {

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
                .andExpect(jsonPath("$[0].totalValue").isNumber())
                .andExpect(jsonPath("$[0].positionsCount").isNumber())
                .andExpect(jsonPath("$[0].sparklineData").isArray());
    }

    @Test
    void getPortfolios_noAuth_returns401() throws Exception {
        mockMvc.perform(get(ApiRoutes.Portfolios.PORTFOLIOS)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /portfolios/{id} ─────────────────────────────────────────────────

    @Test
    void getPortfolio_existingId_returnsPortfolioDto() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(get(ApiRoutes.Portfolios.PORTFOLIO, portfolioId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(portfolioId))
                .andExpect(jsonPath("$.name").value("Test Portfolio"))
                .andExpect(jsonPath("$.totalValue").isNumber())
                .andExpect(jsonPath("$.positionsCount").isNumber())
                .andExpect(jsonPath("$.volatility").isNumber())
                .andExpect(jsonPath("$.sharpeRatio").isNumber())
                .andExpect(jsonPath("$.sparklineData").isArray());
    }

    @Test
    void getPortfolio_unknownId_returns404() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(get(ApiRoutes.Portfolios.PORTFOLIO, UUID.randomUUID())
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPortfolio_noAuth_returns401() throws Exception {
        mockMvc.perform(get(ApiRoutes.Portfolios.PORTFOLIO, portfolioId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
