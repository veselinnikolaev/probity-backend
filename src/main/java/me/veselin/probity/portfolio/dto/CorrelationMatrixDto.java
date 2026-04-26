package me.veselin.probity.portfolio.dto;

import java.util.List;

public record CorrelationMatrixDto(
        List<String> assets,
        List<List<Double>> matrix
) {}
