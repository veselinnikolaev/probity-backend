package me.veselin.probity.bff.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.veselin.probity.auth.port.UserSettingsPort;
import me.veselin.probity.bff.dto.auth.UserPrincipal;
import me.veselin.probity.bff.dto.settings.*;
import me.veselin.probity.common.util.ApiRoutes;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Self-service settings endpoints. All mutations are scoped to the
 * authenticated user — no admin privilege escalation possible here.
 */
@RestController
@RequiredArgsConstructor
public class UserSettingsController {

    private final UserSettingsPort settingsPort;

    /**
     * GET /users/me
     * Fetches the authenticated user's profile.
     *
     * @param principal authenticated user principal
     * @return profile response
     */
    @GetMapping(ApiRoutes.Users.ME)
    public ResponseEntity<ProfileResponse> getProfile(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(settingsPort.getProfile(principal.id()));
    }

    /**
     * PATCH /users/me
     * Updates the authenticated user's profile.
     *
     * @param principal authenticated user principal
     * @param request profile update request
     * @return updated profile response
     */
    @PatchMapping(ApiRoutes.Users.ME)
    public ResponseEntity<ProfileResponse> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(settingsPort.updateProfile(principal.id(), request));
    }

    /**
     * PUT /users/me/password
     * Changes the authenticated user's password.
     *
     * @param principal authenticated user principal
     * @param request password change request
     * @return 204 NO CONTENT
     * @throws me.veselin.probity.auth.exception.UnauthorizedException if current password is invalid
     */
    @PutMapping(ApiRoutes.Users.PASSWORD)
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        settingsPort.changePassword(principal.id(), request);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /users/me/preferences
     * Fetches the authenticated user's preferences.
     *
     * @param principal authenticated user principal
     * @return preferences response
     */
    @GetMapping(ApiRoutes.Users.PREFERENCES)
    public ResponseEntity<PreferencesResponse> getPreferences(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(settingsPort.getPreferences(principal.id()));
    }

    /**
     * PUT /users/me/preferences
     * Updates the authenticated user's preferences.
     *
     * @param principal authenticated user principal
     * @param request preferences update request
     * @return updated preferences response
     */
    @PutMapping(ApiRoutes.Users.PREFERENCES)
    public ResponseEntity<PreferencesResponse> updatePreferences(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdatePreferencesRequest request) {
        return ResponseEntity.ok(settingsPort.updatePreferences(principal.id(), request));
    }

    /**
     * GET /users/me/sessions
     * Lists all active sessions for the authenticated user.
     *
     * @param principal authenticated user principal
     * @return list of session responses
     */
    @GetMapping(ApiRoutes.Users.SESSIONS)
    public ResponseEntity<List<SessionResponse>> getSessions(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(settingsPort.getActiveSessions(principal.id()));
    }

    /**
     * DELETE /users/me/sessions/{sessionId}
     * Revokes a specific session for the authenticated user.
     *
     * @param principal authenticated user principal
     * @param sessionId session ID to revoke
     * @return 204 NO CONTENT
     */
    @DeleteMapping(ApiRoutes.Users.SESSION)
    public ResponseEntity<Void> revokeSession(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String sessionId) {
        settingsPort.revokeSession(principal.id(), sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /users/me
     * Deletes the authenticated user's account and all associated data.
     *
     * @param principal authenticated user principal
     * @param accessToken access token from cookie (optional)
     * @param refreshToken refresh token from cookie (optional)
     * @return 204 NO CONTENT
     */
    @DeleteMapping(ApiRoutes.Users.ME)
    public ResponseEntity<Void> deleteAccount(
            @AuthenticationPrincipal UserPrincipal principal,
            @CookieValue(name = "access_token", required = false) String accessToken,
            @CookieValue(name = "refresh_token", required = false) String refreshToken) {
        settingsPort.deleteAccount(principal.id(), accessToken, refreshToken);
        return ResponseEntity.noContent().build();
    }
}