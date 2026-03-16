package me.veselin.probity.auth.service;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import me.veselin.probity.auth.domain.User;
import me.veselin.probity.auth.dto.AuthResponse;
import me.veselin.probity.auth.dto.LoginRequest;
import me.veselin.probity.auth.dto.RegisterRequest;
import me.veselin.probity.auth.exception.UnauthorizedException;
import me.veselin.probity.auth.jwt.JwtService;
import me.veselin.probity.auth.repository.UserRepository;
import me.veselin.probity.common.exception.ConflictException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserCommandService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElse(null);

        // always run bcrypt even if user not found — prevents timing attacks
        String hashToCheck = user != null ? user.getPassword() : "$2a$10$dummyhashtopreventtimingattack00000000000000000000000";

        if (user == null || !passwordEncoder.matches(request.password(), hashToCheck)) {
            throw new BadCredentialsException("Invalid credentials");
        }

        String accessToken = jwtService.generateAccessJwt(
                user.getUsername(),
                Map.of("role", user.getRole().name())
        );
        String refreshToken = jwtService.generateRefreshJwt(
                user.getUsername(),
                Map.of("role", user.getRole().name())
        );

        jwtService.saveRefreshToken(refreshToken);
        return new AuthResponse(accessToken, refreshToken);
    }

    public void register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ConflictException("Username already taken");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email already registered");
        }

        String hashedPassword = passwordEncoder.encode(request.password());
        User user = User.create(request.username(), request.email(), hashedPassword);
        userRepository.save(user);
    }

    public void logout(String token) {
        jwtService.blacklistToken(token);
        jwtService.deleteCorrespondingRefreshToken(token);
    }

    public AuthResponse refresh(String incomingRefreshToken) {
        Claims claims = jwtService.extractClaims(incomingRefreshToken);
        String username = claims.getSubject();

        String stored = jwtService.getRefreshToken(username);

        if (stored == null || !stored.equals(incomingRefreshToken)) {
            throw new UnauthorizedException("Invalid refresh token");
        }

        if (!jwtService.isTokenValid(incomingRefreshToken, username)) {
            throw new UnauthorizedException("Refresh token expired");
        }

        String newAccessToken = jwtService.generateAccessJwt(username, Map.of("role", claims.get("role", String.class)));

        // rotation — old refresh token replaced with new one
        String newRefreshToken = jwtService.generateRefreshJwt(username, Map.of("role", claims.get("role", String.class)));
        jwtService.saveRefreshToken(newRefreshToken);

        return new AuthResponse(newAccessToken, newRefreshToken);
    }
}
