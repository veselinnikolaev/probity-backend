package me.veselin.probity.risk.dto;

public record DistributionStatistics(
        double mean,
        double median,
        double stdDeviation,
        double min,
        double max
) {}

