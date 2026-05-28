package me.veselin.probity.bff.dto.portfolio;

import jakarta.validation.constraints.NotBlank;

/**
 * BFF request DTO for creating a new user portfolio.
 */
public record PortfolioCreateRequest(
        @NotBlank(message = "Name is required") String name,
        String description
) {}
