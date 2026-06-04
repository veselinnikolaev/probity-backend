package me.veselin.probity.bff.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.veselin.probity.auth.dto.LoginCommand;
import me.veselin.probity.auth.dto.RegisterCommand;
import me.veselin.probity.auth.exception.UnauthorizedException;
import me.veselin.probity.bff.cookie.CookieService;
import me.veselin.probity.bff.dto.auth.AuthResponse;
import me.veselin.probity.bff.dto.auth.LoginRequest;
import me.veselin.probity.bff.dto.auth.RegisterRequest;
import me.veselin.probity.auth.dto.AuthResult;
import me.veselin.probity.auth.port.AuthCommandPort;
import me.veselin.probity.bff.security.rate_limit.RateLimit;
import me.veselin.probity.common.util.ApiRoutes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthCommandPort authCommandPort;
    private final CookieService cookieService;
    private final CsrfTokenRepository csrfTokenRepository;

    /**
     * GET /auth/csrf
     * Fetches or generates a CSRF token for the session.
     *
     * @param request HTTP request
     * @param response HTTP response
     * @return empty response with X-XSRF-TOKEN header
     */
    @GetMapping(ApiRoutes.Auth.CSRF)
    public ResponseEntity<Void> csrf(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.loadToken(request);
        if (token == null) {
            token = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(token, request, response); // writes the cookie
        }
        response.setHeader("X-XSRF-TOKEN", token.getToken());
        return ResponseEntity.ok().build();
    }

    /**
     * POST /auth/register
     * Registers a new user account.
     *
     * @param request registration request with username, email, and password
     * @return 201 CREATED on success
     * @throws me.veselin.probity.common.exception.ConflictException if username or email already exists
     */
    @PostMapping(ApiRoutes.Auth.REGISTER)
    @RateLimit(requests = 3, seconds = 60)
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        authCommandPort.register(
                new RegisterCommand(request.username(), request.email(), request.password())
        );
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * POST /auth/login
     * Authenticates a user and issues JWT tokens.
     *
     * @param request login request with identifier (username or email) and password
     * @param response HTTP response for setting auth cookies
     * @return auth response with username and role
     * @throws me.veselin.probity.auth.exception.UnauthorizedException if credentials are invalid
     */
    @PostMapping(ApiRoutes.Auth.LOGIN)
    @RateLimit(requests = 5, seconds = 60)
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletResponse response) {
        AuthResult result = authCommandPort.login(
                new LoginCommand(request.identifier(), request.password())
        );

        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.buildAccessCookie(result.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.buildRefreshCookie(result.refreshToken()).toString());

        return ResponseEntity.ok(new AuthResponse(result.username(), result.role()));
    }

    /**
     * POST /auth/refresh
     * Refreshes access token using a valid refresh token.
     *
     * @param refreshToken refresh token from cookie
     * @param response HTTP response for setting new auth cookies
     * @return auth response with username and role
     * @throws me.veselin.probity.auth.exception.UnauthorizedException if refresh token is invalid or missing
     */
    @PostMapping(ApiRoutes.Auth.REFRESH)
    public ResponseEntity<AuthResponse> refresh(@CookieValue(value = "refresh_token", required = false) String refreshToken,
                                                HttpServletResponse response) {
        if (refreshToken == null) {
            throw new UnauthorizedException("Refresh token is missing");
        }

        AuthResult result = authCommandPort.refresh(refreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.buildAccessCookie(result.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.buildRefreshCookie(result.refreshToken()).toString());  // rotation

        return ResponseEntity.ok(new AuthResponse(result.username(), result.role()));
    }

    /**
     * POST /auth/logout
     * Logs out the user by invalidating tokens and clearing cookies.
     *
     * @param refreshToken refresh token from cookie (optional)
     * @param accessToken access token from cookie (optional)
     * @param response HTTP response for clearing auth cookies
     * @return 204 NO CONTENT
     */
    @PostMapping(ApiRoutes.Auth.LOGOUT)
    public ResponseEntity<Void> logout(
            @CookieValue(value = "refresh_token", required = false) String refreshToken,
            @CookieValue(value = "access_token", required = false) String accessToken,
            HttpServletResponse response) {
        authCommandPort.logout(accessToken, refreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE, cookieService.clearAccessCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookieService.clearRefreshCookie().toString());

        return ResponseEntity.noContent().build();
    }
}