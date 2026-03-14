package me.veselin.probity.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.veselin.probity.auth.dto.LoginRequest;
import me.veselin.probity.auth.dto.RegisterRequest;
import me.veselin.probity.auth.service.UserCommandService;
import me.veselin.probity.common.ApiRoutes;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiRoutes.Auth.ROOT)
@RequiredArgsConstructor
public class AuthController {

    private final UserCommandService userCommandService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(userCommandService.login(request));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        userCommandService.register(request);
        return ResponseEntity.ok("Registered successfully.");
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(@RequestHeader("Authorization") String authHeader) {
        userCommandService.logout(authHeader.substring(7));
        return ResponseEntity.ok("Logged out successfully");
    }
}
