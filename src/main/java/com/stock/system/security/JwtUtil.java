package com.stock.system.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

public final class JwtUtil {

    private static final String SECRET = "stock-system-secret-key-2026-stock-system-secret-key";
    private static final long EXPIRE_MILLIS = 24 * 60 * 60 * 1000;

    private JwtUtil() {
    }

    private static SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    public static String createToken(Map<String, Object> claims) {
        return Jwts.builder()
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRE_MILLIS))
                .signWith(getSigningKey())
                .compact();
    }

    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static boolean isExpired(String token) {
        return parseToken(token).getExpiration().before(new Date());
    }

    public static long getExpireSeconds() {
        return EXPIRE_MILLIS / 1000;
    }
}
