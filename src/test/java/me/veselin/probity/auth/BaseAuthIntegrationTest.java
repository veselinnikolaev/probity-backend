package me.veselin.probity.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import me.veselin.probity.BaseIntegrationTest;
import me.veselin.probity.auth.dto.RegisterCommand;
import me.veselin.probity.auth.enumeration.Token;
import me.veselin.probity.auth.port.AuthCommandPort;
import me.veselin.probity.auth.dto.AuthResult;
import me.veselin.probity.common.util.ApiRoutes;
import me.veselin.probity.common.exception.ConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class BaseAuthIntegrationTest extends BaseIntegrationTest {
    @Autowired AuthCommandPort authCommandPort;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    protected static final String ADMIN_USERNAME = "admin";
    protected static final String ADMIN_EMAIL = "admin@probity.test";
    protected static final String ADMIN_PASSWORD = "Password123!";

    protected AuthResult login() throws Exception {
        MvcResult result = mockMvc.perform(post(ApiRoutes.Auth.LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"identifier": "%s", "password": "%s"}
                                """, ADMIN_USERNAME, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        String username = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("username").asText();
        String role = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("role").asText();
        String accessToken = getCookieValue(result, Token.ACCESS.getCookieName());
        String refreshToken = getCookieValue(result, Token.REFRESH.getCookieName());

        return new AuthResult(username, role, accessToken, refreshToken);
    }

    private String getCookieValue(MvcResult result, String name) {
        return Arrays.stream(result.getResponse().getCookies())
                        .filter(c -> name.equals(c.getName()))
                        .map(Cookie::getValue)
                        .findFirst()
                        .orElse(null);
    }

    @Override
    protected void afterSetUp() {
        try {
            authCommandPort.register(new RegisterCommand(ADMIN_USERNAME, ADMIN_EMAIL, ADMIN_PASSWORD));
        } catch (ConflictException ignored) {
        }
    }
}