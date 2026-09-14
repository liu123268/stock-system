package com.stock.system.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    @Test
    void rejectsMissingSecret() {
        assertThatThrownBy(() -> new JwtUtil(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stock-system.jwt.secret 未配置");
    }

    @Test
    void rejectsSecretShorterThanHmacMinimum() {
        assertThatThrownBy(() -> new JwtUtil("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stock-system.jwt.secret 至少需要 32 个 UTF-8 字节");
    }

    @Test
    void signsAndParsesClaimsWithInjectedSecret() {
        JwtUtil jwtUtil = new JwtUtil("test-only-secret-with-at-least-32-bytes");

        String token = jwtUtil.createToken(Map.of("userId", 42L, "username", "test_user"));
        Claims claims = jwtUtil.parseToken(token);

        assertThat(claims.get("userId", Long.class)).isEqualTo(42L);
        assertThat(claims.get("username", String.class)).isEqualTo("test_user");
        assertThat(jwtUtil.getExpireSeconds()).isEqualTo(86_400L);
    }
}
