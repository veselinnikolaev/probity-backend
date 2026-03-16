package me.veselin.probity.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import me.veselin.probity.auth.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class JwtService {
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${app.jwt.secret}")
    private String secret;
    private SecretKey key;

    @Value("${app.jwt.access.expiration.seconds}")
    private long accessExpirationTime;
    @Value("${app.jwt.refresh.expiration.seconds}")
    private long refreshExpirationTime;

    @Value("${app.jwt.issuer}")
    private String issuer;

    @Value("${app.jwt.refresh-prefix}")
    private String refreshPrefix;
    @Value("${app.jwt.blacklist-prefix}")
    private String blacklistedPrefix;

    @PostConstruct
    public void setKey() {
        if (secret.length() < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateAccessJwt(String subject, Map<String, Object> claims) {
        long expiry = System.currentTimeMillis() + accessExpirationTime * 1000;

        return constructJwt(subject, claims, expiry);
    }

    public String generateRefreshJwt(String subject, Map<String, Object> claims) {
        long expiry = System.currentTimeMillis() + refreshExpirationTime * 1000;

        return constructJwt(subject, claims, expiry);
    }

    public Claims extractClaims(String token) throws UnauthorizedException {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException("Token expired");
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid token");
        }
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    public String extractJti(String token) {
        return extractClaims(token).getId();
    }

    public boolean isTokenValid(String token, String username) {
        Claims claims = extractClaims(token);
        boolean notExpired = claims.getExpiration().after(new Date(System.currentTimeMillis()));
        return claims.getSubject().equals(username) && notExpired;
    }

    public void blacklistToken(String token) {
        Claims claims = extractClaims(token);
        String jti = claims.getId();
        Duration ttl = Duration.between(Instant.now(), claims.getExpiration().toInstant());
        if (!ttl.isNegative() && !ttl.isZero()) {
            redisTemplate.opsForValue().set(blacklistedPrefix + jti, "1", ttl);
        }
    }

    public boolean isBlacklisted(String jti) {
        return redisTemplate.hasKey(blacklistedPrefix + jti);
    }

    public void saveRefreshToken(String token) {
        redisTemplate.opsForValue().set(refreshPrefix + extractUsername(token), token, refreshExpirationTime, TimeUnit.SECONDS);
    }

    private String constructJwt(String subject, Map<String, Object> claims, long expiration) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(subject)
                .claims(claims)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(expiration))
                .signWith(key)
                .compact();
    }

    public String getRefreshToken(String username) {
        return redisTemplate.opsForValue().get(refreshPrefix + username);
    }

    public void deleteCorrespondingRefreshToken(String token) {
        redisTemplate.delete(refreshPrefix + extractUsername(token));
    }
}
