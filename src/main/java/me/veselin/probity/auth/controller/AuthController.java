package me.veselin.probity.auth.controller;

import lombok.RequiredArgsConstructor;
import me.veselin.probity.auth.dto.LoginRequest;
import me.veselin.probity.auth.dto.RegisterRequest;
import me.veselin.probity.auth.service.UserCommandService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserCommandService userCommandService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(userCommandService.login(request));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        userCommandService.register(request);
        return ResponseEntity.ok("Registered successfully.");
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body("Missing token");
        }

        String token = authHeader.substring(7);

        userCommandService.logout(token);


        return ResponseEntity.ok("Logged out successfully");
    }
}
