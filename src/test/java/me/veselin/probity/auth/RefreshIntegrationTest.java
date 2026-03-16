package me.veselin.probity.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.veselin.probity.common.ApiRoutes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class RefreshIntegrationTest extends BaseAuthIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void refresh_withValidRefreshToken_returnsNewAccessToken() throws Exception {
        String refreshToken = login().refreshToken();

        mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void refresh_rotatesRefreshToken() throws Exception {
        String refreshToken = login().refreshToken();

        String refreshResponse = mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String newRefreshToken = objectMapper.readTree(refreshResponse).get("refreshToken").asText();

        // old token must be rejected
        mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized());

        // new token must still work
        mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(newRefreshToken)))
                .andExpect(status().isOk());
    }

    @Test
    void refresh_withInvalidToken_returns401() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "notavalidtoken"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid token"));
    }

    @Test
    void refresh_afterLogout_returns401() throws Exception {
        TokenPair tokens = login();

        mockMvc.perform(post(ApiRoutes.Auth.LOGOUT)
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(tokens.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid refresh token"));
    }

    @Test
    void refresh_withMissingBody_returns400() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}