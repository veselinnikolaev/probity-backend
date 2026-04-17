package me.veselin.probity.bff.dto.auth;

import java.util.UUID;

public record UserPrincipal(
        UUID id,
        String username,
        String role
) {}
