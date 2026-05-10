package me.veselin.probity.bff.dto.simulation;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record RunSimulationRequest(
        @NotNull(message = "portfolioId is required")
        UUID portfolioId,

        @Min(value = 100, message = "numberOfSimulations must be at least 100")
        @Max(value = 10_000, message = "numberOfSimulations must not exceed 10 000")
        int numberOfSimulations,

        @Min(value = 1,   message = "timeHorizonDays must be at least 1")
        @Max(value = 1260, message = "timeHorizonDays must not exceed 1260 (5 years)")
        int timeHorizonDays,

        @DecimalMin(value = "0.80", message = "confidenceLevel must be at least 0.80")
        @DecimalMax(value = "0.99", message = "confidenceLevel must not exceed 0.99")
        double confidenceLevel,

        // Optional overrides — when null the service derives them from market data
        @DecimalMin(value = "-50.0", message = "assumedReturnPercent must be ≥ -50")
        @DecimalMax(value = "100.0",  message = "assumedReturnPercent must be ≤ 100")
        Double assumedReturnPercent,

        @DecimalMin(value = "1.0",  message = "assumedVolatilityPercent must be ≥ 1")
        @DecimalMax(value = "200.0", message = "assumedVolatilityPercent must be ≤ 200")
        Double assumedVolatilityPercent
) {}

