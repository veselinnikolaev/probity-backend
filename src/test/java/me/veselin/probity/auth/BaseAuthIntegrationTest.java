package me.veselin.probity.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import me.veselin.probity.BaseIntegrationTest;
import me.veselin.probity.auth.dto.RegisterCommand;
import me.veselin.probity.auth.port.AuthCommandPort;
import me.veselin.probity.auth.dto.AuthResult;
import me.veselin.probity.common.ApiRoutes;
import me.veselin.probity.common.exception.ConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class BaseAuthIntegrationTest extends BaseIntegrationTest {
    @Autowired
    AuthCommandPort authCommandPort;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    protected AuthResult login() throws Exception {
        MvcResult result = mockMvc.perform(post(ApiRoutes.Auth.LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"username": "admin", "password": "Password123!"}
                            """))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = getCookieValue(result, "access_token");
        String refreshToken = getCookieValue(result, "refresh_token");
        String role = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("role").asText();

        return new AuthResult(accessToken, refreshToken, role);
    }

    private String getCookieValue(MvcResult result, String name) {
        return result.getResponse().getCookies() == null ? null :
                Arrays.stream(result.getResponse().getCookies())
                        .filter(c -> name.equals(c.getName()))
                        .map(Cookie::getValue)
                        .findFirst()
                        .orElse(null);
    }

    @Override
    protected void afterSetUp() {
        try {
            authCommandPort.register(new RegisterCommand("admin", "admin@probity.test", "Password123!"));
        } catch (ConflictException ignored) {
        }
    }
}