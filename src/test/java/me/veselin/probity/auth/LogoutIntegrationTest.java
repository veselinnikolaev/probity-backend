package me.veselin.probity.auth;

import me.veselin.probity.common.ApiRoutes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class LogoutIntegrationTest extends BaseAuthIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void logout_withValidToken_returns200() throws Exception {
        String token = login().accessToken();

        mockMvc.perform(post(ApiRoutes.Auth.LOGOUT)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void logout_blacklistedToken_returns401OnNextRequest() throws Exception {
        String token = login().accessToken();

        mockMvc.perform(post(ApiRoutes.Auth.LOGOUT)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get(ApiRoutes.Users.ME)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Token has been revoked"));
    }

    @Test
    void logout_blacklistedToken_cannotLogoutAgain() throws Exception {
        String token = login().accessToken();

        mockMvc.perform(post(ApiRoutes.Auth.LOGOUT)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post(ApiRoutes.Auth.LOGOUT)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Token has been revoked"));
    }

    @Test
    void logout_withNoToken_returns401() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.LOGOUT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_withInvalidToken_returns401() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.LOGOUT)
                        .header("Authorization", "Bearer totallynotavalidtoken"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_withMalformedAuthHeader_returns401() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.LOGOUT)
                        .header("Authorization", "NotBearer token"))
                .andExpect(status().isUnauthorized());
    }
}