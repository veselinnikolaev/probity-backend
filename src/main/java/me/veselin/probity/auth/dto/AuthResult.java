package me.veselin.probity.auth.dto;

public record AuthResult(String username, String role, String accessToken, String refreshToken) {
}
