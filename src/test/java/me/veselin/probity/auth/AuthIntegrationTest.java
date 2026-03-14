package me.veselin.probity.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.veselin.probity.BaseIntegrationTest;
import me.veselin.probity.common.ApiRoutes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AuthIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String loginAndGetToken() throws Exception {
        String response = mockMvc.perform(post(ApiRoutes.Auth.LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "admin", "password": "Password123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("token").asText();
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void login_withValidCredentials_returnsJwt() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "admin", "password": "Password123!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.token").isString());
    }

    @Test
    void login_withWrongPassword_returns401() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "admin", "password": "WrongPass123!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid credentials"));
    }

    @Test
    void login_withNonExistentUser_returns401() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "ghost", "password": "Password123!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid credentials"));
    }

    @Test
    void login_withBlankUsername_returns400() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "", "password": "Password123!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.username").exists());
    }

    @Test
    void login_withBlankPassword_returns400() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "admin", "password": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    @Test
    void login_withMissingBody_returns400() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields").exists());
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    void register_withValidData_returns200() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "newuser",
                                    "email": "newuser@probity.test",
                                    "password": "Password123!"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void register_withDuplicateUsername_returns409() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "admin",
                                    "email": "other@probity.test",
                                    "password": "Password123!"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username already taken"));
    }

    @Test
    void register_withDuplicateEmail_returns409() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "otheradmin",
                                    "email": "admin@probity.test",
                                    "password": "Password123!"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Email already registered"));
    }

    @Test
    void register_withInvalidEmail_returns400() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "newuser",
                                    "email": "notanemail",
                                    "password": "Password123!"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists());
    }

    @Test
    void register_withWeakPassword_returns400() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "newuser",
                                    "email": "newuser@probity.test",
                                    "password": "weakpassword"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    @Test
    void register_withInvalidUsername_returns400() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "invalid user!",
                                    "email": "newuser@probity.test",
                                    "password": "Password123!"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.username").exists());
    }

    @Test
    void register_withUsernameTooShort_returns400() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "ab",
                                    "email": "newuser@probity.test",
                                    "password": "Password123!"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.username").exists());
    }

    @Test
    void register_withMissingBody_returns400() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields").exists());
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_withValidToken_returns200() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(post(ApiRoutes.Auth.LOGOUT)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void logout_blacklistedToken_returns401OnNextRequest() throws Exception {
        String token = loginAndGetToken();

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
        String token = loginAndGetToken();

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