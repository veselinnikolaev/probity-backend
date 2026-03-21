package me.veselin.probity.auth.dto;

public record AuthResult(String accessToken, String refreshToken, String role) {
}
