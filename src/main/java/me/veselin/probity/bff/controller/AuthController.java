package me.veselin.probity.bff.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.veselin.probity.auth.dto.LoginCommand;
import me.veselin.probity.auth.dto.RegisterCommand;
import me.veselin.probity.bff.cookie.CookieService;
import me.veselin.probity.bff.dto.AuthResponse;
import me.veselin.probity.bff.dto.LoginRequest;
import me.veselin.probity.bff.dto.RegisterRequest;
import me.veselin.probity.auth.dto.AuthResult;
import me.veselin.probity.auth.port.AuthCommandPort;
import me.veselin.probity.common.ApiRoutes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiRoutes.Auth.ROOT)
@RequiredArgsConstructor
public class AuthController {

    private final AuthCommandPort authCommandPort;
    private final CookieService cookieService;

    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        authCommandPort.register(
                new RegisterCommand(request.username(), request.email(), request.password())
        );
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletResponse response) {
        AuthResult result = authCommandPort.login(
                new LoginCommand(request.username(), request.password())
        );

        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.buildAccessCookie(result.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.buildRefreshCookie(result.refreshToken()).toString());

        return ResponseEntity.ok(new AuthResponse(request.username(), result.role()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(@CookieValue("refresh_token") String refreshToken,
                                        HttpServletResponse response) {
        AuthResult result = authCommandPort.refresh(refreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.buildAccessCookie(result.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.buildRefreshCookie(result.refreshToken()).toString());  // rotation

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue("refresh_token") String refreshToken,
                                       HttpServletResponse response) {
        authCommandPort.logout(refreshToken);  // blacklist the refresh token, not access token

        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.clearAccessCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.clearRefreshCookie().toString());

        return ResponseEntity.noContent().build();
    }
}
