package me.veselin.probity.portfolio.dto;

import java.util.List;

public record RiskAlertDto(
        String id,
        String message,
        String severity,
        String timestamp,
        List<String> affectedAssets
) {}
