package me.veselin.probity.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.veselin.probity.auth.domain.User;
import me.veselin.probity.auth.domain.UserPreferences;
import me.veselin.probity.auth.exception.UnauthorizedException;
import me.veselin.probity.auth.jwt.JwtService;
import me.veselin.probity.auth.port.UserSettingsPort;
import me.veselin.probity.auth.repository.UserPreferencesRepository;
import me.veselin.probity.auth.repository.UserRepository;
import me.veselin.probity.bff.dto.settings.*;
import me.veselin.probity.common.exception.ConflictException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Handles all user self-service mutations: profile, password, preferences,
 * session management, and account deletion.
 *
 * Session tracking uses Redis with the key pattern:
 *   session:{userId}:{sessionId}  →  JSON payload (device hint, IP, issued-at)
 *
 * This reuses the existing Redis infrastructure without adding a new table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSettingsService implements UserSettingsPort {

    private static final String SESSION_KEY_PREFIX = "session:";
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final UserRepository userRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    // ─── Profile ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(UUID userId) {
        User user = requireUser(userId);
        return toProfileResponse(user);
    }

    @Transactional
    @CacheEvict(cacheNames = "user", key = "#userId")
    public ProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = requireUser(userId);
        user.updateProfile(request.firstName(), request.lastName());
        userRepository.save(user);
        log.info("Profile updated for user {}", userId);
        return toProfileResponse(user);
    }

    // ─── Password ─────────────────────────────────────────────────────────────

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new ConflictException("New password and confirmation do not match");
        }

        User user = requireUser(userId);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new UnauthorizedException("Current password is incorrect");
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        log.info("Password changed for user {}", userId);
    }

    // ─── Preferences ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PreferencesResponse getPreferences(UUID userId) {
        User user = requireUser(userId);
        UserPreferences prefs = preferencesRepository.findByUserId(userId)
                .orElseGet(() -> UserPreferences.createDefaults(user));
        return toPreferencesResponse(prefs);
    }

    @Transactional
    public PreferencesResponse updatePreferences(UUID userId, UpdatePreferencesRequest request) {
        User user = requireUser(userId);
        UserPreferences prefs = preferencesRepository.findByUserId(userId)
                .orElseGet(() -> UserPreferences.createDefaults(user));

        prefs.update(
                request.defaultCurrency(),
                request.defaultConfidenceLevel(),
                request.defaultTimeHorizon()
        );
        preferencesRepository.save(prefs);
        log.info("Preferences updated for user {}", userId);
        return toPreferencesResponse(prefs);
    }

    // ─── Sessions ─────────────────────────────────────────────────────────────

    /**
     * Returns active sessions stored in Redis for this user.
     * Sessions are written by UserCommandService#login via recordSession().
     */
    public List<SessionResponse> getActiveSessions(UUID userId) {
        String pattern = sessionKeyPrefix(userId) + "*";
        Set<String> keys = jwtService.scanSessionKeys(pattern);
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        List<SessionResponse> sessions = new ArrayList<>();
        for (String key : keys) {
            String payload = jwtService.getRawSessionPayload(key);
            if (payload == null) continue;
            // payload format: "sessionId|deviceHint|ipAddress|issuedAt"
            String[] parts = payload.split("\\|", 4);
            if (parts.length < 4) continue;
            sessions.add(new SessionResponse(parts[0], parts[1], parts[2], parts[3]));
        }
        return sessions;
    }

    public void revokeSession(UUID userId, String sessionId) {
        String key = sessionKey(userId, sessionId);
        boolean deleted = jwtService.deleteSessionKey(key);
        if (!deleted) {
            throw new UnauthorizedException("Session not found or already expired");
        }
        // Also blacklist the refresh token associated with this session
        jwtService.deleteRefreshTokenByJti(sessionId);
        log.info("Session {} revoked for user {}", sessionId, userId);
    }

    // ─── Account Deletion ────────────────────────────────────────────────────

    @Transactional
    @CacheEvict(cacheNames = "user", allEntries = true)
    public void deleteAccount(UUID userId, String currentAccessToken, String currentRefreshToken) {
        User user = requireUser(userId);
        user.softDelete();
        userRepository.save(user);

        // Invalidate all sessions in Redis
        Set<String> sessionKeys = jwtService.scanSessionKeys(sessionKeyPrefix(userId) + "*");
        if (sessionKeys != null) {
            sessionKeys.forEach(jwtService::deleteSessionKey);
        }

        // Invalidate current tokens
        try {
            if (currentRefreshToken != null) {
                var claims = jwtService.extractClaims(currentRefreshToken);
                jwtService.deleteRefreshTokenByJti(claims.getId());
            }
            if (currentAccessToken != null) {
                jwtService.blacklistToken(currentAccessToken);
            }
        } catch (Exception e) {
            log.warn("Token cleanup during account deletion failed for user {}: {}", userId, e.getMessage());
        }

        log.info("Account soft-deleted for user {}", userId);
    }

    // ─── Session key helpers ──────────────────────────────────────────────────

    private String sessionKey(UUID userId, String sessionId) {
        return SESSION_KEY_PREFIX + userId + ":" + sessionId;
    }

    private String sessionKeyPrefix(UUID userId) {
        return SESSION_KEY_PREFIX + userId + ":";
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
    }

    private ProfileResponse toProfileResponse(User user) {
        String memberSince = user.getCreatedAt() != null
                ? ISO_FORMATTER.format(user.getCreatedAt())
                : null;
        return new ProfileResponse(
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                memberSince
        );
    }

    private PreferencesResponse toPreferencesResponse(UserPreferences prefs) {
        return new PreferencesResponse(
                prefs.getDefaultCurrency(),
                prefs.getDefaultConfidenceLevel(),
                prefs.getDefaultTimeHorizon()
        );
    }
}