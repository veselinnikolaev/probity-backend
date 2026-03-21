package me.veselin.probity.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import me.veselin.probity.auth.dto.AuthResult;
import me.veselin.probity.common.ApiRoutes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class RefreshIntegrationTest extends BaseAuthIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void refresh_withMissingBody_returns400() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refresh_withValidRefreshToken_returnsNewAccessToken() throws Exception {
        AuthResult tokens = login();

        mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .cookie(new Cookie("refresh_token", tokens.refreshToken())))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists("access_token"))
                .andExpect(cookie().exists("refresh_token"));
    }

    @Test
    void refresh_rotatesRefreshToken() throws Exception {
        AuthResult tokens = login();

        MvcResult refreshResult = mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .cookie(new Cookie("refresh_token", tokens.refreshToken())))
                .andExpect(status().isNoContent())
                .andReturn();

        String newRefreshToken = Arrays.stream(refreshResult.getResponse().getCookies())
                .filter(c -> "refresh_token".equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst().orElseThrow();

        // old token rejected
        mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .cookie(new Cookie("refresh_token", tokens.refreshToken())))
                .andExpect(status().isUnauthorized());

        // new token works
        mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .cookie(new Cookie("refresh_token", newRefreshToken)))
                .andExpect(status().isOk());
    }

    @Test
    void refresh_withInvalidToken_returns401() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .cookie(new Cookie("refresh_token", "notavalidtoken")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid token"));
    }

    @Test
    void refresh_afterLogout_returns401() throws Exception {
        AuthResult tokens = login();

        mockMvc.perform(post(ApiRoutes.Auth.LOGOUT)
                        .cookie(new Cookie("refresh_token", tokens.refreshToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .cookie(new Cookie("refresh_token", tokens.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid refresh token"));
    }

    @Test
    void refresh_withMissingCookie_returns401() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REFRESH))
                .andExpect(status().isUnauthorized());
    }
}