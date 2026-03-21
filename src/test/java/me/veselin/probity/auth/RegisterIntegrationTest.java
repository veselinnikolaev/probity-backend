package me.veselin.probity.auth;

import me.veselin.probity.common.ApiRoutes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class RegisterIntegrationTest extends BaseAuthIntegrationTest {

    @Autowired MockMvc mockMvc;

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
                .andExpect(status().isCreated());
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
}