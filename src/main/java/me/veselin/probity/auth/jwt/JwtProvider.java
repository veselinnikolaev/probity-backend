package me.veselin.probity.auth.jwt;

import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtProvider {
    private static final String signingKey = "";

    public static String generateJwt(UUID userId, Map<String, Object> claims) {
        long now = System.currentTimeMillis();
        long expiry = now + 3600 * 1000;

        Date iat = new Date(now);
        Date exp = new Date(expiry);




        return Jwts.builder()
                .issuer("probity")
                .subject(userId.toString())
                .issuedAt(iat)
                .expiration(exp)
                .signWith()
                .compact();
    }
}
