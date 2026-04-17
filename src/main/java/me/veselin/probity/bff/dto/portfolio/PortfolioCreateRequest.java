package me.veselin.probity.bff.dto.portfolio;

import jakarta.validation.constraints.NotBlank;

public record PortfolioCreateRequest(
        @NotBlank(message = "Name is required") String name,
        String description
) {}
