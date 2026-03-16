package me.veselin.probity.auth;

import me.veselin.probity.common.ApiRoutes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class LoginIntegrationTest extends BaseAuthIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void login_withValidCredentials_returnsJwt() throws Exception {
        mockMvc.perform(post(ApiRoutes.Auth.LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "admin", "password": "Password123!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.refreshToken").isString());
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
}
