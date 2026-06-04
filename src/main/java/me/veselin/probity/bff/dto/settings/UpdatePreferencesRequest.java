package me.veselin.probity.bff.dto.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * UI preference defaults — all fields required to prevent partial updates.
 */
public record UpdatePreferencesRequest(
        @NotBlank(message = "Currency is required")
        String defaultCurrency,

        @NotNull(message = "Confidence level is required")
        Integer defaultConfidenceLevel,

        @NotBlank(message = "Time horizon is required")
        String defaultTimeHorizon
) {}
