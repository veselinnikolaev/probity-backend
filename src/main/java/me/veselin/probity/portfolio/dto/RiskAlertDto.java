package me.veselin.probity.portfolio.dto;

public record RiskAlertDto(
        String id,
        String message,
        String severity,
        String timestamp,
        String icon
) {}
