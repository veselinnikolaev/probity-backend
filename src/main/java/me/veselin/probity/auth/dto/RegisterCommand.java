package me.veselin.probity.auth.dto;

/**
 * Application-layer command carrying registration data into the auth domain.
 */
public record RegisterCommand(String username, String email, String password) {}
