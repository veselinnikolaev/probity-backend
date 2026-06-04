package me.veselin.probity.auth.port;

import me.veselin.probity.bff.dto.settings.*;

import java.util.List;
import java.util.UUID;

public interface UserSettingsPort {
    ProfileResponse getProfile(UUID id);

    ProfileResponse updateProfile(UUID id, UpdateProfileRequest request);

    void changePassword(UUID id, ChangePasswordRequest request);

    PreferencesResponse getPreferences(UUID id);

    PreferencesResponse updatePreferences(UUID id, UpdatePreferencesRequest request);

    List<SessionResponse> getActiveSessions(UUID id);

    void revokeSession(UUID id, String sessionId);

    void deleteAccount(UUID id, String accessToken, String refreshToken);
}
