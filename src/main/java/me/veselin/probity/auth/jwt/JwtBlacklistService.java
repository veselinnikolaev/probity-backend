package me.veselin.probity.auth.jwt;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class JwtBlacklistService {
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${app.jwt.blacklist-prefix}")
    private String blacklistPrefix;

    public void blacklist(String jti, Instant exp) {
        Duration ttl = Duration.between(Instant.now(), exp);
        if (!ttl.isNegative() && !ttl.isZero()) {
            redisTemplate.opsForValue().set(key(jti), "1", ttl);
        }
    }

    public boolean isBlacklisted(String jti) {
        return redisTemplate.hasKey(key(jti));
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private String key(String jti) {
        return blacklistPrefix + jti;
    }
}
