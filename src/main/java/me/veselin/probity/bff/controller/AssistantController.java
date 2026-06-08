package me.veselin.probity.bff.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.veselin.probity.assistant.port.AssistantPort;
import me.veselin.probity.bff.dto.assistant.ChatRequest;
import me.veselin.probity.bff.dto.assistant.ChatResponse;
import me.veselin.probity.bff.dto.auth.UserPrincipal;
import me.veselin.probity.bff.security.rate_limit.RateLimit;
import me.veselin.probity.common.util.ApiRoutes;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantPort assistantPort;

    /**
     * 10 requests per 60 seconds per IP.
     * Each AI round-trip is expensive — keep this tighter than other endpoints.
     * Tune via @RateLimit parameters; no code change needed for the bucket logic.
     */
    @RateLimit(requests = 10, seconds = 60)
    @PostMapping(ApiRoutes.Assistant.CHAT)
    public ResponseEntity<ChatResponse> chat(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChatRequest request) {

        String response = assistantPort.chat(principal.id(), request.message());
        return ResponseEntity.ok(new ChatResponse(response));
    }
}