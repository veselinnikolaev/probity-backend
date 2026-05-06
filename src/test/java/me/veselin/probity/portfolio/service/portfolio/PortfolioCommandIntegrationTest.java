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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class PortfolioCommandIntegrationTest extends BasePortfolioIntegrationTest {

    // ── POST /portfolios ─────────────────────────────────────────────────────

    @Test
    void createPortfolio_validName_returns201WithLocation() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(withCsrf(post(ApiRoutes.Portfolios.PORTFOLIOS)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "My New Portfolio"}
                                """)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/portfolios/")))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.name").value("My New Portfolio"));
    }

    @Test
    void createPortfolio_duplicateName_returns409() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(withCsrf(post(ApiRoutes.Portfolios.PORTFOLIOS)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Duplicate Portfolio"}
                                """)))
                .andExpect(status().isCreated());

        mockMvc.perform(withCsrf(post(ApiRoutes.Portfolios.PORTFOLIOS)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Duplicate Portfolio"}
                                """)))
                .andExpect(status().isConflict());
    }

    @Test
    void createPortfolio_noAuth_returns401() throws Exception {
        mockMvc.perform(withCsrf(post(ApiRoutes.Portfolios.PORTFOLIOS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name": "Unauthorized Portfolio"}
                            """)))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /portfolios/{id}/positions ──────────────────────────────────────

    @Test
    void addPosition_validTicker_returns201WithLocation() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(withCsrf(post(ApiRoutes.Portfolios.POSITIONS, portfolioId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticker": "AAPL", "quantity": 5}
                                """)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/positions/")))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.ticker").value("AAPL"))
                .andExpect(jsonPath("$.quantity").isNumber());
    }

    @Test
    void addPosition_unknownPortfolioId_returns404() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(withCsrf(post(ApiRoutes.Portfolios.POSITIONS, UUID.randomUUID())
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticker": "AAPL", "quantity": 5}
                                """)))
                .andExpect(status().isNotFound());
    }

    @Test
    void addPosition_noAuth_returns401() throws Exception {
        mockMvc.perform(withCsrf(post(ApiRoutes.Portfolios.POSITIONS, portfolioId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"ticker": "AAPL", "quantity": 5}
                            """)))
                .andExpect(status().isUnauthorized());
    }

    // ── PUT /portfolios/{id}/positions/{positionId} ──────────────────────────

    @Test
    void updatePosition_validQuantity_returns204() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(withCsrf(put(ApiRoutes.Portfolios.POSITION, portfolioId, positionId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 20}
                                """)))
                .andExpect(status().isNoContent());
    }

    @Test
    void updatePosition_unknownPortfolioId_returns404() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(withCsrf(put(ApiRoutes.Portfolios.POSITION, UUID.randomUUID(), positionId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 20}
                                """)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatePosition_unknownPositionId_returns404() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(withCsrf(put(ApiRoutes.Portfolios.POSITION, portfolioId, UUID.randomUUID())
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": 20}
                                """)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatePosition_noAuth_returns401() throws Exception {
        mockMvc.perform(withCsrf(put(ApiRoutes.Portfolios.POSITION, portfolioId, positionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"quantity": 20}
                            """)))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /portfolios/{id}/positions/{positionId} ───────────────────────

    @Test
    void deletePosition_existingPosition_returns204() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(withCsrf(delete(ApiRoutes.Portfolios.POSITION, portfolioId, positionId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletePosition_subsequentGet_reflectsRemoval() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(withCsrf(delete(ApiRoutes.Portfolios.POSITION, portfolioId, positionId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))))
                .andExpect(status().isNoContent());

        // AAPL (positionId) is gone — only GOOGL should remain
        mockMvc.perform(get(ApiRoutes.Portfolios.POSITIONS, portfolioId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ticker").value("GOOGL"));
    }

    @Test
    void deletePosition_unknownPortfolioId_returns404() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(withCsrf(delete(ApiRoutes.Portfolios.POSITION, UUID.randomUUID(), positionId)
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletePosition_unknownPositionId_returns404() throws Exception {
        AuthResult auth = login();

        mockMvc.perform(withCsrf(delete(ApiRoutes.Portfolios.POSITION, portfolioId, UUID.randomUUID())
                        .cookie(new Cookie(Token.ACCESS.getCookieName(), auth.accessToken()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletePosition_noAuth_returns401() throws Exception {
        mockMvc.perform(withCsrf(delete(ApiRoutes.Portfolios.POSITION, portfolioId, positionId)))
                .andExpect(status().isUnauthorized());
    }
}