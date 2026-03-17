package me.veselin.probity.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.veselin.probity.BaseIntegrationTest;
import me.veselin.probity.auth.dto.RegisterRequest;
import me.veselin.probity.auth.service.UserCommandService;
import me.veselin.probity.common.ApiRoutes;
import me.veselin.probity.common.exception.ConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class BaseAuthIntegrationTest extends BaseIntegrationTest {
    @Autowired
    UserCommandService userCommandService;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    protected record TokenPair(String accessToken, String refreshToken) {
    }

    protected TokenPair login() throws Exception {
        String response = mockMvc.perform(post(ApiRoutes.Auth.LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "admin", "password": "Password123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var tree = objectMapper.readTree(response);
        return new TokenPair(
                tree.get("accessToken").asText(),
                tree.get("refreshToken").asText()
        );
    }

    @Override
    protected void afterSetUp() {
        try {
            userCommandService.register(new RegisterRequest("admin", "admin@probity.test", "Password123!"));
        } catch (ConflictException ignored) {
        }
    }
}