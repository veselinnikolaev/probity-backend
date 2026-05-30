package me.veselin.probity.bff.controller;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.bff.dto.auth.UserPrincipal;
import me.veselin.probity.bff.dto.portfolio.PortfolioCreateRequest;
import me.veselin.probity.bff.dto.portfolio.PortfolioCreatedDto;
import me.veselin.probity.bff.dto.portfolio.PositionCreateRequest;
import me.veselin.probity.bff.dto.portfolio.PositionCreatedDto;
import me.veselin.probity.bff.dto.portfolio.PositionUpdateRequest;
import me.veselin.probity.common.util.ApiRoutes;
import me.veselin.probity.portfolio.port.portfolio.PortfolioCommandPort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PortfolioCommandController {

    private final PortfolioCommandPort portfolioCommandPort;

    @PostMapping(ApiRoutes.Portfolios.PORTFOLIOS)
    public ResponseEntity<PortfolioCreatedDto> create(@RequestBody PortfolioCreateRequest request,
                                                      @AuthenticationPrincipal UserPrincipal userPrincipal,
                                                      UriComponentsBuilder ucb) {

        PortfolioCreatedDto created = portfolioCommandPort.create(request.name(), userPrincipal.id());

        URI location = ucb.path(ApiRoutes.Portfolios.PORTFOLIO)
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PostMapping(ApiRoutes.Portfolios.POSITIONS)
    public ResponseEntity<PositionCreatedDto> addPosition(
            @PathVariable UUID id,
            @RequestBody PositionCreateRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            UriComponentsBuilder ucb) {

        PositionCreatedDto created = portfolioCommandPort.addPosition(id, request, principal.id());

        URI location = ucb.path(ApiRoutes.Portfolios.POSITION)
                .buildAndExpand(id, created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping(ApiRoutes.Portfolios.POSITION)
    public ResponseEntity<Void> updatePosition(
            @PathVariable UUID id,
            @PathVariable UUID positionId,
            @RequestBody PositionUpdateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        portfolioCommandPort.updatePosition(id, positionId, request, principal.id());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(ApiRoutes.Portfolios.POSITION)
    public ResponseEntity<Void> deletePosition(
            @PathVariable UUID id,
            @PathVariable UUID positionId,
            @AuthenticationPrincipal UserPrincipal principal) {

        portfolioCommandPort.deletePosition(id, positionId, principal.id());
        return ResponseEntity.noContent().build();
    }
}