package me.veselin.probity.bff.cookie;

import me.veselin.probity.common.ApiRoutes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class CookieService {
    @Value("${app.jwt.access.expiration.seconds}")
    private long accessExpirationTime;
    @Value("${app.jwt.refresh.expiration.seconds}")
    private long refreshExpirationTime;

    public ResponseCookie buildAccessCookie(String token) {
        return ResponseCookie.from("access_token", token)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(Duration.ofSeconds(accessExpirationTime))
                .sameSite("Strict")
                .build();
    }

    public ResponseCookie buildRefreshCookie(String token) {
        return ResponseCookie.from("refresh_token", token)
                .httpOnly(true)
                .secure(true)
                .path(ApiRoutes.Auth.REFRESH)  // only sent to /refresh — not every request
                .maxAge(Duration.ofSeconds(refreshExpirationTime))
                .sameSite("Strict")
                .build();
    }

    public ResponseCookie clearAccessCookie() {
        return ResponseCookie.from("access_token", "")
                .httpOnly(true).secure(true).path("/").maxAge(0).build();
    }

    public ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from("refresh_token", "")
                .httpOnly(true).secure(true).path(ApiRoutes.Auth.REFRESH).maxAge(0).build();
    }
}
