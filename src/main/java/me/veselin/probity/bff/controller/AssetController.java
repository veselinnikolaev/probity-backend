package me.veselin.probity.bff.controller;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.bff.security.rate_limit.RateLimit;
import me.veselin.probity.common.util.ApiRoutes;
import me.veselin.probity.portfolio.dto.AssetSearchResultDto;
import me.veselin.probity.portfolio.service.asset.AssetQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AssetController {

    private final AssetQueryService assetQueryService;

    /**
     * GET /api/v1/assets/search?q=AAPL
     * Phase 1: local Asset table (instant)
     * Phase 2: Yahoo Finance probe if ticker not in DB (handles unknown tickers)
     */
    @GetMapping(ApiRoutes.Assets.SEARCH)
    @RateLimit(requests = 30, seconds = 60)
    public ResponseEntity<List<AssetSearchResultDto>> search(
            @RequestParam(name = "q", defaultValue = "") String q) {

        if (q.isBlank()) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(assetQueryService.search(q));
    }
}


